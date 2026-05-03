package co.lumen.credit.credit_orchestrator.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ApprovalDecision(
        String decisionId,
        Decision decision,
        CreditApplication application,
        RiskAssessment riskAssessment,
        BigDecimal montoAprobado,
        BigDecimal tasaInteresEA,
        BigDecimal cuotaMensual,
        String vaultAccountId,
        String razonRechazo,        // null si aprobado
        Instant timestamp
) {

    public enum Decision { APROBADO, RECHAZADO, REVISION_MANUAL }

    public static ApprovalDecision approved(
            CreditApplication app, RiskAssessment a,
            BigDecimal tasaEA, BigDecimal cuota, String accountId) {
        return new ApprovalDecision(
                "dec_" + UUID.randomUUID(),
                Decision.APROBADO, app, a,
                app.montoSolicitado(), tasaEA, cuota, accountId,
                null, Instant.now()
        );
    }

    public static ApprovalDecision rejected(
            CreditApplication app, RiskAssessment a, String razon) {
        return new ApprovalDecision(
                "dec_" + UUID.randomUUID(),
                Decision.RECHAZADO, app, a,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null,
                razon, Instant.now()
        );
    }

    public static ApprovalDecision manualReview(CreditApplication app, RiskAssessment a) {
        return new ApprovalDecision(
                "dec_" + UUID.randomUUID(),
                Decision.REVISION_MANUAL, app, a,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null,
                "Enviado a analista humano", Instant.now()
        );
    }
}
