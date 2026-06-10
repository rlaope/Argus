package io.argus.apm;

import io.argus.apm.model.ApmBackendSignal;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Type;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApmFacadeBoundaryTest {
    @Test
    void publicRoutesDoNotExposeAggregatorV1Alpha1Routes() {
        for (String route : ApmFacadeRoutes.publicRoutes()) {
            assertFalse(ApmFacadeRoutes.isForbiddenAggregatorRoute(route));
        }
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.argus.aggregator.model.PodTarget"));
    }

    @Test
    void facadeSignaturesStayOnApmDtos() {
        for (Method method : ApmFacade.class.getDeclaredMethods()) {
            assertNoAggregatorType(method.getGenericReturnType());
            for (Type parameterType : method.getGenericParameterTypes()) {
                assertNoAggregatorType(parameterType);
            }
        }
        assertFalse(ApmFacadeRoutes.isForbiddenAggregatorRoute("/apm/backend-links"));
        assertFalse(ApmBackendSignal.METRICS.name().isBlank());
    }

    private static void assertNoAggregatorType(Type type) {
        assertFalse(type.getTypeName().contains("io.argus.aggregator"), type.getTypeName());
    }
}
