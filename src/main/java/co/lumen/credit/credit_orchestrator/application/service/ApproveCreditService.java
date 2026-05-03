package co.lumen.credit.credit_orchestrator.application.service;

import co.lumen.credit.credit_orchestrator.application.port.in.ApproveCreditUseCase;
import co.lumen.credit.credit_orchestrator.application.port.out.CoreBankingPort;
import co.lumen.credit.credit_orchestrator.application.port.out.CoreBankingPort.VaultValidation;
import co.lumen.credit.credit_orchestrator.application.port.out.RiskScoringPort;
import co.lumen.credit.credit_orchestrator.domain.model.ApprovalDecision;
import co.lumen.credit.credit_orchestrator.domain.model.CreditApplication;
import co.lumen.credit.credit_orchestrator.domain.model.RiskAssessment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApproveCreditService implements ApproveCreditUseCase {

    private final RiskScoringPort riskScoringPort;     // inyección por constructor (Lombok)
    private final CoreBankingPort coreBankingPort;

    // --- Parámetros de política (en producción vivirían en la tabla de pricing) ---
    private static final double PD_UMBRAL_RECHAZO = 0.5;       // rechaza si prob. default > 50%
    private static final double TASA_BASE_EA      = 0.15;       // 15% EA base
    private static final double PRIMA_MAX_RIESGO  = 0.30;       // hasta +30 pp por riesgo

    @Override
    public ApprovalDecision approve(CreditApplication app) {
        log.info("Procesando solicitud: cedula={}, monto={}, plazo={}",
                app.cedula(), app.montoSolicitado(), app.plazoMeses());

        // 1. Evaluación de IA: score + probabilidad de default + explicación en español
        RiskAssessment assessment = riskScoringPort.assess(app);
        log.debug("IA respondió: score={}, pd={}, manualReview={}",
                assessment.score(), assessment.probabilityDefault(), assessment.requiresManualReview());

        // 1.1 Si la IA cayó y el adapter devolvió el fallback, va a revisión humana
        if (assessment.requiresManualReview()) {
            log.warn("Derivando a revisión manual por fallback de IA");
            return ApprovalDecision.manualReview(app, assessment);
        }

        // 2. Reglas duras del smart contract (pre_posting_code): score mínimo, edad, monto...
        VaultValidation validation = coreBankingPort.validateLoan(app, assessment);
        log.debug("Vault validation: accepted={}, reason={}",
                validation.accepted(), validation.reason());

        // 3. Decisión final = IA probabilística ∩ reglas determinísticas del contract
        if (!validation.accepted()) {
            return ApprovalDecision.rejected(app, assessment,
                    "Regla de política: " + validation.reason());
        }
        if (assessment.probabilityDefault() >= PD_UMBRAL_RECHAZO) {
            return ApprovalDecision.rejected(app, assessment,
                    "Probabilidad de default demasiado alta");
        }

        // 4. Pricing dinámico: tasa crece con el riesgo (mecanismo de compensación)
        BigDecimal tasaEA = calcularTasa(assessment.probabilityDefault());
        BigDecimal cuota  = calcularCuotaPMT(app.montoSolicitado(), tasaEA, app.plazoMeses());

        // 5. (Opcional pero demostrable en el pitch) Crear cuenta PENDING en Vault
        String accountId = coreBankingPort.createPendingAccount(app, tasaEA);

        log.info("APROBADO: accountId={}, tasaEA={}, cuota={}", accountId, tasaEA, cuota);
        return ApprovalDecision.approved(app, assessment, tasaEA, cuota, accountId);
    }

    /**
     * Pricing dinámico simple: tasa base + prima proporcional al riesgo.
     * Ej: pd=0.05 → 0.15 + 0.05*0.30 = 0.165 (16.5% EA)
     *     pd=0.30 → 0.15 + 0.30*0.30 = 0.240 (24% EA)
     */
    private BigDecimal calcularTasa(double probabilityDefault) {
        double tasa = TASA_BASE_EA + probabilityDefault * PRIMA_MAX_RIESGO;
        return BigDecimal.valueOf(tasa).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Cuota fija mensual (sistema francés / PMT):
     *    cuota = P * i / (1 - (1+i)^(-n))
     * donde i es la tasa mensual equivalente y n el plazo en meses.
     */
    private BigDecimal calcularCuotaPMT(BigDecimal principal, BigDecimal tasaEA, int plazoMeses) {
        double iMensual = Math.pow(1 + tasaEA.doubleValue(), 1.0 / 12) - 1;
        double pmt = principal.doubleValue() * iMensual
                / (1 - Math.pow(1 + iMensual, -plazoMeses));
        return BigDecimal.valueOf(pmt).setScale(0, RoundingMode.HALF_UP);
    }

}
