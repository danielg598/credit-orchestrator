# credit-orchestrator

> **Orquestador central de Lumen**. Recibe solicitudes del frontend,
> coordina la evaluación con el microservicio de IA, valida contra el
> core bancario (Vault) y devuelve la decisión final con pricing
> ajustado por riesgo.

Puerto: **8080** · Java 21 · Spring Boot 3.5.13

---

## Responsabilidades

Este es el **único** punto de contacto del frontend con el backend.
Todas las llamadas del dashboard pasan por aquí.

Sus tareas:

1. Validar el request entrante (JSR-303 Bean Validation)
2. Delegar el cálculo de riesgo al servicio de IA (`credit-ai-service`)
3. Validar las reglas de producto contra el Smart Contract en Vault
4. Si aprueba, crear la cuenta en Vault con idempotencia
5. Calcular pricing dinámico (tasa ajustada por PD, cuota PMT)
6. Devolver al frontend un `CreditResponse` completo

**No** tiene lógica de ML, no tiene lógica de contabilidad bancaria, no
persiste datos propios. Es puro `glue code` transaccional — y por eso
vale la pena que esté en Java/Spring, que es donde ese tipo de código
brilla.

---

## Arquitectura hexagonal (ports & adapters)

El proyecto sigue el patrón **Hexagonal** de Alistair Cockburn. La lógica
de negocio (`domain` + `application`) **no conoce** Spring, HTTP, ni
Python — solo conoce interfaces (`ports`).

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│   adapter/in/web              adapter/out/ai                │
│   ┌──────────────┐            ┌──────────────┐              │
│   │CreditController           │MockRiskScoring│              │
│   │(REST @8080)  │            │Adapter       │              │
│   └──────┬───────┘            └──────┬───────┘              │
│          │                           │                       │
│          │ implementa                │ implementa            │
│          ▼                           ▼                       │
│   ┌──────────────┐            ┌──────────────┐              │
│   │ApproveCredit │──usa──▶    │RiskScoring   │              │
│   │UseCase (port │           │Port (salida) │              │
│   │de entrada)   │           └──────────────┘              │
│   └──────┬───────┘                                          │
│          │                    adapter/out/ai                 │
│          │ implementado       ┌──────────────┐              │
│          ▼ por               │AiRiskScoring │              │
│   ┌──────────────┐            │Adapter       │              │
│   │ApproveCredit │            │(HTTP→Python) │              │
│   │Service       │            └──────────────┘              │
│   │              │                                           │
│   │(CORE del     │            adapter/out/vault              │
│   │ negocio)     │            ┌──────────────┐              │
│   └──────┬───────┘            │VaultCore     │              │
│          │                    │Adapter       │              │
│          └────────usa─────▶   │(HTTP→FastAPI)│              │
│                               └──────────────┘              │
│                                                             │
└─────────────────────────────────────────────────────────────┘
        DOMAIN + APPLICATION          ADAPTERS (IO)
        (puro Java, sin frameworks)   (Spring, RestClient, etc.)
