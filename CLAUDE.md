# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`credit-orchestrator` is the central orchestrator of **Lumen**, a credit-evaluation demo system. It's the **only** point of contact between the frontend and the backend: every dashboard request passes through here. It validates the incoming request, delegates risk scoring to a Python AI microservice, validates product rules against a core-banking Smart Contract, creates the loan account if approved, computes risk-adjusted pricing, and returns the full decision to the frontend.

It has no ML logic, no banking-ledger logic, and persists nothing of its own — it is transactional glue code, which is why it's Java/Spring rather than Python.

Port **8080** · Java 21 · Spring Boot 3.5.13. Full narrative documentation (in Spanish, with rationale for every architectural decision) lives in `README-credit-orchestrator.md` — read it for the "why" behind a change; this file is the "how to work here" complement, not a replacement.

### Sibling repositories (separate git repos, opened as additional working directories in this session)

- **`credit-ai-service`** (`C:\desarrollos\credit-ai-service`, port 8000, FastAPI/Python) — scikit-learn LogisticRegression scoring model + SHAP explainability + Groq LLM natural-language explanation. Called by `AiRiskScoringAdapter` via `POST /predict`.
- **`vault-mock`** (`C:\desarrollos\vault-mock`, port 9000, FastAPI/Python) — local simulator of Thought Machine's Vault Core banking platform (Smart Contracts as executable Python, in-memory account store). Called by `VaultCoreAdapter`. Has its own `CLAUDE.md`.
- **`credit-dashboard`** (`C:\desarrollos\credit-dashboard`, port 4200, Angular 17) — the only UI. Calls this orchestrator at `:8080/api/v1/credit/evaluate`; also calls `vault-mock` directly in dev only (never in prod) to list accounts.

The migration path off mocks is a pure environment-variable swap (`APP_AI_MODE=real`, `APP_VAULT_BASE_URL`, `APP_VAULT_AUTH_TOKEN`) — no code changes in this repo.

## Commands

```bash
# Compile + run
mvn clean install
mvn spring-boot:run

# Run a single test class
mvn test -Dtest=CreditOrchestratorApplicationTests

# Unit tests only
mvn test
# Integration tests (Testcontainers)
mvn verify
```

Swagger UI: `http://localhost:8080/swagger-ui.html`. Actuator health: `GET /actuator/health`.

There's currently only a context-load smoke test (`CreditOrchestratorApplicationTests`) — no real unit/integration test suite exists yet despite `mvn verify`/Testcontainers being wired in `pom.xml`. Coverage target once tests exist: **>75% on `application/service`**; adapters get mock/contract tests.

### Environment variables (mock is the default; see `application.yaml`)

| Var | Default | Purpose |
|---|---|---|
| `APP_AI_MODE` | `mock` | `mock` uses `MockRiskScoringAdapter` (in-process heuristic); `real` switches to `AiRiskScoringAdapter` calling `credit-ai-service` |
| `APP_AI_BASE_URL` | `http://localhost:8000` | credit-ai-service base URL |
| `APP_VAULT_BASE_URL` | `http://localhost:9000` | vault-mock, or real Vault (`https://core-api.tm.blx-demo.com`) |
| `APP_VAULT_AUTH_TOKEN` | `mock-dev-token` | Sent as `X-Auth-Token` header — **not** `Authorization: Bearer`, this mirrors real Vault |
| `APP_VAULT_PRODUCT_ID` | `personal_loan_ai` | Smart Contract product id to validate against |
| `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:4200,http://localhost:3000` | Comma-separated origins for `/api/**` |
| `APP_TRACING_SAMPLING` | `1.0` | OTel trace sampling probability |
| `APP_OTLP_ENDPOINT` | `http://localhost:4318/v1/traces` | OTLP collector; `docker-compose.observability.yml` spins up Jaeger for local viewing at `:16686` |

Run `docker compose -f docker-compose.observability.yml up` to get a local Jaeger UI for the distributed traces emitted by this service (and, if configured the same way, by `credit-ai-service`/`vault-mock`).

## Architecture

Hexagonal (ports & adapters, Alistair Cockburn). The rule that matters when adding code: **`domain/` and `application/` never import Spring, HTTP client types, or anything adapter-specific — only `application/port/*` interfaces.**

