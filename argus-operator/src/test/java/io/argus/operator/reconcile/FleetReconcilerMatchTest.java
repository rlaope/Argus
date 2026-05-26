package io.argus.operator.reconcile;

import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.LabelSelectorRequirement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FleetReconcilerMatchTest {

    @Test
    void isEmpty_handlesNullAndBlankSelectors() {
        assertTrue(FleetReconciler.isEmpty(null));
        assertTrue(FleetReconciler.isEmpty(new LabelSelector()));
        LabelSelector hasLabels = new LabelSelector();
        hasLabels.setMatchLabels(Map.of("k", "v"));
        assertFalse(FleetReconciler.isEmpty(hasLabels));
    }

    @Test
    void matches_matchLabelsConjunction() {
        LabelSelector sel = new LabelSelector();
        sel.setMatchLabels(Map.of("env", "prod", "tier", "api"));
        assertTrue(FleetReconciler.matches(Map.of("env", "prod", "tier", "api", "x", "y"), sel));
        assertFalse(FleetReconciler.matches(Map.of("env", "prod"), sel));
        assertFalse(FleetReconciler.matches(Map.of("env", "stage", "tier", "api"), sel));
    }

    @Test
    void matches_inOperator() {
        LabelSelector sel = new LabelSelector();
        LabelSelectorRequirement r = new LabelSelectorRequirement();
        r.setKey("region");
        r.setOperator("In");
        r.setValues(List.of("us-east", "us-west"));
        sel.setMatchExpressions(List.of(r));

        assertTrue(FleetReconciler.matches(Map.of("region", "us-east"), sel));
        assertTrue(FleetReconciler.matches(Map.of("region", "us-west"), sel));
        assertFalse(FleetReconciler.matches(Map.of("region", "eu-west"), sel));
        assertFalse(FleetReconciler.matches(Map.of(), sel));
    }

    @Test
    void matches_existsOperator() {
        LabelSelector sel = new LabelSelector();
        LabelSelectorRequirement r = new LabelSelectorRequirement();
        r.setKey("argus.io/managed");
        r.setOperator("Exists");
        sel.setMatchExpressions(List.of(r));

        assertTrue(FleetReconciler.matches(Map.of("argus.io/managed", ""), sel));
        assertFalse(FleetReconciler.matches(Map.of(), sel));
    }

    @Test
    void matches_doesNotExistOperator() {
        LabelSelector sel = new LabelSelector();
        LabelSelectorRequirement r = new LabelSelectorRequirement();
        r.setKey("exclude");
        r.setOperator("DoesNotExist");
        sel.setMatchExpressions(List.of(r));

        assertTrue(FleetReconciler.matches(Map.of("other", "x"), sel));
        assertFalse(FleetReconciler.matches(Map.of("exclude", "anything"), sel));
    }

    @Test
    void matches_unknownOperatorRejects() {
        LabelSelector sel = new LabelSelector();
        LabelSelectorRequirement r = new LabelSelectorRequirement();
        r.setKey("k");
        r.setOperator("MysteryOp");
        r.setValues(List.of("v"));
        sel.setMatchExpressions(List.of(r));

        assertFalse(FleetReconciler.matches(Map.of("k", "v"), sel));
    }

    @Test
    void matches_nullSelectorMatchesAnything() {
        assertTrue(FleetReconciler.matches(Map.of("k", "v"), null));
        assertTrue(FleetReconciler.matches(null, null));
    }
}