```

### Beneficio concreto

El día en que Thought Machine nos dé acceso al Vault Core real y al
modelo productivo del banco:

- El `domain` no cambia ni una línea.
- `ApproveCreditService` no cambia.
- `RiskScoringPort` no cambia.
- Solo cambia `VaultCoreAdapter` (apuntando al dominio real) y podemos
  escribir un `AiRiskScoringAdapter` nuevo (o reutilizar el actual con
  otra URL).

Eso es lo que hace **defendible** esta arquitectura ante un arquitecto
senior de TM: *"tu código de negocio está aislado del código de
integración"*.

---

## Estructura del proyecto

```
credit-orchestrator/
├── src/main/java/co/lumen/credit/credit_orchestrator/
│   ├── CreditOrchestratorApplication.java       Entry point
│   │
│   ├── domain/                                  NÚCLEO DE NEGOCIO
│   │   ├── model/
│   │   │   ├── CreditApplication.java           Record: solicitud
│   │   │   ├── ApprovalDecision.java            Record: decisión final
│   │   │   ├── RiskAssessment.java              Record: respuesta IA
│   │   │   ├── HistorialCrediticio.java         Enum
│   │   │   └── PropositoPrestamo.java           Enum
│   │   └── exception/
│   │       └── InvalidCreditApplicationException
│   │
│   ├── application/                             ORQUESTACIÓN
│   │   ├── port/
│   │   │   ├── in/
│   │   │   │   └── ApproveCreditUseCase.java    Interface (lo que el
│   │   │   │                                    mundo exterior puede
│   │   │   │                                    pedirnos)
│   │   │   └── out/
│   │   │       ├── RiskScoringPort.java         Interface (IA)
│   │   │       └── CoreBankingPort.java         Interface (Vault)
│   │   └── service/
│   │       └── ApproveCreditService.java        Implementación del UC
│   │
│   ├── adapter/                                 INPUTS / OUTPUTS
│   │   ├── in/web/
│   │   │   ├── CreditController.java            @RestController
│   │   │   ├── CreditRequestDto.java            JSON entrada
│   │   │   ├── CreditResponseDto.java           JSON salida
│   │   │   └── CreditWebMapper.java             DTO ↔ domain
│   │   └── out/
│   │       ├── ai/
│   │       │   ├── MockRiskScoringAdapter.java  Modo dev
│   │       │   └── AiRiskScoringAdapter.java    Modo real (FastAPI)
│   │       └── vault/
│   │           └── VaultCoreAdapter.java        Cliente Vault
│   │
│   └── config/
│       ├── RestClientConfig.java                Apache HttpClient 5
│       ├── WebCorsConfig.java                   CORS para Angular
│       └── GlobalExceptionHandler.java          @RestControllerAdvice
│
├── src/main/resources/
│   └── application.yml                          Config centralizada
│
├── pom.xml
└── README.md                                    (este archivo)
```

---

## Stack técnico

| Componente | Versión | Rol |
|---|---|---|
| Spring Boot | 3.5.13 | Framework base |
| Java | 21 | Records, pattern matching, virtual threads |
| Spring Web | 6.2 | REST API |
| RestClient | 6.2 | HTTP cliente (reemplaza RestTemplate/WebClient para cases sync) |
| Apache HttpClient | 5.3 | Backend de RestClient (ver *Bug conocido* abajo) |
| Resilience4j | 2.2 | CircuitBreaker + Retry |
| Jackson | 2.17 | Serialización JSON |
| Bean Validation | 3.0 | @NotNull, @Min, @Max en DTOs |
| Lombok | 1.18 | (opcional, se puede retirar) |

---

## Setup desde cero

```bash
cd credit-orchestrator

# Compilar y correr
mvn clean install
mvn spring-boot:run
```

O desde IntelliJ: abre el proyecto → botón play verde sobre
`CreditOrchestratorApplication`.

### Variables de entorno

Se configuran en IntelliJ (Run Configuration → Environment variables)
o directamente en `application.yml` para entornos de deploy.

```ini
# Modo del adaptador de IA (mock|real)
APP_AI_MODE=real

# URL del microservicio Python
APP_AI_BASE_URL=http://localhost:8000

# URL del Vault (mock local o Vault real con VPN)
APP_VAULT_BASE_URL=http://localhost:9000

# Token de auth — en Vault real viene del gestor de secretos
APP_VAULT_AUTH_TOKEN=mock-dev-token

# ID del producto Vault a invocar en Smart Contracts
APP_VAULT_PRODUCT_ID=personal_loan_ai
```

El modo **mock** está activo por default para desarrollo offline. Se
cambia a **real** con una sola variable cuando el credit-ai-service
está corriendo.

---

## Los 4 componentes clave del dominio

### 1. `CreditApplication` — la solicitud

Record inmutable. Es lo que el UseCase recibe.

```java
public record CreditApplication(
    String nombre,
    String cedula,
    int edad,
    BigDecimal ingresosMensuales,
    BigDecimal montoSolicitado,
    int plazoMeses,
    HistorialCrediticio historialCrediticio,
    PropositoPrestamo propositoPrestamo
) {
    public CreditApplication {
        // Invariantes de construcción
        if (edad < 18 || edad > 75) throw new InvalidCreditApplicationException(...);
        ...
    }
}
```

**Por qué un record y no una clase**: inmutabilidad por construcción,
equals/hashCode/toString gratis, syntax concisa. Java 21.

### 2. `ApproveCreditService` — el corazón

Implementa `ApproveCreditUseCase`. No sabe nada de HTTP, nada de Python,
nada de Vault. Solo habla con sus dos puertos de salida.

```java
@Service
public class ApproveCreditService implements ApproveCreditUseCase {

