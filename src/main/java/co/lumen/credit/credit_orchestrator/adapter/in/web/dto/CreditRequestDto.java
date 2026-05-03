package co.lumen.credit.credit_orchestrator.adapter.in.web.dto;

import co.lumen.credit.credit_orchestrator.domain.model.HistorialCrediticio;
import co.lumen.credit.credit_orchestrator.domain.model.PropositoPrestamo;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/**
 * Payload JSON que llega del frontend (Angular) al endpoint POST /api/v1/credit/evaluate.
 * Las anotaciones de jakarta.validation validan CADA campo automáticamente.
 * Si falla alguna, Spring dispara MethodArgumentNotValidException y el
 * GlobalExceptionHandler la convierte en un 400 Bad Request con detalle.
 */
public record CreditRequestDto(
        @NotBlank(message = "El nombre es obligatorio")
        @Size(min = 2, max = 100)
        String nombre,

        @NotBlank(message = "La cédula es obligatoria")
        @Pattern(regexp = "\\d{6,10}", message = "La cédula debe tener entre 6 y 10 dígitos")
        String cedula,

        @NotNull(message = "La edad es obligatoria")
        @Min(value = 18, message = "Edad mínima: 18 años")
        @Max(value = 75, message = "Edad máxima: 75 años")
        Integer edad,

        @NotNull(message = "Los ingresos son obligatorios")
        @DecimalMin(value = "1000000", message = "Ingresos mínimos: COP 1.000.000")
        BigDecimal ingresosMensuales,

        @NotNull(message = "El monto solicitado es obligatorio")
        @DecimalMin(value = "500000", message = "Monto mínimo: COP 500.000")
        @DecimalMax(value = "100000000", message = "Monto máximo: COP 100.000.000")
        BigDecimal montoSolicitado,

        @NotNull(message = "El plazo es obligatorio")
        @Min(value = 6, message = "Plazo mínimo: 6 meses")
        @Max(value = 120, message = "Plazo máximo: 120 meses")
        Integer plazoMeses,

        @NotNull(message = "El historial crediticio es obligatorio")
        HistorialCrediticio historialCrediticio,

        @NotNull(message = "El propósito del préstamo es obligatorio")
        PropositoPrestamo propositoPrestamo
) {}
