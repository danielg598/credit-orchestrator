# Handoff — DGAL_ACCELERATOR: de PoC a motor de decisión gobernado por IA

> Documento vivo. Se actualiza cada vez que se completa un paso — no se reescribe, se agrega a la **Bitácora de avance** al final. Vive en `credit-orchestrator` porque el orquestador es el punto donde convergen todas las piezas del cambio, pero varias etapas tocan `credit-ai-service`, `vault-mock` y (nuevo) un mini-servicio de centrales de riesgo.

---

## 1. Resumen del documento fuente

`documento/DGAL_ACCELERATOR - motor inteligente de decisiones financieras.docx` (Daniel Gonzalo Alzate Restrepo, abril 2026) es el dossier del acelerador para el Accelerathon de Vault Core / Thought Machine.

**Qué es hoy (Fase 1, completada):** un PoC funcional de extremo a extremo — panel Angular (`credit-dashboard`) → orquestador Spring Boot hexagonal (`credit-orchestrator`) → motor de IA Python con scoring + SHAP + LLM (`credit-ai-service`) → simulador de Vault Core con Smart Contract en Python (`vault-mock`). Decisión crediticia completa en ~1s, con explicabilidad en español, resiliencia (Circuit Breaker + fallbacks) y trazas distribuidas con OpenTelemetry.

**Lo que el documento declara explícitamente fuera de alcance del PoC** (relevante porque es justo lo que ahora hay que atacar):
- No conecta a burós de crédito locales (DataCrédito / TransUnion) — queda para Fase 2.
- No persiste decisiones para auditoría de 7 años — PostgreSQL queda para Fase 2.
- El modelo de riesgo está entrenado con datos alemanes de los 90 (Statlog German Credit), no colombianos.
- Las reglas de aprobación/rechazo son **estáticas**: un umbral de PD fijo en Java (`ApproveCreditService`, `PD_UMBRAL_RECHAZO = 0.5`) y un Smart Contract con reglas duras iguales para todos (score mínimo 500, edad 18-70, monto máx. COP 100M, edad+plazo ≤ 75 años).

**La visión que marca el rumbo (Fase 4 del roadmap, "Motor de decisión autónomo y gobernado"):** que la decisión deje de resolverse con un umbral fijo y reglas iguales para todos, y la asuma el modelo a partir del riesgo real de cada caso — con las reglas actuando como **capa de gobierno que la IA respeta**, no como sustituto de su criterio. Con una condición no negociable, textual del documento:

> *"La decisión cuantitativa la toma un modelo calibrado, determinista y auditable — no un modelo de lenguaje generando texto libre —, mientras que el LLM permanece en la capa de explicación, anclado a los factores reales calculados por el modelo (SHAP), sin inventar cifras ni razones."*

Esto es lo que le da forma técnica al pedido que te hicieron: **no es "dejar que el LLM decida"**, es mover la decisión de un `if (pd >= 0.5)` codificado en Java a un motor de decisión calibrado, con zona de incertidumbre que deriva a revisión humana, y alimentado con datos reales del solicitante (buró) — conservando trazabilidad y sin reglas "iguales para todos".

---

## 2. Qué se pidió avanzar (este handoff)

Dos frentes, explícitamente:

1. **Las reglas de aprobación/rechazo hoy son estáticas → deben pasar a decidirlas la IA.**
2. **Consumir APIs de centrales de riesgo** para conocer el perfil real del solicitante (moras, endeudamiento, reportes negativos, consultas recientes) y poder decir *no solo* que se rechaza, sino **por qué**.

Y un tercer eje que agregó un mentor: **este motor no es específico de crédito** — el patrón (Smart Contract como guardrail + modelo calibrado + explicabilidad + resiliencia + observabilidad) es genérico y aplica a otras decisiones del core bancario. Se aborda en la sección 5 y en la Etapa 7.

---

## 3. Estrategia para tiempo limitado (léase antes de tocar código)

**Contexto real:** esto es para presentar a un jurado, no para producción. Sin presupuesto de nube. El criterio de éxito no es "quedó listo para el banco", es **"quedó demostrable en vivo y defendible técnicamente en 10 minutos de preguntas"**.

