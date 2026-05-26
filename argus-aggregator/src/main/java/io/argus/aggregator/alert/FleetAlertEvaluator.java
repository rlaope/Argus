package io.argus.aggregator.alert;

import io.argus.aggregator.model.AlertEvent;
import io.argus.aggregator.store.FleetRegistry;
import io.argus.core.alert.AlertEvaluator;
import io.argus.core.alert.AlertRule;
import io.argus.core.alert.WebhookSender;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-target wrapper around {@link AlertEvaluator}. Owns one evaluator instance
 * per registered {@code podId} so dedup state is independent across targets.
 *
 * <p>Updates {@link FleetRegistry}'s active-alert map on each evaluation and
 * dispatches webhook notifications on first fire.
 */
public final class FleetAlertEvaluator {

    private final FleetRegistry registry;
    private final List<AlertRule> rules;
    private final WebhookSender webhookSender = new WebhookSender();
    private final Map<String, AlertEvaluator> perPod = new ConcurrentHashMap<>();

    public FleetAlertEvaluator(FleetRegistry registry, List<AlertRule> rules) {
        this.registry = registry;
        this.rules = List.copyOf(rules);
    }

    public List<AlertRule> rules() {
        return rules;
    }

    /**
     * Evaluates all rules for one pod against its latest raw metric map.
     * Updates {@link FleetRegistry}'s active alert set in place.
     */
    public void evaluate(String podId, Map<String, Double> metrics) {
        if (rules.isEmpty() || metrics == null) return;
        AlertEvaluator evaluator = perPod.computeIfAbsent(podId, k -> new AlertEvaluator(rules));
        for (AlertEvaluator.Outcome outcome : evaluator.evaluate(metrics)) {
            AlertRule rule = outcome.rule();
            String alertId = AlertEvent.alertIdFor(podId, rule.name());
            if (outcome.breached()) {
                Instant firedAt = registry.getAlert(alertId)
                        .map(AlertEvent::firedAt)
                        .orElseGet(Instant::now);
                registry.recordAlert(new AlertEvent(
                        alertId, podId, rule.name(), rule.metric(),
                        outcome.value(), rule.threshold(), rule.comparator(),
                        rule.severity(), firedAt, true
                ));
                if (outcome.firstFire()) {
                    webhookSender.send(rule, outcome.value(), podId);
                }
            } else {
                registry.clearAlert(alertId);
            }
        }
    }

    /** Called when a target is deregistered — drops its evaluator state. */
    public void onPodRemoved(String podId) {
        perPod.remove(podId);
    }
}
