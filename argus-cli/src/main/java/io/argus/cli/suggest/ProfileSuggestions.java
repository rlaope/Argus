package io.argus.cli.suggest;

import io.argus.cli.model.MethodSample;
import io.argus.cli.model.ProfileSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Derives JVM-flag recommendations from a {@link ProfileSnapshot}.
 *
 * <p>Each inner rule reads the snapshot's method samples and emits zero or more
 * {@link ProfileRecommendation} entries. Rules are intentionally stateless and
 * side-effect free so they can be composed and tested in isolation.
 */
public final class ProfileSuggestions {

    // Threshold: fraction of total samples that must be attributed to matching
    // methods before a rule fires.
    private static final double STRING_DEDUP_THRESHOLD  = 0.05;  // 5%
    private static final double TIERED_COMP_THRESHOLD   = 0.05;  // 5%
    private static final double RUNTIME_WAIT_THRESHOLD  = 0.60;  // 60%
    private static final long   YOUNG_GEN_SAMPLE_MIN    = 10_000L;
    private static final double LOCK_CONTENTION_SINGLE  = 0.30;  // 30% of contention samples

    private static final Pattern STRING_DEDUP_PAT = Pattern.compile(
            "String\\.intern|StringTable::intern|HashMap.*hash|HashMap\\$Node");
    private static final Pattern TIERED_COMP_PAT = Pattern.compile(
            "^c2_compile|^OptoRuntime|^CompileBroker");
    private static final Pattern ESCAPE_ANALYSIS_PAT = Pattern.compile(
            "java\\.util\\.ArrayList\\$Itr|java\\.lang\\.Long|java\\.lang\\.Integer"
            + "|java\\.lang\\.Short|java\\.lang\\.Byte|java\\.lang\\.Boolean");
    private static final Pattern RUNTIME_WAIT_PAT = Pattern.compile(
            "Object\\.wait|Thread\\.sleep|park");

    private ProfileSuggestions() {}

    /**
     * Runs all rules against the given snapshot and returns the combined list
     * of recommendations, ordered by rule priority.
     */
    public static List<ProfileRecommendation> analyze(ProfileSnapshot snapshot) {
        List<ProfileRecommendation> out = new ArrayList<>();
        if (snapshot == null || snapshot.methods().isEmpty()) return out;

        out.addAll(stringDeduplication(snapshot));
        out.addAll(tieredCompilation(snapshot));
        out.addAll(escapeAnalysis(snapshot));
        out.addAll(youngGenSizing(snapshot));
        out.addAll(lockContention(snapshot));
        out.addAll(runtimeWait(snapshot));
        return out;
    }

    // -------------------------------------------------------------------------
    // Rule 1 – String Deduplication
    // -------------------------------------------------------------------------

