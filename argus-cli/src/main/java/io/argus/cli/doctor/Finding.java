package io.argus.cli.doctor;

import java.util.List;

/**
 * A single diagnostic finding from a health check rule.
 *
 * <p>Contains the problem description, evidence (metric values),
 * root cause explanation, and actionable recommendations.
 */
public final class Finding {

    private final Severity severity;
    private final String category;
    private final String title;
    private final String detail;
    private final List<String> recommendations;
    private final List<String> suggestedFlags;

    public Finding(Severity severity, String category, String title, String detail,
                   List<String> recommendations, List<String> suggestedFlags) {
        this.severity = severity;
        this.category = category;
        this.title = title;
        this.detail = detail;
        this.recommendations = recommendations;
        this.suggestedFlags = suggestedFlags;
    }

    public Severity severity() { return severity; }
    public String category() { return category; }
    public String title() { return title; }
    public String detail() { return detail; }
    public List<String> recommendations() { return recommendations; }
    public List<String> suggestedFlags() { return suggestedFlags; }

    public static Builder builder(Severity severity, String category, String title) {
        return new Builder(severity, category, title);
    }

    public static final class Builder {
        private final Severity severity;
        private final String category;
        private final String title;
        private String detail = "";
        private final List<String> recommendations = new java.util.ArrayList<>();
        private final List<String> suggestedFlags = new java.util.ArrayList<>();

        private Builder(Severity severity, String category, String title) {
            this.severity = severity;
            this.category = category;
            this.title = title;
        }

        public Builder detail(String detail) { this.detail = detail; return this; }
        public Builder recommend(String r) { recommendations.add(r); return this; }
        public Builder flag(String f) { suggestedFlags.add(f); return this; }

        public Finding build() {
            return new Finding(severity, category, title, detail,
                    List.copyOf(recommendations), List.copyOf(suggestedFlags));
        }
    }
}
