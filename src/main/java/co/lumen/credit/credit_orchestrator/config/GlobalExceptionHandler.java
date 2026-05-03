package co.lumen.credit.credit_orchestrator.config;

import co.lumen.credit.credit_orchestrator.domain.exception.InvalidCreditApplicationException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/**
 * Manejador global de excepciones.
 * Convierte cualquier fallo en un ProblemDetail (RFC 7807):
 * JSON estándar con campos 'type', 'title', 'status', 'detail', y campos custom.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Errores de Bean Validation (campos faltantes, formatos inválidos, rangos).
     * Devuelve 400 Bad Request con lista de campos fallidos.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> errores = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of(
                        "campo", fe.getField(),
                        "mensaje", fe.getDefaultMessage() == null ? "inválido" : fe.getDefaultMessage(),
                        "valorRechazado", String.valueOf(fe.getRejectedValue())
                ))
                .toList();

        var pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Datos de solicitud inválidos");
        pd.setDetail("Uno o más campos no cumplen las reglas de validación.");
        pd.setProperty("errores", errores);
        return pd;
    }

    /**
     * Circuit breaker abierto: algún servicio externo está caído.
     * Devuelve 503 con info del circuito afectado.
     */
    @ExceptionHandler(CallNotPermittedException.class)
    public ProblemDetail handleCircuitOpen(CallNotPermittedException ex) {
        log.warn("Circuit breaker abierto: {}", ex.getCausingCircuitBreakerName());
        var pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Servicio temporalmente no disponible. Reintente en unos segundos.");
        pd.setTitle("Servicio externo caído");
        pd.setProperty("circuito", ex.getCausingCircuitBreakerName());
        return pd;
    }

    /**
     * Reglas de negocio violadas (ej: DTI imposible, monto > patrimonio).
     */
    @ExceptionHandler(InvalidCreditApplicationException.class)
    public ProblemDetail handleInvalidApplication(InvalidCreditApplicationException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setTitle("Solicitud inválida según reglas de negocio");
        return pd;
    }

    /**
     * Red de seguridad: cualquier excepción no manejada cae aquí.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        log.error("Error no esperado", ex);
        var pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Error interno. El equipo ha sido notificado.");
        pd.setTitle("Error interno");
        return pd;
    }
}
