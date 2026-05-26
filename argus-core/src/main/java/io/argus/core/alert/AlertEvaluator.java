package io.argus.core.alert;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stateful rule evaluator with first-fire dedup.
 *
 * <p>For each {@link AlertRule}, tracks whether the rule is currently breached.
 * On the transition from not-breached → breached, an outcome with {@code firstFire=true}
 * is emitted (suitable for webhook dispatch). While ongoing, subsequent breaches emit
 * {@code firstFire=false}. When the metric returns to a non-breaching value, the
 * dedup state is cleared so the next breach will again first-fire.
 *
 * <p>This class is not thread-safe; one instance per polling thread.
 */
public final class AlertEvaluator {

    private final List<AlertRule> rules;
    private final Set<String> fired = new HashSet<>();

    public AlertEvaluator(List<AlertRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<AlertRule> rules() {
        return rules;
    }

    /**
     * Evaluates all rules against the provided metric snapshot.
     *
     * @param metrics metric name → value (e.g. from {@code PrometheusTextParser.parse})
     * @return one outcome per rule, in rule order
     */
    public List<Outcome> evaluate(Map<String, Double> metrics) {
        List<Outcome> out = new ArrayList<>(rules.size());
        for (AlertRule rule : rules) {
            Double raw = metrics.get(rule.metric());
            double value = raw != null ? raw : 0.0;
            boolean breached = rule.isBreached(value);
            boolean firstFire = false;
            if (breached) {
                if (!fired.contains(rule.name())) {
                    fired.add(rule.name());
                    firstFire = true;
                }
            } else {
                fired.remove(rule.name());
            }
            out.add(new Outcome(rule, value, breached, firstFire));
        }
        return out;
    }

    /** Returns the set of currently-firing rule names (defensive copy). */
    public Set<String> firingRules() {
        return new HashSet<>(fired);
    }

    /**
     * Result of one rule evaluation.
     */
    public static final class Outcome {
        private final AlertRule rule;
        private final double value;
        private final boolean breached;
        private final boolean firstFire;

        public Outcome(AlertRule rule, double value, boolean breached, boolean firstFire) {
            this.rule = rule;
            this.value = value;
            this.breached = breached;
            this.firstFire = firstFire;
        }

        public AlertRule rule() { return rule; }
        public double value() { return value; }
        public boolean breached() { return breached; }
        public boolean firstFire() { return firstFire; }
    }
}
