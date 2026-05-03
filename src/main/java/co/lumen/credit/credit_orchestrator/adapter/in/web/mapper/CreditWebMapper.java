package co.lumen.credit.credit_orchestrator.adapter.in.web.mapper;

import co.lumen.credit.credit_orchestrator.adapter.in.web.dto.CreditRequestDto;
import co.lumen.credit.credit_orchestrator.adapter.in.web.dto.CreditResponseDto;
import co.lumen.credit.credit_orchestrator.adapter.in.web.dto.CreditResponseDto.FactorClaveDto;
import co.lumen.credit.credit_orchestrator.domain.model.ApprovalDecision;
import co.lumen.credit.credit_orchestrator.domain.model.CreditApplication;
import org.springframework.stereotype.Component;

/**
 * Mapper entre DTOs web y objetos de dominio.
 * Se hace a mano (sin MapStruct) porque son pocos campos y así evitamos
 * una dependencia más. En producción grande, MapStruct vale la pena.
 */
@Component
public class CreditWebMapper {

    public CreditApplication toDomain(CreditRequestDto dto) {
        return new CreditApplication(
                dto.nombre(),
                dto.cedula(),
                dto.edad(),
                dto.ingresosMensuales(),
                dto.montoSolicitado(),
                dto.plazoMeses(),
                dto.historialCrediticio(),
                dto.propositoPrestamo()
        );
    }

    public CreditResponseDto toResponse(ApprovalDecision decision, long latenciaMs) {
        var assessment = decision.riskAssessment();

        var factores = assessment.factoresClave().stream()
                .map(f -> new FactorClaveDto(
                        f.factor(), f.impacto(), f.descripcion(), f.direction().name()))
                .toList();

        return new CreditResponseDto(
                decision.decisionId(),
                decision.decision().name(),
                decision.montoAprobado(),
                decision.tasaInteresEA(),
                decision.cuotaMensual(),
                decision.application().plazoMeses(),
                assessment.score(),
                assessment.probabilityDefault(),
                factores,
                assessment.explicacion(),
                decision.razonRechazo(),
                decision.vaultAccountId(),
                assessment.modeloVersion(),
                latenciaMs,
                decision.timestamp()
        );
    }
}