    private final RiskScoringPort riskScoring;
    private final CoreBankingPort coreBanking;

    public ApprovalDecision approve(CreditApplication application) {
        // 1. Delegar cálculo de riesgo
        var risk = riskScoring.assess(application);

        // 2. Validar contra smart contract de Vault
        var validation = coreBanking.validateWithSmartContract(
            application, risk.score()
        );

        if (!validation.accepted()) {
            return ApprovalDecision.rechazado(..., validation.reason());
        }

        // 3. Calcular pricing
        var tasaEA    = calcularTasaDinamica(risk.probabilidadDefault());
        var cuota     = calcularCuotaPMT(application.montoSolicitado(), tasaEA, application.plazoMeses());
        var accountId = coreBanking.createLoanAccount(application, tasaEA);

        // 4. Ensamblar decisión
        return ApprovalDecision.aprobado(..., risk, tasaEA, cuota, accountId);
    }
}
```

### 3. `AiRiskScoringAdapter` — el cliente de la IA

Implementa `RiskScoringPort`. Traduce el domain model a JSON, llama al
FastAPI con RestClient, y mapea la respuesta de vuelta.

```java
@Component
@ConditionalOnProperty(name = "app.ai.mode", havingValue = "real")
public class AiRiskScoringAdapter implements RiskScoringPort {

    @Retry(name = "aiService", fallbackMethod = "fallback")
    @CircuitBreaker(name = "aiService", fallbackMethod = "fallback")
    public RiskAssessment assess(CreditApplication app) {
        var payload = toAiPayload(app);               // domain → dict

        var response = restClient.post()
            .uri(baseUrl + "/predict")
            .contentType(APPLICATION_JSON)
            .body(payload)
            .retrieve()
            .body(AiResponse.class);

        return toDomain(response);                    // dict → domain
    }

    private RiskAssessment fallback(CreditApplication app, Exception ex) {
        log.warn("IA real no disponible, usando scoring conservador. Causa: {}", ex.getMessage());
        return RiskAssessment.conservador(...);       // rechaza por precaución
    }
}
```

**`@ConditionalOnProperty`** es la pieza que hace la magia del
dev-offline: si `app.ai.mode=mock`, este bean no se instancia y Spring
usa el `MockRiskScoringAdapter` en su lugar. **Un solo flag** cambia
todo el comportamiento.

**CircuitBreaker + Retry + fallback**: si el FastAPI cae o se lagea,
Resilience4j corta el circuito después de N fallas, y el método
`fallback()` devuelve un `RiskAssessment` conservador (rechaza por
defecto) en vez de dejar al cliente esperando.

### 4. `VaultCoreAdapter` — el cliente del core

Mismo patrón que el anterior pero contra el puerto 9000 (Vault Mock)
o la URL productiva (Vault real con VPN).

Hace **2 llamadas** por cada aprobación:

1. `POST /v1/smart-contracts/validate` — valida reglas (score mínimo,
   edad, monto máximo) contra el `pre_posting_code` del Smart Contract
   Python.
2. `POST /v1/accounts` — si validation.accepted=true, crea la cuenta.

---

## Pricing dinámico

El orquestador no sabe cómo scorear, pero sí sabe cómo **pricear**
— porque eso depende del perfil de riesgo que calcula la IA.

```java
private BigDecimal calcularTasaDinamica(double pd) {
    // Tasa base del producto: 15% EA
    // Prima de riesgo: hasta 30 pp. adicionales según PD
    BigDecimal tasaBase  = BigDecimal.valueOf(0.15);
    BigDecimal primaRiesgo = BigDecimal.valueOf(pd * 0.30);
    return tasaBase.add(primaRiesgo);
}

