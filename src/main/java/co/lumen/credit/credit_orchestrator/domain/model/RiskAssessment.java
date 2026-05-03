package co.lumen.credit.credit_orchestrator.domain.model;

import java.util.List;

public record RiskAssessment(
        Integer score,                 // 0-1000, mayor = mejor
        Double probabilityDefault,     // 0.0 - 1.0
        List<FactorClave> factoresClave,
        String explicacion,
        String modeloVersion,
        boolean requiresManualReview   // true si el circuit breaker activó el fallback
) {

    public record FactorClave(
            String factor,
            Double impacto,
            String descripcion,
            Direction direction
    ) {
        public enum Direction { AUMENTA_RIESGO, REDUCE_RIESGO }
    }

    /**
     * Constructor para el fallback cuando la IA no responde.
     * Se usa cuando Resilience4j activa el circuit breaker.
     */
    public static RiskAssessment manualReview() {
        return new RiskAssessment(
                0, 1.0, List.of(),
                "Servicio de scoring no disponible, enviado a revisión manual",
                "fallback-v1", true
        );
    }
}
