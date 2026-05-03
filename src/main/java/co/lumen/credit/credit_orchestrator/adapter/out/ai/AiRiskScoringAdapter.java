package co.lumen.credit.credit_orchestrator.adapter.out.ai;

import co.lumen.credit.credit_orchestrator.application.port.out.RiskScoringPort;
import co.lumen.credit.credit_orchestrator.domain.model.CreditApplication;
import co.lumen.credit.credit_orchestrator.domain.model.RiskAssessment;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Cliente HTTP al microservicio FastAPI de credit scoring.
 * Activo cuando app.ai.mode=real. Inyecta el RestClient con baseUrl y timeouts.
 * Si el servicio cae o excede el timeout, @CircuitBreaker activa el fallback
 * que deriva a revisión manual (nunca bloquea al cliente).
 */
@Component
@ConditionalOnProperty(name = "app.ai.mode", havingValue = "real")
@Slf4j
public class AiRiskScoringAdapter implements RiskScoringPort{

    private final RestClient ai;

    public AiRiskScoringAdapter(@Qualifier("aiRestClient") RestClient ai) {
        this.ai = ai;
    }

    @Override
    @CircuitBreaker(name = "aiService", fallbackMethod = "fallback")
    @Retry(name = "aiService")
    public RiskAssessment assess(CreditApplication app) {
        log.info("[AI-REAL] Llamando FastAPI /predict para cedula={}", app.cedula());

        // Payload que mapea a features del dataset German Credit.
        // Usamos Map.ofEntries porque Map.of tiene límite de 10 pares.
        Map<String, Object> request = Map.ofEntries(
                Map.entry("duration", app.plazoMeses()),
                Map.entry("credit_amount", app.montoSolicitado().doubleValue()),
                Map.entry("age", app.edad()),
                Map.entry("checking_status", "A12"),
                Map.entry("credit_history", mapHistorial(app.historialCrediticio().name())),
                Map.entry("purpose", "A43"),
                Map.entry("savings", "A63"),
                Map.entry("employment", "A73"),
                Map.entry("installment_rate", 3),
                Map.entry("personal_status", "A93"),
                Map.entry("housing", "A152"),
                Map.entry("job", "A173")
        );

        Map<String, Object> resp = ai.post()
                .uri("/predict")
                .body(request)
                .retrieve()
                .body(Map.class);

        return toDomain(resp);
    }

    /**
     * Fallback activado por Resilience4j cuando el circuit breaker está abierto
     * o tras N retries fallidos. La firma debe aceptar el mismo argumento + Throwable.
     */
    @SuppressWarnings("unused")
    private RiskAssessment fallback(CreditApplication app, Throwable t) {
        log.warn("[AI-REAL] Fallback activado. Causa: {}", t.getMessage());
        return RiskAssessment.manualReview();
    }

    @SuppressWarnings("unchecked")
    private RiskAssessment toDomain(Map<String, Object> r) {
        // TODO: cuando el Python esté listo, parsear factoresClave reales del JSON.
        return new RiskAssessment(
                ((Number) r.get("score")).intValue(),
                ((Number) r.get("probabilidad_default")).doubleValue(),
                List.of(),
                (String) r.get("explicacion"),
                (String) r.getOrDefault("model_version", "unknown"),
                false
        );
    }

    private String mapHistorial(String h) {
        // German Credit codes: A30=no credits, A31=all paid, A32=paid till now, A33=delay, A34=critical
        return switch (h) {
            case "EXCELENTE" -> "A31";
            case "BUENO"     -> "A32";
            case "REGULAR"   -> "A33";
            case "MALO"      -> "A34";
            default          -> "A30";
        };
    }
}