**Se corta explícitamente, sin culpa, de todo lo que menciona el documento como Fase 2/3 de productización:**
- Nada de AWS/Azure/Kubernetes. Todo sigue local, como ya está.
- Nada de Amazon Bedrock — Groq se queda, es más rápido de demostrar y ya funciona.
- Nada de PostgreSQL real — si hace falta un "puerto de auditoría" para la narrativa, se deja como interfaz con una implementación mínima (log), no una base de datos nueva que instalar y poblar.
- Nada de reentrenar el modelo con datos colombianos — no hay datos, y no es lo que pidió el experto (pidió cambiar *cómo se decide*, no *con qué datos se entrenó el modelo*).
- DataCrédito/TransUnion reales — **mock**, exactamente con el mismo patrón que ya usaste para Vault Core: simulador con el mismo contrato, swap por variable de entorno para la narrativa de "listo para producción sin reescribir código".

**Por dónde empezar, en orden, y por qué:**

| # | Repo | Por qué primero/después |
|---|---|---|
| 1 | `credit-ai-service` | Es Python puro, se prueba con `curl`/Swagger sin depender de los otros 3 servicios corriendo. Ahí vive el corazón técnico del pitch: la política de decisión calibrada (Etapa 1 y 3). Iteras más rápido que en Java. |
| 2 | Nuevo mini-servicio `central-riesgo-mock` | Se **clona la estructura de `vault-mock`** (ya tiene el patrón exacto: FastAPI + auth por header + endpoint mock) y se recorta a un solo endpoint. No es un patrón nuevo que diseñar, es copiar uno que ya funciona — bajo riesgo, rápido. |
| 3 | `vault-mock` | Un solo archivo a tocar en serio: `contracts/personal_loan_ai.py`. Bajo esfuerzo, pero es el cambio con más impacto narrativo frente a un jurado de Thought Machine — demuestra que entendiste el rol de Vault (gobierno, no decisión). |
| 4 | `credit-orchestrator` | El que más "pega" tiene que hacer (nuevo puerto + adapter + combinar guardrail vs. IA + reason codes). Se deja para cuando los contratos de los otros 3 ya estén definidos, para no refactorizar dos veces. |
| 5 | `credit-dashboard` | Último 10% de esfuerzo, pero es lo que el jurado **ve**. Un tag visual por origen del factor (Modelo/Buró/Política) es barato y es justo lo que se percibe como "wow" en un demo corto. |

**Regla de oro con el tiempo que tengas:** si hay que cortar algo del plan de abajo, cortá primero la Etapa 6 (observabilidad/auditoría) y dejala solo narrada en el pitch — no la demuestres en vivo si te quita horas. Lo que **no** se puede cortar sin perder el argumento central son las Etapas 1, 3 y 4 (son literalmente "la IA decide, el contrato gobierna").

---

## 4. Principios que no se deben romper al construir esto

Vienen directo del documento (sección "Garantías de confiabilidad") y son el criterio de aceptación real de cada etapa:

- **La decisión cuantitativa nunca la genera el LLM.** El LLM solo narra en español los factores que el modelo/reglas ya calcularon. Si un reason code no viene de SHAP, del buró o de un guardrail, el LLM no puede inventarlo.
- **Zona de incertidumbre → revisión humana**, no una decisión forzada. Si la confianza es baja, el resultado es `REVISION_MANUAL`, igual que hoy pasa con el circuit breaker.
- **Las reglas no desaparecen, cambian de rol.** Edad legal, monto tope del producto, etc. son límites de producto/regulatorios — se quedan como guardrails duros. Lo que se vuelve adaptativo es el **corte de riesgo** (hoy `PD_UMBRAL_RECHAZO = 0.5` fijo para todos).
- **Todo sigue siendo auditable:** cada decisión debe poder reconstruirse (versión de modelo, versión de política, datos de buró consultados, guardrails evaluados).
- **Resiliencia igual o mejor que hoy:** si el buró cae, el sistema no debe bloquearse — degradar a decisión solo con score interno + guardrails, marcando el caso como "evaluado sin buró".

---

## 5. Lo que dijo el mentor: este motor no es solo para crédito