    static List<ProfileRecommendation> stringDeduplication(ProfileSnapshot snapshot) {
        List<ProfileRecommendation> out = new ArrayList<>();
        long total = snapshot.totalSamples();
        if (total <= 0) return out;

        MethodSample top = null;
        long matched = 0;
        for (MethodSample m : snapshot.methods()) {
            if (STRING_DEDUP_PAT.matcher(m.method()).find()) {
                matched += m.samples();
                if (top == null || m.samples() > top.samples()) top = m;
            }
        }

        double fraction = (double) matched / total;
        if (fraction >= STRING_DEDUP_THRESHOLD && top != null) {
            out.add(new ProfileRecommendation(
                    "StringDeduplicationSuggestion",
                    Confidence.MED,
                    "-XX:+UseStringDeduplication",
                    "High String.intern / hash-table activity detected; G1 string deduplication"
                    + " reduces live-set and GC pressure",
                    top.method(),
                    fraction * 100.0
            ));
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Rule 2 – Tiered Compilation
    // -------------------------------------------------------------------------

    static List<ProfileRecommendation> tieredCompilation(ProfileSnapshot snapshot) {
        List<ProfileRecommendation> out = new ArrayList<>();
        long total = snapshot.totalSamples();
        if (total <= 0) return out;

        MethodSample top = null;
        long matched = 0;
        for (MethodSample m : snapshot.methods()) {
            if (TIERED_COMP_PAT.matcher(m.method()).find()) {
                matched += m.samples();
                if (top == null || m.samples() > top.samples()) top = m;
            }
        }

        double fraction = (double) matched / total;
        if (fraction >= TIERED_COMP_THRESHOLD && top != null) {
            out.add(new ProfileRecommendation(
                    "TieredCompilationSuggestion",
                    Confidence.MED,
                    "-XX:+TieredCompilation -XX:TieredStopAtLevel=4",
                    "Significant JIT compiler CPU time; explicit tiered compilation flag improves"
                    + " warmup-sensitive workload throughput",
                    top.method(),
                    fraction * 100.0
            ));
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Rule 3 – Escape Analysis
    // -------------------------------------------------------------------------

    static List<ProfileRecommendation> escapeAnalysis(ProfileSnapshot snapshot) {
        List<ProfileRecommendation> out = new ArrayList<>();
        long total = snapshot.totalSamples();
        if (total <= 0) return out;

        MethodSample top = null;
        long matched = 0;
        for (MethodSample m : snapshot.methods()) {
            if (ESCAPE_ANALYSIS_PAT.matcher(m.method()).find()) {
                matched += m.samples();
                if (top == null || m.samples() > top.samples()) top = m;
            }
        }

        double fraction = (double) matched / total;
        // Only fire if the single class dominates (>2% of all samples)
        if (fraction >= 0.02 && top != null) {
            out.add(new ProfileRecommendation(
                    "EscapeAnalysisSuggestion",
                    Confidence.LOW,
                    "-XX:+DoEscapeAnalysis -XX:+EliminateAllocations",
                    "Short-lived wrapper objects dominate alloc snapshot; escape analysis may"
                    + " eliminate stack-allocatable objects (already default in Java 21 — verify"
                    + " these flags are not disabled)",
                    top.method(),
                    fraction * 100.0
            ));
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Rule 4 – Young-Gen Sizing
    // -------------------------------------------------------------------------

    static List<ProfileRecommendation> youngGenSizing(ProfileSnapshot snapshot) {
        List<ProfileRecommendation> out = new ArrayList<>();
        long total = snapshot.totalSamples();
        if (total < YOUNG_GEN_SAMPLE_MIN) return out;

        // Soft hint: only fire when alloc snapshot has a large sample count
        if ("alloc".equalsIgnoreCase(snapshot.type())) {
            out.add(new ProfileRecommendation(
                    "YoungGenSizingSuggestion",
                    Confidence.LOW,
                    "-XX:NewRatio=2",
                    "High allocation volume detected (" + total + " samples); consider"
                    + " increasing young-gen size to reduce promotion pressure",
                    snapshot.methods().isEmpty() ? "" : snapshot.methods().get(0).method(),
                    snapshot.methods().isEmpty() ? 0.0 : snapshot.methods().get(0).percentage()
            ));
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Rule 5 – Lock Contention Hint
    // -------------------------------------------------------------------------

    static List<ProfileRecommendation> lockContention(ProfileSnapshot snapshot) {
        List<ProfileRecommendation> out = new ArrayList<>();
        if (!"lock".equalsIgnoreCase(snapshot.type())) return out;

        long total = snapshot.totalSamples();
        if (total <= 0) return out;

        for (MethodSample m : snapshot.methods()) {
            double fraction = (double) m.samples() / total;
            if (fraction >= LOCK_CONTENTION_SINGLE) {
                out.add(new ProfileRecommendation(
                        "LockContentionHint",
                        Confidence.HIGH,
                        "",   // non-flag hint
                        "Lock contention concentrated on " + m.method()
                        + "; consider concurrent collection or fine-grained locking",
                        m.method(),
                        fraction * 100.0
                ));
                break; // only report the hottest monitor
            }
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Rule 6 – Runtime Wait Hint
    // -------------------------------------------------------------------------

    static List<ProfileRecommendation> runtimeWait(ProfileSnapshot snapshot) {
        List<ProfileRecommendation> out = new ArrayList<>();
        long total = snapshot.totalSamples();
        if (total <= 0) return out;

        MethodSample top = null;
        long matched = 0;
        for (MethodSample m : snapshot.methods()) {
            if (RUNTIME_WAIT_PAT.matcher(m.method()).find()) {
                matched += m.samples();
                if (top == null || m.samples() > top.samples()) top = m;
            }
        }

        double fraction = (double) matched / total;
        if (fraction >= RUNTIME_WAIT_THRESHOLD && top != null) {
            out.add(new ProfileRecommendation(
                    "RuntimeWaitHint",
                    Confidence.HIGH,
                    "",   // non-flag hint
                    "Most CPU is wait time. Suggest reviewing thread pool sizing rather than JVM flags.",
                    top.method(),
                    fraction * 100.0
            ));
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Supporting types
    // -------------------------------------------------------------------------

    public enum Confidence { LOW, MED, HIGH }

    /**
     * A single profile-driven recommendation.
     */
    public static final class ProfileRecommendation {
        private final String ruleName;
        private final Confidence confidence;
        private final String flag;
        private final String rationale;
        private final String evidence;
        private final double evidencePct;

        public ProfileRecommendation(String ruleName, Confidence confidence, String flag,
                                     String rationale, String evidence, double evidencePct) {
            this.ruleName = ruleName;
            this.confidence = confidence;
            this.flag = flag;
            this.rationale = rationale;
            this.evidence = evidence;
            this.evidencePct = evidencePct;
        }

        public String ruleName() { return ruleName; }
        public Confidence confidence() { return confidence; }
        public String flag() { return flag; }
        public String rationale() { return rationale; }
        public String evidence() { return evidence; }
        public double evidencePct() { return evidencePct; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ProfileRecommendation)) return false;
            ProfileRecommendation that = (ProfileRecommendation) o;
            return Double.compare(that.evidencePct, evidencePct) == 0
                    && java.util.Objects.equals(ruleName, that.ruleName)
                    && confidence == that.confidence
                    && java.util.Objects.equals(flag, that.flag)
                    && java.util.Objects.equals(rationale, that.rationale)
                    && java.util.Objects.equals(evidence, that.evidence);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(ruleName, confidence, flag, rationale, evidence, evidencePct);
        }

        @Override
        public String toString() {
            return "ProfileRecommendation[ruleName=" + ruleName + ", confidence=" + confidence
                    + ", flag=" + flag + ", rationale=" + rationale + ", evidence=" + evidence
                    + ", evidencePct=" + evidencePct + "]";
        }
    }
}
