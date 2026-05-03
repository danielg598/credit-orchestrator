package co.lumen.credit.credit_orchestrator.application.port.out;

import co.lumen.credit.credit_orchestrator.domain.model.CreditApplication;
import co.lumen.credit.credit_orchestrator.domain.model.RiskAssessment;

/**
 * Puerto de salida: el servicio necesita "alguien" que calcule el riesgo
 * de una solicitud. No sabe (ni le importa) si ese alguien es:
 *  - un microservicio Python por HTTP (AiRiskScoringAdapter, prod)
 *  - un mock determinístico en memoria (MockRiskScoringAdapter, tests)
 *  - un modelo local via ONNX, un endpoint SageMaker, etc.
 * Esa sustituibilidad es lo que nos permite testear el servicio sin IA real.
 */
public interface RiskScoringPort {
    RiskAssessment assess(CreditApplication application);
}