El patrón que ya está construido —**Smart Contract como guardrail + modelo calibrado que decide + SHAP/LLM que explica + Circuit Breaker que da resiliencia + OpenTelemetry que traza**— no tiene nada específico de "aprobar un préstamo". Es un motor de decisión genérico para cualquier punto del core bancario donde hoy hay una regla fija y en realidad debería haber juicio contextual explicable. Usos concretos dentro de lo que ya cubre Vault Core:

| Caso de uso | Qué reemplaza | Por qué encaja con lo ya construido |
|---|---|---|
| **Detección de fraude transaccional** | Reglas fijas de "monto > X → bloquear" | Vault Core ya expone **pre/post-posting hooks** — el mismo mecanismo que hoy usa `pre_posting_code` para crédito, aplicado a `postings` en vez de a la apertura de cuenta. `vault-mock` **ya tiene** `postings` en su store y `instruct_posting_batch` en `MockVault` — la plomería ya existe. |
| **Ajuste dinámico de cupo en tarjetas/rotativos** | Cupo fijo asignado una vez | Mismo motor de riesgo (score + buró), pero evaluado periódicamente sobre comportamiento, no solo en el origination. Es exactamente lo que el documento ya proyecta en Fase 3 ("pricing dinámico avanzado por segmento", "expansión a tarjeta de crédito"). |
| **Estrategia de cobranza inteligente** | "días de mora → acción X" fijo para todos | El mismo score + buró decide la acción (recordatorio, refinanciación, castigo) en vez de una tabla de reglas por rango de días. |
| **Onboarding / KYC con fricción variable** | Mismo nivel de verificación para todos | El motor decide cuánta fricción de verificación pedir según señales de riesgo, no un checklist fijo. |

**Recomendación concreta para la demo, dado el tiempo:** no construyas las cuatro. La prueba de "esto es genérico" más barata y más convincente para un jurado de Thought Machine es la primera — **fraude transaccional** — porque:
1. `vault-mock` ya modela `postings`, así que no hay que inventar un dominio de datos nuevo.
2. El motor de contratos (`contract_loader.py`) **ya es multi-producto por diseño** — agregar un segundo `contracts/<product_id>.py` no toca nada de `app/`. Es la frase textual del propio `vault-mock`: *"Adding a new product means adding a new `contracts/<product_id>.py` file — no changes needed anywhere in `app/`"*.
3. Reutilizás el 90% del código: mismo patrón de score + explicación + guardrail, solo cambia el `product_id` y el contrato.

Ver Etapa 7 para el alcance mínimo de esta prueba — es la última prioridad, solo si sobra tiempo después de las Etapas 1-5.

---

## 6. Plan por etapas

Cada etapa tiene objetivo, alcance de código, prioridad y criterio de "hecho". Prioridad: **P0** = no se puede cortar, es el argumento central del pitch. **P1** = fuerte pero recortable si el tiempo aprieta. **P2** = solo si sobra tiempo.

### Etapa 1 — Consolidar la autoridad de decisión (quick win) · P0
**Objetivo:** una sola fuente de verdad para "aprobado/rechazado/revisión", y dejar de duplicar el umbral en Java.

- [ ] `credit-ai-service`: confirmar que `/predict` devuelve `decision` + `reason_codes` (si no existen reason codes estructurados todavía, agregarlos ahí — ver Etapa 5, pero como campo vacío/placeholder por ahora).
- [ ] `AiRiskScoringAdapter.toDomain()`: leer y propagar `decision` en vez de descartarlo.
- [ ] `RiskAssessment` (domain record): agregar campo `Decision sugerida` (o similar) que venga del motor de IA.
- [ ] `ApproveCreditService.approve()`: eliminar el `if (assessment.probabilityDefault() >= PD_UMBRAL_RECHAZO)` hardcodeado — la decisión de riesgo la trae `assessment` desde el motor de IA. Java conserva la orquestación (Vault, pricing) pero deja de recalcular el corte de riesgo.
- [ ] Actualizar `README-credit-orchestrator.md` / `CLAUDE.md` para reflejar que el umbral de riesgo vive en `credit-ai-service`, no en el orquestador.

**Hecho cuando:** un cambio de `DEFAULT_THRESHOLD` en `credit-ai-service/.env` cambia el comportamiento de aprobación sin tocar una línea de Java.

---

