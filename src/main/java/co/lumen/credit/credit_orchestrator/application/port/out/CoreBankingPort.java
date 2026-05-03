package co.lumen.credit.credit_orchestrator.application.port.out;

import co.lumen.credit.credit_orchestrator.domain.model.CreditApplication;
import co.lumen.credit.credit_orchestrator.domain.model.RiskAssessment;

import java.math.BigDecimal;

/**
 * Puerto de salida al core bancario.
 * Implementado por un único VaultCoreAdapter cuya URL apunta al Mock local
 * o a Vault real (https://core-api.tm.blx-demo.com/) según variable de entorno.
 * Cuando tengas la VPN y el X-Auth-Token real, ESTE archivo no cambia.
 * Solo cambia el application.yml. Ese es el valor del hexagonal.
 */
public interface CoreBankingPort {
    /**
     * Ejecuta pre_posting_code del smart contract para validar la solicitud
     * contra reglas duras de política (score mínimo, edad, monto máximo, etc.).
     */
    VaultValidation validateLoan(CreditApplication application, RiskAssessment assessment);

    /**
     * Crea la cuenta de préstamo en estado PENDING en Vault.
     * Retorna el account_id asignado por Vault (se guarda en la ApprovalDecision).
     */
    String createPendingAccount(CreditApplication application, BigDecimal tasaInteresEA);

    /**
     * Resultado de validateLoan. Record anidado = vive "con" su puerto,
     * evita contaminar el paquete domain con tipos de infraestructura.
     */
    record VaultValidation(boolean accepted, String reason, String reasonCode) {
        public static VaultValidation accept() {
            return new VaultValidation(true, null, null);
        }
        public static VaultValidation reject(String reason) {
            return new VaultValidation(false, reason, "POLICY_REJECTION");
        }
    }
}