private BigDecimal calcularCuotaPMT(BigDecimal principal,
                                     BigDecimal tasaEA,
                                     int plazoMeses) {
    // Fórmula PMT: P · i / (1 - (1+i)^-n)
    BigDecimal tasaMensual = tasaEA.divide(BigDecimal.valueOf(12), MathContext.DECIMAL64);
    ...
}
```

Tasa final: entre 15% (perfil impecable) y 30% EA (riesgo cercano al
umbral). Esto se documenta en el contrato de producto que la SFC
auditará.

---

## CORS — por qué habilitado en el orquestador y no solo en el mock

El Angular le pega directo al Spring Boot (`localhost:8080`) y habría
error CORS sin el `WebCorsConfig`.

```java
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins("http://localhost:4200", "http://localhost:3000")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true)
            .maxAge(3600);
    }
}
```

En producción los `allowedOrigins` serán el dominio real del dashboard
(ej: `https://ops.lumen.co`).

---

## Bug conocido: RestClient + JDK Factory no serializa POST body

### Síntoma
Al usar el `JdkClientHttpRequestFactory` por defecto (que es el que
Spring ofrece out-of-the-box para RestClient), los requests `POST` con
body JSON **llegan al servidor sin el body** — el Content-Length queda
en 0 y FastAPI/Vault devuelven 422.

### Causa
Incompatibilidad en la versión de Spring 6.2 + JDK HttpClient 21
manejando streaming de bodies pequeños con `ObjectMapper.writeValueAsBytes`.

### Solución
Usar Apache HttpClient 5 como factory:

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.apache.httpcomponents.client5</groupId>
    <artifactId>httpclient5</artifactId>
</dependency>
```

```java
// RestClientConfig.java
@Bean
public ClientHttpRequestFactory clientHttpRequestFactory() {
    return new HttpComponentsClientHttpRequestFactory();
}

@Bean
public RestClient restClient(ClientHttpRequestFactory factory) {
    return RestClient.builder()
        .requestFactory(factory)
        .build();
}
```

Esto resuelve el bug inmediatamente. El request body viaja correctamente.

**No es bug nuestro**: reportado en Spring Issues. Workaround oficial
hasta que Spring 6.3 lo corrija.

---

## Endpoints expuestos

### `POST /api/v1/credit/evaluate`

**Request**:

```json
{
  "nombre": "María Pérez",
  "cedula": "1023456789",
  "edad": 32,
  "ingresosMensuales": 4500000,
  "montoSolicitado": 20000000,
  "plazoMeses": 36,
  "historialCrediticio": "BUENO",
  "propositoPrestamo": "VEHICULO"
}
```

**Response** (200 OK):

```json
{
  "decisionId": "dec_7a3c9f2b-...",
  "decision": "APROBADO",
  "score": 755,
  "probabilidadDefault": 0.244,
  "montoAprobado": 20000000,
  "tasaInteresEA": 0.2233,
  "cuotaMensual": 766254,
  "plazoMeses": 36,
  "vaultAccountId": "LOAN-1023456789-1776650001047",
  "factoresClave": [...],
  "explicacion": "La decisión de aprobar...",
  "latenciaMs": 1061,
  "modeloVersion": "logreg-german-v1.0",
  "timestamp": "2026-04-19T20:36:00Z"
}
```

Decisiones posibles: `APROBADO` | `RECHAZADO` | `REVISION_MANUAL`.

### `GET /actuator/health`

Endpoint de Spring Actuator. Usado por Kubernetes readiness/liveness
probes.

---

## Validación de entrada

El `CreditRequestDto` usa Bean Validation:

```java
public record CreditRequestDto(
    @NotBlank(message = "El nombre es obligatorio")
    @Size(min = 2, max = 100)
    String nombre,

    @Pattern(regexp = "\\d{6,10}", message = "Cédula debe ser 6-10 dígitos")
    String cedula,

    @Min(18) @Max(75)
    int edad,

    @DecimalMin("1000000")
    BigDecimal ingresosMensuales,

    @DecimalMin("500000") @DecimalMax("100000000")
    BigDecimal montoSolicitado,

    @Min(6) @Max(120)
    int plazoMeses,

    @NotNull
    HistorialCrediticio historialCrediticio,

    @NotNull
    PropositoPrestamo propositoPrestamo
) {}
```

Ante error 400, `GlobalExceptionHandler` devuelve un cuerpo estructurado:

```json
{
  "timestamp": "2026-04-19T20:36:00Z",
  "status": 400,
  "error": "VALIDATION_ERROR",
  "messages": [
    "cedula: Cédula debe ser 6-10 dígitos"
  ]
}
```

---

## Observabilidad

Logs en cada paso del flujo:

```
[INFO] CreditController:      POST /api/v1/credit/evaluate cedula=1023456789
[INFO] ApproveCreditService:  Procesando solicitud: cedula=..., monto=..., plazo=...
[INFO] AiRiskScoringAdapter:  [AI-REAL] Llamando FastAPI /predict
[DEBUG] ApproveCreditService: IA respondió: score=755, pd=0.244
[INFO] VaultCoreAdapter:      [VAULT] Validando smart contract productId=personal_loan_ai
[DEBUG] ApproveCreditService: Vault validation: accepted=true
[INFO] VaultCoreAdapter:      [VAULT] Creando cuenta para cedula=1023456789
[INFO] ApproveCreditService:  Decisión: APROBADO, score=755, vaultAccount=LOAN-...
```

En producción: Micrometer → Prometheus → Grafana para métricas de
latencia, tasa de aprobación, tasa de error por adapter.

---

## Resiliencia con Resilience4j

Configuración en `application.yml`:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      aiService:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
      vaultCore:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 15s
  retry:
    instances:
      aiService:
        max-attempts: 3
        wait-duration: 1s
```