### Etapa 2 — Central de riesgo: mini-servicio mock + puerto de integración · P0
**Objetivo:** que el sistema conozca el perfil real del solicitante (moras, endeudamiento, reportes negativos) antes de decidir — mismo patrón que ya funcionó con `vault-mock`.

Acceso real a DataCrédito/TransUnion requiere convenio comercial (así lo dice el propio documento) — no es razonable ni necesario para esta demo. El camino correcto y rápido es el mismo que se usó para Vault: **simulador local con el mismo contrato HTTP que tendría el buró real**, clonando la estructura ya probada de `vault-mock`.

- [ ] Nuevo mini-servicio `central-riesgo-mock` (FastAPI, **clonado de `vault-mock`**: mismo `auth.py`, mismo patrón de `main.py`, recortado a lo esencial): expone `GET /v1/buro/{cedula}` devolviendo:
  - Score del buró (independiente del score interno del modelo).
  - Historial de mora: días de mora vigente, mora en últimos 12/24 meses.
  - Endeudamiento total vigente en el sistema financiero.
  - Número de consultas recientes.
  - Reportes negativos activos (embargos, procesos jurídicos).
  - Metadata: fecha de corte, fuente (`DATACREDITO_MOCK`/`TRANSUNION_MOCK`).
  - **Para que la demo sea reproducible ante el jurado:** datos determinísticos por cédula (ej. un diccionario fijo con 3-4 perfiles: limpio, con mora, sobreendeudado, sin historial) — nada de aleatoriedad en vivo.
- [ ] `application/port/out/CreditBureauPort.java` (nuevo puerto): `BureauReport consultarHistorial(String cedula)`.
- [ ] `adapter/out/bureau/CreditBureauAdapter.java`: mismo patrón que `VaultCoreAdapter` — `RestClient` propio (Apache HttpClient5, igual que los otros dos, para no reintroducir el bug de body vacío), `@CircuitBreaker` + fallback.
  - **Fallback si el buró cae:** no bloquear. Continuar con score interno + guardrails, marcar `bureauAvailable=false`.
- [ ] `RestClientConfig`: tercer `RestClient` bean (`bureauRestClient`), variables `APP_BUREAU_MODE` (`mock|real`), `APP_BUREAU_BASE_URL`, `APP_BUREAU_AUTH_TOKEN` — mismo esquema que `APP_VAULT_*`.
- [ ] `ApproveCreditService`: llamar al buró antes de invocar el motor de IA, enviar el `BureauReport` como parte del payload a `credit-ai-service`.
- [ ] `credit-ai-service`: extender `app/schemas/predict.py` para aceptar los campos del buró como features opcionales.

**Hecho cuando:** una solicitud con mora activa en el mock de buró se rechaza aunque el score interno del modelo sea alto, y la razón lo referencia explícitamente.

---

### Etapa 3 — De regla fija a motor de decisión calibrado · P0
**Objetivo:** reemplazar el corte único (`DEFAULT_THRESHOLD` plano) por una decisión que pondera score interno + señales de buró + incertidumbre, sin que el LLM participe en el cálculo.

**Alcance mínimo dado el tiempo:** no hace falta calibración estadística formal (Platt/isotonic) para la demo — alcanza con una **banda de dos umbrales** (ej. PD < 0.3 → aprobar, PD > 0.6 → rechazar, entre medio → `REVISION_MANUAL`) más las reglas de buró. Es defendible y es justo el mecanismo que el documento describe ("umbrales de confianza con abstención").

- [ ] `credit-ai-service`: separar "cálculo de riesgo" (ya existe: `model_service.py`) de "política de decisión" (nuevo: `app/services/decision_service.py`).
- [ ] Banda de incertidumbre en vez de corte único (ver alcance mínimo arriba).
- [ ] Incorporar señales del buró a la política: mora vigente > 90 días → rechazo directo (regla de riesgo dura, defendible ante regulador) independiente del score; endeudamiento alto o muchas consultas recientes ajustan el umbral en vez de rechazar por sí solos.
- [ ] Documentar la política de decisión igual que se documenta el Smart Contract hoy: parametrizable, versionada (`decision-policy-v1`), sin recompilar para ajustar umbrales.
- [ ] `RiskAssessment`: agregar `modeloDecisionVersion` (separado de `modeloVersion` del scoring) para auditar qué política decidió, no solo qué modelo scoreó.

