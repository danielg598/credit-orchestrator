package co.lumen.credit.credit_orchestrator.application.port.in;

import co.lumen.credit.credit_orchestrator.domain.model.ApprovalDecision;
import co.lumen.credit.credit_orchestrator.domain.model.CreditApplication;

/**
 * Puerto de entrada: contrato que expone el orquestador hacia los adaptadores
 * de entrada (controller REST, listener Kafka, CLI, tests).
 * El adaptador web depende de ESTA interfaz, NO de la implementación concreta.
 * Esa inversión de dependencia es el corazón del patrón Ports & Adapters.
 */
public interface ApproveCreditUseCase {

    ApprovalDecision approve(CreditApplication application);

}
