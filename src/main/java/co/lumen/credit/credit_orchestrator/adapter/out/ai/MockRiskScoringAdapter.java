package co.lumen.credit.credit_orchestrator.adapter.out.ai;

import co.lumen.credit.credit_orchestrator.application.port.out.RiskScoringPort;
import co.lumen.credit.credit_orchestrator.domain.model.CreditApplication;
import co.lumen.credit.credit_orchestrator.domain.model.HistorialCrediticio;
import co.lumen.credit.credit_orchestrator.domain.model.RiskAssessment;
import co.lumen.credit.credit_orchestrator.domain.model.RiskAssessment.FactorClave;
import co.lumen.credit.credit_orchestrator.domain.model.RiskAssessment.FactorClave.Direction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Implementación "mock" del puerto de scoring.
 * Se activa cuando app.ai.mode=mock (valor por defecto mientras no tengas
 * FastAPI corriendo). Permite probar el orquestador de punta a punta
 * sin dependencias externas.
 * La lógica es una heurística simple pero razonable: a mayor DTI, menor edad
 * y peor historial, mayor probabilidad de default.
 */
@Component
@ConditionalOnProperty(name = "app.ai.mode", havingValue = "mock", matchIfMissing = true)
@Slf4j
public class MockRiskScoringAdapter implements RiskScoringPort{

    @Override
    public RiskAssessment assess(CreditApplication app) {
        log.info("[MOCK-IA] Evaluando solicitud de cedula={}", app.cedula());

        double dti = app.debtToIncomeEstimate().doubleValue();
        double pdBase = 0.10 + dti * 0.8;                // 10% base + ajuste por DTI
        pdBase += penalizacionHistorial(app.historialCrediticio());
        pdBase -= bonificacionEdad(app.edad());
        double pd = Math.max(0.01, Math.min(0.99, pdBase));
        int score = (int) Math.round((1.0 - pd) * 1000);

        return new RiskAssessment(
                score, pd,
                buildFactoresClave(app, dti),
                buildExplicacion(pd, dti, app),
                "mock-heuristic-v1",
                false
        );
    }

    private double penalizacionHistorial(HistorialCrediticio h) {
        return switch (h) {
            case EXCELENTE     -> -0.08;
            case BUENO         -> -0.03;
            case REGULAR       ->  0.05;
            case MALO          ->  0.25;
            case SIN_HISTORIAL ->  0.08;
        };
    }

    private double bonificacionEdad(int edad) {
        if (edad >= 30 && edad <= 55) return 0.05;  // edad estable = menor riesgo
        if (edad < 25) return -0.05;                // muy joven = más riesgo
        return 0.0;
    }

    private List<FactorClave> buildFactoresClave(CreditApplication app, double dti) {
        return List.of(
                new FactorClave("debt_to_income", dti,
                        "Relación cuota estimada vs ingresos mensuales",
                        dti > 0.3 ? Direction.AUMENTA_RIESGO : Direction.REDUCE_RIESGO),
                new FactorClave("historial_crediticio", 1.0,
                        "Calidad del historial en centrales: " + app.historialCrediticio(),
                        app.historialCrediticio() == HistorialCrediticio.MALO
                                ? Direction.AUMENTA_RIESGO : Direction.REDUCE_RIESGO),
                new FactorClave("edad", app.edad().doubleValue(),
                        "Edad del solicitante",
                        app.edad() >= 30 && app.edad() <= 55
                                ? Direction.REDUCE_RIESGO : Direction.AUMENTA_RIESGO)
        );
    }

    private String buildExplicacion(double pd, double dti, CreditApplication app) {
        String nivel = pd < 0.2 ? "bajo" : (pd < 0.5 ? "medio" : "alto");
        return String.format(
                "Riesgo %s (probabilidad de default %.1f%%). DTI estimado: %.1f%%. " +
                        "Historial: %s. Esta es una evaluación preliminar con modelo mock; " +
                        "la IA real ofrecerá mayor precisión.",
                nivel, pd * 100, dti * 100, app.historialCrediticio());
    }
}