**Nota de alcance:** no se persigue re-entrenar el modelo con datos colombianos (fuera de alcance, sección 3). Esta etapa es sobre la **política que traduce probabilidad + contexto en decisión** — que es exactamente lo que señaló el experto.

**Hecho cuando:** dos solicitudes con el mismo score de modelo pero distinto perfil de buró terminan en decisiones distintas, y la razón queda trazada.

---

### Etapa 4 — El Smart Contract pasa de "gate" a "guardrail" · P0
**Objetivo:** que Vault deje de decidir con un pass/fail binario y pase a expresar límites de producto/legales que la decisión de IA debe respetar. Este es el punto que más va a resonar frente a un jurado que conoce Vault Core — es la prueba de que entendiste su rol real.

- [ ] Distinguir en `contracts/personal_loan_ai.py` (vault-mock) qué reglas son **elegibilidad legal/producto** (edad 18-70, monto máx. COP 100M — no negociables, no dependen del modelo) de qué reglas son **apetito de riesgo** (score mínimo 500 — esto ahora lo decide el motor de la Etapa 3, ya no el contrato).
- [ ] `pre_posting_code` deja de rechazar por score; sigue rechazando por elegibilidad (edad, monto, regla edad+plazo). El score mínimo se retira del contrato o se deja como red de seguridad muy laxa (ej. score < 100 → rechazo automático pase lo que pase).
- [ ] `CoreBankingPort.validateLoan`: se reinterpreta como "guardrail check" — corre en paralelo/antes de la decisión de riesgo, y su resultado se combina en `ApproveCreditService` (guardrail rechaza → rechazo inmediato sin importar la IA; guardrail aprueba → prevalece la decisión de la IA).
- [ ] `ApprovalDecision.razonRechazo`: distinguir el origen: `POLITICA_PRODUCTO` (guardrail/Vault) vs `RIESGO_MODELO` (IA) vs `BURO_CREDITO` — esto es directamente lo que pide "saber por qué se rechaza".

**Hecho cuando:** cambiar el score mínimo de riesgo ya no requiere tocar el Smart Contract — se ajusta en la política de decisión de `credit-ai-service` sin redeploy de Vault. Esto se puede *demostrar en vivo* frente al jurado: cambiar un valor en `.env`, reiniciar solo `credit-ai-service`, mostrar que la decisión cambia sin tocar Vault ni Java.

---

### Etapa 5 — Razones de rechazo multi-fuente + explicación LLM anclada · P1
**Objetivo:** cerrar el ciclo de explicabilidad — el LLM narra, no decide, y las razones combinan las tres fuentes (modelo/SHAP, buró, guardrails de producto). Es lo que más se **ve** en el demo, alta prioridad aunque no sea P0 estructural.

- [ ] `RiskAssessment.FactorClave`: agregar campo `origen` (`MODELO_SHAP` | `BURO_CREDITO` | `POLITICA_PRODUCTO`) a cada factor.
- [ ] `groq_explainer.py`: el prompt recibe explícitamente los factores de buró y de guardrails (no solo SHAP), con instrucción explícita de no razonar sobre nada fuera de la lista recibida.
- [ ] Fallback local (sin Groq) también narra factores de buró y guardrails, no solo SHAP.
- [ ] `credit-dashboard`: mostrar el origen de cada factor visualmente (tag "Central de riesgo" / "Modelo" / "Política").

**Hecho cuando:** un rechazo por mora en el buró muestra una explicación que la menciona explícitamente, generada por el LLM a partir de un factor real (`origen=BURO_CREDITO`), no una explicación genérica de score.

---

### Etapa 6 — Observabilidad, auditoría y pruebas de resiliencia del nuevo flujo · P2 (recortable)
**Objetivo:** que el motor ampliado siga siendo tan observable y resiliente como el actual.

- [ ] Extender las trazas OpenTelemetry: nuevo span para la consulta al buró.
- [ ] Nuevo escenario de resiliencia: caída del buró → decisión continúa con guardrails + modelo interno, marcada `bureauAvailable=false`.
- [ ] `DecisionAuditPort`: interfaz con implementación mínima (log estructurado), sin Postgres — deja el hueco listo para después.
- [ ] Actualizar la matriz de "Escenarios validados" del documento con los nuevos casos.

