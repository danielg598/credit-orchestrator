package co.lumen.credit.credit_orchestrator.adapter.in.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Respuesta JSON que devuelve el orquestador al frontend después de
 * evaluar una solicitud de crédito.
 * Contiene decisión + pricing + explicación en lenguaje natural + métricas.
 * El frontend la renderiza como gauge (score), card (decisión) y chips (factores).
 */
public record CreditResponseDto(
        String decisionId,
        String decision,                 // APROBADO | RECHAZADO | REVISION_MANUAL
        BigDecimal montoAprobado,
        BigDecimal tasaInteresEA,        // tasa efectiva anual (ej: 0.185 = 18.5%)
        BigDecimal cuotaMensual,
        Integer plazoMeses,
        Integer score,                   // 0-1000
        Double probabilidadDefault,      // 0.0 - 1.0
        List<FactorClaveDto> factoresClave,
        String explicacion,
        String razonRechazo,             // null si aprobado
        String vaultAccountId,           // null si rechazado
        String modeloVersion,
        Long latenciaMs,
        Instant timestamp
) {
    public record FactorClaveDto(
            String factor,
            Double impacto,
            String descripcion,
            String direction              // AUMENTA_RIESGO | REDUCE_RIESGO
    ) {}
}