```
adapter/in/web    → CreditController → ApproveCreditUseCase (port in)
                                              │
                                              ▼
                                   ApproveCreditService (application/service)
                                     — the only class with business logic
                                    /                              \
                        RiskScoringPort (port out)         CoreBankingPort (port out)
                          /                    \                     │
          MockRiskScoringAdapter      AiRiskScoringAdapter   VaultCoreAdapter
          (app.ai.mode=mock,          (app.ai.mode=real,     (always active;
           default, no I/O)           HTTP → credit-ai-service) HTTP → vault-mock/Vault)
```

`ApproveCreditService.approve()` is the entire business flow, in order:
1. `riskScoringPort.assess(app)` — if the adapter's circuit breaker tripped, the fallback sets `requiresManualReview=true` and the flow short-circuits to `REVISION_MANUAL` immediately (does not proceed to Vault).
2. `coreBankingPort.validateLoan(app, assessment)` — runs the Vault Smart Contract's `pre_posting_code`. Rejection here is a normal `200` from Vault with `accepted:false`, not an HTTP error.
3. Hard threshold check: `probabilityDefault >= 0.5` → reject, independent of what Vault said.
4. Pricing: `calcularTasa` (base 15% EA + up to 30pp risk premium, linear in PD) and `calcularCuotaPMT` (French amortization system).
5. `coreBankingPort.createPendingAccount(app, tasaEA)` — only reached on approval.

**Which adapter is live is decided entirely by `@ConditionalOnProperty(name = "app.ai.mode", ...)`** on `MockRiskScoringAdapter` vs `AiRiskScoringAdapter` — exactly one of the two beans exists at a time. `VaultCoreAdapter` has no such switch; it's always active and its target (mock vs real Vault) is purely the `APP_VAULT_BASE_URL` value.

Both outbound adapters (`AiRiskScoringAdapter`, `VaultCoreAdapter`) are wrapped in Resilience4j `@CircuitBreaker` (+ `@Retry` for AI only); every fallback method has the signature `(originalArgs..., Throwable) -> sameReturnType` and is resolved by Resilience4j via reflection matching. Fallback semantics differ deliberately by failure mode:
- AI service down → reject-by-default (`RiskAssessment.manualReview()`, conservative — never silently approve without scoring).
- Vault down → `REVISION_MANUAL` for validation, or a synthetic `PENDING-LOCAL-<uuid>` id for account creation — a human resolves it later, request is never blocked.

`GlobalExceptionHandler` (`@RestControllerAdvice`) converts everything to RFC 7807 `ProblemDetail`: `MethodArgumentNotValidException` → 400 with per-field errors, `CallNotPermittedException` (circuit open) → 503, `InvalidCreditApplicationException` → 422, anything else → 500.

### Two `RestClient` beans, not one

`RestClientConfig` declares `aiRestClient` and `vaultRestClient` separately (different base URLs, timeouts, and — critically — `vaultRestClient` sends `X-Auth-Token` as a default header, which is Vault's real convention, not `Authorization: Bearer`). Both are built on **Apache HttpClient 5**, not the JDK `HttpClient` that Spring 3.5 defaults to — the JDK factory has a known bug where POST bodies serialize to empty against this stack (Content-Length 0, downstream 422s). If a new outbound adapter is added, reuse `HttpComponentsClientHttpRequestFactory` the same way; don't fall back to the default factory.

### Domain records

`CreditApplication`, `ApprovalDecision`, `RiskAssessment` are immutable Java records. `ApprovalDecision` has no public constructor usage outside its own static factories (`approved`/`rejected`/`manualReview`) — always construct through those, they set the right zeroed/null fields per outcome consistently. `CoreBankingPort.VaultValidation` is a record nested inside its port interface (infra-shaped type kept out of `domain/`), likewise with its own `accept()`/`reject(reason)` factories.

DTO ↔ domain conversion is manual in `CreditWebMapper` (no MapStruct — deliberate, small field count).

## Known issues / conventions to preserve

- **Español in logs, comments, and JSON field names** (`nombre`, `cedula`, `montoSolicitado`, `RECHAZADO`, log messages) — this is intentional throughout `application`/`domain`/`adapter`, matching the Colombian-bank domain. Keep new code consistent with it rather than switching to English mid-file.
- `pom.xml` has empty `<name/>`, `<description/>`, `<url/>`, `<licenses><license/></licenses>`, `<developers><developer/></developers>` placeholders — not an oversight to silently "clean up", leave unless asked.
- Resilience4j circuit breaker/retry instance names are `aiService` and `vaultService` (see `application.yaml`) — `@CircuitBreaker(name = "...")` / `@Retry(name = "...")` annotations must match these exactly or Resilience4j falls back to defaults silently.