**Si el tiempo aprieta:** dejar esta etapa solo como diapositiva de roadmap ("el puerto ya está diseñado, la implementación con Postgres es el siguiente paso") en vez de construirla. No compromete el argumento central.

---

### Etapa 7 (opcional/estirado) — Prueba de que el motor es genérico · P2
**Objetivo:** responder en código, no solo en discurso, al comentario del mentor. Solo si las Etapas 1-5 ya están cerradas y sobra tiempo antes de la presentación.

**Alcance mínimo recomendado — fraude transaccional** (ver justificación en sección 5):
- [ ] `vault-mock`: nuevo `contracts/fraud_guard.py` con un `pre_posting_code` simple (ej. bloquear posting si excede un múltiplo del promedio histórico de la cuenta, o si es una lista de patrones sospechosos) — reutiliza el mismo `contract_loader.py`, sin tocar `app/`.
- [ ] Un endpoint mínimo (puede ser en `credit-orchestrator` o incluso un script de demo standalone) que dispare una transacción de prueba contra ese contrato y muestre score + explicación, igual que el flujo de crédito.
- [ ] **No hace falta** dashboard nuevo para esto — con mostrarlo por Swagger UI / `curl` en vivo alcanza para probar el punto ante el jurado: "el mismo motor, otro `product_id`, cero cambios en la plomería".

**Hecho cuando:** podés decir y mostrar en la misma demo: "esto que ven no es un motor de crédito, es un motor de decisión — miren, el mismo mecanismo aplicado a fraude, sin tocar el orquestador ni el motor de contratos".

---

## 7. Guion sugerido para la demo ante el jurado

Pensado para que la narrativa siga el mismo orden en que se construyó — cada paso resuelve la objeción que dejó el anterior:

1. **Mostrar el PoC base funcionando** (aprobación, rechazo, revisión manual) — esto ya está, es el punto de partida, no hay que reconstruirlo.
2. **Cambiar `DEFAULT_THRESHOLD` en `credit-ai-service` en vivo** y mostrar que la decisión cambia sin tocar Java ni Vault → prueba que la IA decide, no un `if` fijo en el orquestador (Etapas 1 y 3).
3. **Correr una cédula con mora en el mock de buró** → mostrar que se rechaza aunque el score sea bueno, y que la explicación en español lo menciona explícitamente (Etapas 2 y 5).
4. **Mostrar el Smart Contract de Vault** y explicar que ya no decide el riesgo, solo guarda los límites legales del producto — la frase clave: *"Vault gobierna, la IA decide"* (Etapa 4).
5. *(Si llegaste a la Etapa 7)* **Mostrar el mismo motor aplicado a un segundo caso de uso** (fraude) para responder directamente al comentario del mentor sobre multitarea.
6. Cerrar con el mapeo regulatorio que ya trae el documento (Circular 018, CONPES 4144, AI Act) — ya está escrito, solo hay que reforzar que la explicabilidad multi-fuente lo cumple mejor que el PoC original.

---

## 8. Fuera de alcance de este plan (a propósito)

- Re-entrenar el modelo con datos colombianos reales (requiere datos del banco, no disponibles).
- Conexión a DataCrédito/TransUnion **reales** (requiere convenio comercial — se deja el adapter listo para swap, igual que Vault).
- Persistencia definitiva en PostgreSQL con retención de 7 años (se deja el puerto listo, no la implementación completa).
- Migración de Groq a Amazon Bedrock, despliegue en AWS/Azure/Kubernetes (sin presupuesto ni necesidad para el demo).
- Multi-producto completo (tarjeta, microcrédito, hipotecario) más allá de la prueba puntual de la Etapa 7.

---

## 9. Bitácora de avance

> Agregar una entrada corta cada vez que se cierre algo. Formato: fecha, etapa, qué se hizo, qué queda pendiente/decidido.

- **2026-08-07** — Documento leído y plan de etapas creado. Sin código tocado todavía.
- **2026-08-07** — Plan actualizado con estrategia de tiempo limitado (sección 3), priorización P0/P1/P2, respuesta al comentario del mentor sobre generalización (sección 5, Etapa 7), y guion de demo (sección 7). Próximo paso: Etapa 1 en `credit-ai-service`.
