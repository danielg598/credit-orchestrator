package co.lumen.credit.credit_orchestrator.domain.model;

import java.math.BigDecimal;

public record CreditApplication(
        String nombre,
        String cedula,
        Integer edad,
        BigDecimal ingresosMensuales,
        BigDecimal montoSolicitado,
        Integer plazoMeses,
        HistorialCrediticio historialCrediticio,
        PropositoPrestamo propositoPrestamo
) {

    /**
     * Ratio deuda/ingreso mensual estimado (cuota aproximada / ingresos).
     * Útil como feature derivada y para validaciones rápidas.
     */
    public BigDecimal debtToIncomeEstimate() {
        if (ingresosMensuales == null || ingresosMensuales.signum() == 0) {
            return BigDecimal.ZERO;
        }
        // cuota estimada simple: monto / plazo (sin intereses, solo aproximación)
        BigDecimal cuotaEstimada = montoSolicitado.divide(
                BigDecimal.valueOf(plazoMeses), 2, java.math.RoundingMode.HALF_UP);
        return cuotaEstimada.divide(ingresosMensuales, 4, java.math.RoundingMode.HALF_UP);
    }
}
