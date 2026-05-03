package co.lumen.credit.credit_orchestrator.adapter.out.vault;

import co.lumen.credit.credit_orchestrator.application.port.out.CoreBankingPort;
import co.lumen.credit.credit_orchestrator.domain.model.CreditApplication;
import co.lumen.credit.credit_orchestrator.domain.model.RiskAssessment;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Adapter ÚNICO hacia Vault Core. Habla HTTP/REST con:
 *   - Mock FastAPI local (http://localhost:9000) durante desarrollo
 *   - Vault real (https://core-api.tm.blx-demo.com/) cuando tengas VPN + token
 * El cambio es una variable de entorno. Este código NO cambia.
 */
@Component
@Slf4j
public class VaultCoreAdapter implements CoreBankingPort {

    private final RestClient vault;
    private final String productId;

    public VaultCoreAdapter(
            @Qualifier("vaultRestClient") RestClient vault,
            @Value("${app.vault.product-id}") String productId) {
        this.vault = vault;
        this.productId = productId;
    }

    @Override
    @CircuitBreaker(name = "vaultService", fallbackMethod = "validateFallback")
    public VaultValidation validateLoan(CreditApplication app, RiskAssessment a) {
        log.info("[VAULT] Validando contra smart contract productId={}, score={}",
                productId, a.score());

        Map<String, Object> body = Map.of(
                "product_id", productId,
                "instance_param_vals", Map.of(
                        "principal",        app.montoSolicitado().toString(),
                        "loan_term_months", app.plazoMeses().toString(),
                        "risk_score",       String.valueOf(a.score()),
                        "customer_age",     app.edad().toString()
                )
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> resp = vault.post()
                .uri("/v1/smart-contracts/validate")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .accept(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        boolean accepted = Boolean.TRUE.equals(resp.get("accepted"));
        String  reason   = (String) resp.get("reason");
        String  code     = (String) resp.get("reason_code");
        return new VaultValidation(accepted, reason, code);
    }

    @Override
    @CircuitBreaker(name = "vaultService", fallbackMethod = "createAccountFallback")
    public String createPendingAccount(CreditApplication app, BigDecimal tasaEA) {
        String accountId = "LOAN-" + app.cedula() + "-" + System.currentTimeMillis();
        Map<String, Object> body = Map.of(
                "request_id", UUID.randomUUID().toString(),
                "account", Map.of(
                        "id", accountId,
                        "product_id", productId,
                        "product_version_id", "1",
                        "status", "ACCOUNT_STATUS_PENDING",
                        "stakeholder_ids", List.of(app.cedula()),
                        "permitted_denominations", List.of("COP"),
                        "instance_param_vals", Map.of(
                                "principal",            app.montoSolicitado().toString(),
                                "annual_interest_rate", tasaEA.toString(),
                                "loan_term_months",     app.plazoMeses().toString()
                        )
                )
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> resp = vault.post()
                .uri("/v1/accounts")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .accept(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        Map<String, Object> acc = (Map<String, Object>) resp.get("account");
        String id = (String) acc.get("id");
        log.info("[VAULT] Cuenta creada: {}", id);
        return id;
    }

    @SuppressWarnings("unused")
    private VaultValidation validateFallback(CreditApplication app, RiskAssessment a, Throwable t) {
        log.warn("[VAULT] Fallback en validateLoan: {}", t.getMessage());
        return VaultValidation.reject("Core temporalmente no disponible");
    }

    @SuppressWarnings("unused")
    private String createAccountFallback(CreditApplication app, BigDecimal tasaEA, Throwable t) {
        log.warn("[VAULT] Fallback en createPendingAccount: {}", t.getMessage());
        return "PENDING-LOCAL-" + UUID.randomUUID();
    }
}
