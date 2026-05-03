package co.lumen.credit.credit_orchestrator.adapter.in.web;

import co.lumen.credit.credit_orchestrator.adapter.in.web.dto.CreditRequestDto;
import co.lumen.credit.credit_orchestrator.adapter.in.web.dto.CreditResponseDto;
import co.lumen.credit.credit_orchestrator.adapter.in.web.mapper.CreditWebMapper;
import co.lumen.credit.credit_orchestrator.application.port.in.ApproveCreditUseCase;
import co.lumen.credit.credit_orchestrator.domain.model.ApprovalDecision;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Endpoint de entrada del orquestador.
 * DEPENDE de la interfaz ApproveCreditUseCase, NUNCA de ApproveCreditService.
 * Esto significa que mañana podrías reemplazar el servicio por otro
 * (ej: una versión "async" con Kafka) sin tocar una línea del controller.
 */
@RestController
@RequestMapping("/api/v1/credit")
@RequiredArgsConstructor
@Slf4j
public class CreditController {

    private final ApproveCreditUseCase approveCreditUseCase;
    private final CreditWebMapper mapper;

    @PostMapping("/evaluate")
    public ResponseEntity<CreditResponseDto> evaluate(@Valid @RequestBody CreditRequestDto request) {
        log.info("POST /api/v1/credit/evaluate cedula={}", request.cedula());

        long t0 = System.currentTimeMillis();
        ApprovalDecision decision = approveCreditUseCase.approve(mapper.toDomain(request));
        long latencia = System.currentTimeMillis() - t0;

        // Código HTTP según la decisión
        HttpStatus status = switch (decision.decision()) {
            case APROBADO        -> HttpStatus.OK;
            case RECHAZADO       -> HttpStatus.OK;              // decisión válida, no error HTTP
            case REVISION_MANUAL -> HttpStatus.ACCEPTED;        // 202: procesándose
        };

        return ResponseEntity.status(status).body(mapper.toResponse(decision, latencia));
    }

    /**
     * Health check custom del dominio (además del /actuator/health).
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Credit Orchestrator operativo");
    }
}