**Semántica de fallback**:

| Servicio caído | Qué hace Lumen |
|---|---|
| `credit-ai-service` | Fallback conservador → RECHAZA por defecto. No se arriesga a aprobar sin evaluación. |
| `vault-mock` / Vault real | Fallback → `REVISION_MANUAL`. Un humano decide. |
| Groq (dentro del AI service) | Fallback local en Python. El Spring Boot no se entera. |

---

## Testing

```bash
mvn test                    # unit tests
mvn verify                  # integration tests con Testcontainers
```

Coverage objetivo: **>75% en `application/service`** (core de negocio).
Los adapters se prueban con mocks/contract tests.

---

## Decisiones y trade-offs

| Decisión | Pros | Contras | Cuándo reconsiderar |
|---|---|---|---|
| **Arquitectura hexagonal** | Aislamiento del dominio, fácil de testear | Más archivos/clases | Si el proyecto nunca cambiará de integración |
| **Records para el dominio** | Inmutabilidad, brevedad, Java 21-idiomatic | Jackson requiere Jackson 2.12+ | Si target bajara a Java 11 |
| **RestClient sync sobre WebClient async** | Simpler mental model para el orquestador | No soporta backpressure | Si el endpoint se vuelve >100 req/s sostenido |
| **Resilience4j en vez de Hystrix** | Hystrix en maintenance mode | Curva de aprendizaje | Nunca — Hystrix está deprecated |
| **Mock Vault + flag para switchear** | Desarrollo offline instantáneo | 1 configuración extra | Cuando llegue VPN productiva |

---

## Próximos pasos

- [ ] Migrar a WebClient async cuando lleguemos a >100 req/s
- [ ] Persistir decisiones en Postgres (audit trail 7 años por regulación SFC)
- [ ] Publicar eventos `CreditApproved` en Kafka (consumir desde Vault real)
- [ ] Distributed tracing con OpenTelemetry + Jaeger
- [ ] Circuit breaker metrics expuestas en `/actuator/metrics`
- [ ] GraphQL endpoint opcional para consultas complejas del dashboard
- [ ] Feature flags con Unleash para A/B testing de umbrales

---

## Referencia rápida

| Necesito... | Archivo |
|---|---|
| Agregar un campo al request | `CreditRequestDto` + `CreditApplication` + `CreditWebMapper` |
| Cambiar la fórmula de pricing | `ApproveCreditService.calcularTasaDinamica` |
| Cambiar el payload al FastAPI | `AiRiskScoringAdapter.toAiPayload` |
| Cambiar las reglas de validación | Anotaciones Bean Validation en `CreditRequestDto` |
| Agregar un tercer adapter (ej: SMS) | Crear `SmsNotificationPort` + Adapter + inyectar en Service |
| Cambiar timeout del RestClient | `RestClientConfig` |
