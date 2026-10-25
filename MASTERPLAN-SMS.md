# MASTERPLAN — SMSGateway → SMStoSMS

> **Este documento es historia, no el plan vigente.** Describe la migración de SMSGateway a
> SMStoSMS, dos aplicaciones anteriores a esta. El plan de SMStoTelegram está en
> [`MASTERPLAN-TELEGRAM.md`](MASTERPLAN-TELEGRAM.md).
>
> Se conserva porque explica por qué la arquitectura es como es —el patrón outbox, la
> idempotencia de cliente, el rescate de huérfanos, el encolado centralizado— y buena parte de
> eso sigue vigente sin un solo cambio. Lo que ya **no** vale es todo lo que habla del envío:
> el transporte por `SmsManager`, las protecciones antibucle y el coste por mensaje.

Plan de migración de la aplicación **SMSGateway** (gateway SMS → API HTTP) a **SMStoSMS**
(reenvío de SMS a otro número de teléfono mediante reglas con expresiones regulares).

- **Rama de trabajo:** `feature/2026_SMStoSMS` (sobre la historia completa de SMSGateway)
- **Base:** v1.0.1 · 44 ficheros Kotlin · 2.819 líneas
- **Decisiones cerradas:** 7 de octubre de 2026
- **Documento visual del plan:** https://claude.ai/code/artifact/22d93cae-7440-4ef0-90cb-17e750c4b821

---

## 1. Idea central

El canal de ingesta de SMS no se toca. Es la parte difícil del problema y ya está resuelta:

```
SIM → SmsReceiver → SmsIngestionService → Room (outbox) → WorkManager → SmsDispatchWorker
```

Lo único que se sustituye es el tramo final del canal:

| | Antes (SMSGateway) | Después (SMStoSMS) |
|---|---|---|
| Destino | URL configurada en Settings | Número de teléfono resuelto por reglas |
| Transporte | OkHttp `GET` | `SmsManager.sendMultipartTextMessage()` |
| Confirmación | Código HTTP 2xx | `sentIntent` con `RESULT_OK` |
| Configuración | Una plantilla de URL | Lista ordenada de reglas con regex |

Se conservan intactos: patrón outbox, idempotencia por UUID, `OrphanRescueWorker` (cada 30 s),
`HealthMonitorWorker` (cada 15 min), `BootReceiver`, Hilt, Room + WAL, DataStore, Material 3,
Navigation con cuatro pestañas y la pantalla de logs con filtros y exportación.

---

## 2. Decisiones cerradas

| # | Decisión | Elegido | Consecuencia |
|---|----------|---------|--------------|
| 01 | Varias reglas casan con el mismo SMS | **Gana la primera, con escape por regla** | Cada regla lleva un flag `continuar`. Obliga a una tabla hija `reenvios`: un SMS, N reenvíos, cada uno con su estado. |
| 02 | Semántica de la expresión regular | **Búsqueda parcial** (`containsMatchIn`) | `codigo` casa con «Tu codigo es 4821». El patrón total sigue disponible con `^…$`. |
| 03 | Texto que llega al destino | **Plantilla por regla** | Marcadores `{mensaje}`, `{telefono}`, `{fecha}`. Por defecto `{mensaje}`. |
| 04a | Selección de SIM (doble SIM) | **Dentro** | `createForSubscriptionId` + permiso `READ_PHONE_STATE`. Fases 3 y 4. |
| 04b | Exportar / importar reglas en JSON | **Dentro** | Fase 4. Reincorpora `kotlinx.serialization` a `libs.versions.toml`, con uso real. |
| 04c | Borrar `test-server/` | **Dentro** | Fase 6. La historia de Git lo conserva. |

### Modelo de datos resultante

```
tabla sms            (sin cambios)
  id, telefono, mensaje, fecha_recepcion

tabla reenvios       ← NUEVA
  id, sms_id, regla_id, destino, texto_final,
  estado, intentos, ultimo_error, fecha_envio

tabla reglas         ← NUEVA
  id, orden, nombre, regex_telefono, regex_mensaje,
  destino, plantilla, activa, continuar

tabla log_entries    (sin codigo_http)
```

Esquema colapsado a la **versión 1**: al cambiar el `applicationId` ningún dispositivo tiene la
base de datos antigua, así que no hay nada que migrar. `room.schemaLocation` se configura (hoy no
lo está, pese a `exportSchema = true`).

---

## 3. Fases

Cada fase termina en un estado compilable y comprobable, con los cambios en el árbol de trabajo.

> **El commit lo pide el usuario, nunca se hace por iniciativa propia.** Al cerrar una fase se
> informa del estado y se para; revisar el diff antes de que entre en la historia es su decisión.

### Fase 1 — Renombrado integral · `[x]` completada el 7 oct 2026

Mecánico y aislado. Va primero para que todo el código posterior se escriba con los nombres
definitivos y el diff funcional quede limpio. Al terminar, la app sigue siendo un gateway HTTP:
solo ha cambiado de nombre.

| Qué | Hoy | Después |
|-----|-----|---------|
| Paquete y `namespace` | `com.capicua.smsgateway` | `com.capicua.smstosms` |
| Clase Application | `GatewayApplication` | `SmsToSmsApplication` |
| Proyecto Gradle | `SmsGateway` | `SMStoSMS` |
| Base de datos | `sms_gateway.db` | `smstosms.db` |
| DataStore | `gateway_config` | `smstosms_config` |
| Canal de notificación | `sms_gateway_channel` | `smstosms_channel` |
| FileProvider authority | `…smsgateway.provider` | `…smstosms.provider` |
| Tema | `Theme.SmsGateway` | `Theme.SmsToSms` |
| APK de release | `smsgateway-1.0.1.apk` | `smstosms-1.0.0.apk` |
| Nombre visible | `SMSGateway` | `SMStoSMS` |

También: cabeceras de licencia de los 44 ficheros, `app_name`, `versionName = "1.0.0"`,
`versionCode = 1`.

**Verificación:** `./gradlew assembleDebug` compila y `grep -ri smsgateway` no devuelve nada
fuera de `.git`.

**No entra:** ningún cambio de lógica, de esquema ni de interfaz.

#### Resultado real

- `assembleDebug` → **BUILD SUCCESSFUL**. Manifest fusionado verificado:
  `package="com.capicua.smstosms.debug"`, `SmsToSmsApplication`, authority
  `com.capicua.smstosms.provider`.
- Los 44 ficheros se movieron con `git mv`, así que Git los registra como *rename* y la historia
  se sigue hasta el commit inicial.

#### Dos desviaciones respecto al plan

1. **Se adelantó de la fase 6 el arreglo de `local.properties`.** No era opcional: el bloque
   `signingConfigs` casteaba `localProps["KEYSTORE_PATH"] as String` en tiempo de configuración,
   de modo que sin claves de firma **ni `assembleDebug` arrancaba**. Ahora `local.properties` se
   lee solo si existe y la firma de release se configura solo si están las cuatro claves. Sin
   ellas, el APK de release sale sin firmar pero el build no rompe.
2. **`./gradlew test` está rojo, y ya lo estaba antes de esta fase.** `SmsRepositoryTest` importa
   `SmsStatus`, que no existe en el proyecto, y `kotlinx.coroutines.test`, que no está declarado
   en `libs.versions.toml`. Verificado contra `git show HEAD`: el fichero llegó así. `CLAUDE.md`
   afirma que «the tests compile as standalone fixtures», lo cual es falso. El borrado de ese
   fichero pasa de la fase 6 al principio de la fase 2, que es donde `./gradlew test` en verde es
   la puerta de salida.

#### Entorno de build verificado

El JDK del PATH es el 25, con el que AGP 8.5.2 no funciona. Para compilar:

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"   # no hay local.properties en esta copia
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug
```

El wrapper es Gradle **9.3.0** con AGP **8.5.2**: compila, pero avisa de features que no existirán
en Gradle 10 (`android.applicationVariants.all` entre ellas). No bloquea nada ahora; conviene
decidir en la fase 6 si se sube AGP o se baja el wrapper.

---

### Fase 2 — Modelo de reglas y motor de coincidencia · `[x]` completada el 7 oct 2026

La pieza nueva del dominio, construida y probada antes de conectarla a nada. El evaluador es
Kotlin puro, sin Android, así que se cubre con tests unitarios de verdad.

- **Primero:** borrar `SmsRepositoryTest`, el placeholder que no compila (heredado de la fase 1),
  y añadir `kotlinx-coroutines-test` a `libs.versions.toml` para los tests nuevos.
- Tablas `reglas` y `reenvios` con sus entidades, DAOs y repositorios.
- `EvaluadorDeReglas`: recorre las reglas activas por `orden`, para en la primera que case salvo
  que lleve `continuar`, y resuelve la plantilla.
- Esquema colapsado a v1 y `room.schemaLocation` configurado.
- ~~El worker pasa a trabajar sobre `reenvioId`~~ → **movido a la fase 3**, ver más abajo.

**Verificación:** `./gradlew test` en verde con casos reales — regex inválida, regla desactivada,
sin coincidencia, orden respetado, `continuar` activo, plantilla con marcadores, reenvío a uno mismo.

**No entra:** nada de interfaz ni de envío; el motor queda inyectable pero sin usar.

#### Resultado real

- `./gradlew test assembleDebug` → **BUILD SUCCESSFUL**. 32 tests en
  `EvaluadorDeReglasTest`, 0 fallos.
- Esquema v1 exportado a `app/schemas/com.capicua.smstosms.data.local.db.SmsDatabase/1.json`
  con las cuatro tablas (`sms`, `reglas`, `reenvios`, `log_entries`) y las dos claves ajenas de
  `reenvios` correctas: `sms_id → sms.id` en CASCADE y `regla_id → reglas.id` en SET NULL.
- El warning de KSP «Schema export directory was not provided» ha desaparecido.

#### Ficheros nuevos

| Fichero | Papel |
|---------|-------|
| `domain/model/Regla.kt` | Regla de reenvío |
| `domain/model/Reenvio.kt`, `EstadoReenvio.kt` | Unidad de despacho y su estado |
| `domain/rules/EvaluadorDeReglas.kt` | Motor: evalúa, resuelve plantilla, valida patrones |
| `domain/rules/ResultadoEvaluacion.kt` | `Coincidencia`, `ReglaInvalida`, `CampoRegla` |
| `data/local/db/entity/ReglaEntity.kt`, `ReenvioEntity.kt` | Tablas |
| `data/local/db/dao/ReglaDao.kt`, `ReenvioDao.kt` | Consultas |
| `data/repository/Regla{Repository,RepositoryImpl}.kt` | Contrato e implementación |
| `data/repository/Reenvio{Repository,RepositoryImpl}.kt` | Contrato e implementación |

#### Tres desviaciones respecto al plan

1. **El worker sigue trabajando sobre `smsId`.** Cambiarlo a `reenvioId` en esta fase habría
   dejado la app justo en el estado que el plan prohíbe: el worker buscando reenvíos que nada
   crea todavía. La migración del worker va con la fase 3, donde toda la ruta de despacho cambia
   de golpe. La fase 2 queda así puramente aditiva: la app sigue funcionando como hoy.
2. **No se añadió `kotlinx-coroutines-test`.** El evaluador es síncrono y ningún test lo
   necesita aún. Añadir una dependencia sin uso contradice la política que el propio
   `CLAUDE.md` fija para `libs.versions.toml`. Entrará cuando haya tests de funciones `suspend`.
3. **La tabla `sms` se deja intacta**, todavía con `enviado`, `intentos` y `ultimo_error`, que
   en el modelo nuevo pertenecen a `reenvios`. Normalizarla arrastra al worker, a los dos
   workers periódicos, al dashboard y al adaptador, así que se hace en la fase 3. El esquema
   sigue en v1 y es editable hasta la 1.0.0.

> **Al actualizar un dispositivo que ya tenga la build de la fase 1:** su base de datos está en
> la versión 2 y pasar a la v1 es un *downgrade*, que Room rechaza. En desarrollo se resuelve
> desinstalando la app o borrando sus datos. No se añade `fallbackToDestructiveMigration`.

---

### Fase 3 — Reenvío real por SmsManager · `[x]` completada el 7 oct 2026

El corazón del cambio. `SmsDispatchWorker` deja de hablar HTTP y pasa a enviar SMS, conservando
intacta su tabla de decisiones.

- **Heredado de la fase 2:** migrar `SmsDispatchWorker` de `smsId` a `reenvioId`, normalizar la
  tabla `sms` (sacar `enviado`, `intentos` y `ultimo_error`, que ahora viven en `reenvios`) y
  adaptar `OrphanRescueWorker` y `HealthMonitorWorker` a la cola de reenvíos.
- `SmsSender`: `divideMessage` + `sendMultipartTextMessage`, un `PendingIntent` por parte y un
  receptor que convierte el broadcast en una corrutina suspendida con timeout.
- Mapeo de resultados:

  | Resultado | WorkManager | Estado |
  |-----------|-------------|--------|
  | `RESULT_OK` (todas las partes) | `success()` | enviado |
  | `RESULT_ERROR_NO_SERVICE`, `RADIO_OFF` | `retry()` | intentos++ |
  | `RESULT_ERROR_GENERIC_FAILURE` | `retry()` | intentos++ |
  | `RESULT_ERROR_NULL_PDU`, destino inválido, permiso denegado | `failure()` | fallo permanente |
  | Timeout esperando `sentIntent` | `retry()` | intentos++ |

- **Protecciones antibucle** (requisito, no extra): nunca reenviar al número de la propia SIM,
  descartar mensajes cuyo remitente sea un destino configurado, y limitar los reenvíos por minuto
  registrando el exceso en el log.
- Doble SIM: `createForSubscriptionId` cuando hay SIM elegida, `getSystemService(SmsManager.class)`
  cuando no. Permiso `READ_PHONE_STATE` para enumerarlas.
- Permiso `SEND_SMS` en el manifiesto y en la petición en tiempo de ejecución de `MainActivity`.
- **Baja:** OkHttp, `NetworkModule`, `HttpClientFactory`, `network_security_config.xml`,
  `usesCleartextTraffic`, permisos `INTERNET` y `ACCESS_NETWORK_STATE`, `TestConexionEstado`.
- **Arreglo:** `intervaloReintentoSegundos` se guarda en configuración y nunca se usa, porque
  `SmsRepositoryImpl.encolarEnvio()` lee la constante `WORKER_INITIAL_BACKOFF_SECONDS`.

**Verificación:** un SMS recibido llega al número destino y el log recoge la cadena completa
(recibido → regla aplicada → enviado). Dos emuladores conectados por sus puertos sirven para
validar sin gasto real.

**No entra:** las reglas se dan de alta a mano en la base de datos.

#### Resultado real

- `./gradlew test assembleDebug` → **BUILD SUCCESSFUL**. 47 tests, 0 fallos
  (32 del evaluador + 15 del normalizador de teléfonos).
- Esquema v1 regenerado y normalizado:
  `sms` conserva solo `id, telefono, mensaje, fecha_recepcion, estado, motivo_descarte`;
  los contadores de intentos y errores viven ya solo en `reenvios`.
  `log_entries` cambia `codigo_http` por `reenvio_id` y `destino`.
- OkHttp fuera por completo: ni en `libs.versions.toml`, ni en el build, ni en el código.

#### Piezas nuevas

| Fichero | Papel |
|---------|-------|
| `data/sms/SmsSender.kt` | Envía por `SmsManager` y **espera** el `sentIntent` |
| `data/sms/ResultadoEnvio.kt` | Enviado / ErrorTransitorio / ErrorPermanente |
| `worker/ColaDeEnvios.kt` | Único punto de encolado; lee el backoff de la configuración |
| `domain/usecase/ProcesarSmsEntranteUseCase.kt` | Protecciones, evaluación, creación de reenvíos y encolado |
| `domain/rules/NormalizadorTelefono.kt` | Compara números con formatos distintos |
| `domain/model/EstadoSms.kt` | PENDIENTE / PROCESADO / SIN_REGLA / DESCARTADO |

#### Decisiones tomadas al implementar

1. **La evaluación ocurre en la ingesta, no en el worker.** `SmsIngestionService` llama a
   `ProcesarSmsEntranteUseCase`, que guarda, protege, evalúa, crea los reenvíos y los encola.
   El worker queda con una sola responsabilidad: enviar uno.
2. **Idempotencia sin transacción entre tablas.** Si el proceso muere entre crear los reenvíos
   y marcar el SMS como PROCESADO, al reprocesarlo se detecta que ya tiene reenvíos y solo se
   reencolan. Evita duplicar envíos —que aquí cuestan dinero— sin necesidad de abrir una
   transacción que cruce DAOs.
3. **El rescate de huérfanos ahora cubre dos tramos:** SMS en PENDIENTE sin evaluar, y
   reenvíos pendientes sin trabajo vivo en WorkManager.
4. **`PendingIntent` por parte, con `FLAG_IMMUTABLE`** y acción con UUID por envío, más
   `RECEIVER_NOT_EXPORTED` al registrar el receptor: ninguna otra app puede falsificar una
   confirmación de envío, y dos reenvíos simultáneos no se cruzan las respuestas.
5. **Si una parte de un mensaje multipart falla, el reenvío se reintenta entero**, aceptando un
   posible duplicado en el destino. Dar por bueno un mensaje truncado es peor.
6. **`{fecha}` pasa a hora local legible** (`toDisplayString()`) en lugar del ISO-8601 UTC que
   usaba la versión HTTP: lo lee una persona en su teléfono, no un servidor.
7. **Arreglado el bug heredado:** `intervaloReintentoSegundos` ya se aplica de verdad, porque
   `ColaDeEnvios` lo lee de la configuración en lugar de usar una constante.

#### Desviaciones

**La protección «nunca reenviar al número de la propia SIM» no se implementó.** En su lugar
quedó «el destino de una regla es el propio remitente», que es la que está en el código y en la
documentación. No es una pérdida: leer el número propio exige `getLine1Number()`, que desde
Android 11 necesita `READ_PHONE_NUMBERS` y que la mayoría de operadores devuelven vacío de todas
formas. Y el caso que preocupaba —configurar tu propio número como destino— lo cubre la
protección 1: el SMS que vuelve tiene como remitente un destino configurado y se descarta. La
diferencia es que se detecta al volver, no al guardar la regla, así que se gasta un SMS antes de
cortarlo.

**La pantalla de configuración se rehízo aquí, no en la fase 4.** Al quitar OkHttp dejaban de
compilar `SettingsViewModel`, `SettingsFragment` y `fragment_settings.xml`, que giraban en torno
a la plantilla de URL y al botón de probar conexión. Ahora tiene protecciones y reintentos. El
selector de SIM y el panel de prueba de reglas siguen en la fase 4.

---

### Fase 4 — Pantalla de reglas · `[x]` completada el 7 oct 2026

La configuración pasa de un formulario con una sola URL a una lista ordenable de reglas. Es la
fase con más trabajo de interfaz.

- Lista con reordenación, interruptor de activación, duplicar y borrar con confirmación.
- Alta y edición con validación de la regex al teclear (`PatternSyntaxException` capturada y
  mostrada en el campo) y validación del número destino.
- **Panel de prueba:** se pega un teléfono y un mensaje de ejemplo y la pantalla dice qué regla
  casa, qué texto saldría y en cuántas partes, sin enviar nada. Botón aparte para envío real.
- Exportar e importar reglas en JSON con `CreateDocument` / `OpenDocument` y
  `kotlinx.serialization`. La importación valida cada regla antes de escribir.
- La configuración general se queda con reintentos, intervalo, retención y protecciones
  antibucle, y gana el selector de SIM cuando hay más de una.

**Verificación:** crear tres reglas, reordenarlas, desactivar la primera y comprobar que un SMS
real toma la ruta esperada. Exportar, borrarlas todas, importar y acabar con las mismas tres en
el mismo orden.

#### Resultado real

- `./gradlew test assembleDebug` → **BUILD SUCCESSFUL**. 55 tests, 0 fallos
  (32 evaluador + 15 normalizador + 8 formato JSON).
- La barra inferior pasa a cinco pestañas: Inicio · **Reglas** · Logs · Config · Acerca de.

#### Pantallas nuevas

| Fichero | Papel |
|---------|-------|
| `presentation/rules/ReglasFragment.kt` | Lista ordenada, activar, duplicar, borrar, exportar e importar |
| `presentation/rules/ReglasAdapter.kt` | Fila con prioridad, criterios, destino e interruptor |
| `presentation/rules/EditarReglaFragment.kt` | Alta y edición, con validación de regex al teclear |
| `presentation/rules/ProbarReglasFragment.kt` | Panel de prueba sin enviar, más envío real opcional |
| `data/rules/ReglasJson.kt` | Formato de intercambio versionado |
| `data/sms/ProveedorDeSims.kt` | Enumera las SIM activas para el selector |

#### Decisiones tomadas al implementar

1. **Reordenar con flechas, no arrastrando.** En una fila que ya lleva un interruptor y un menú,
   el gesto de arrastre competiría con ellos. Las flechas son inequívocas y accesibles. Al mover
   se reescribe el orden de toda la lista en una transacción, en lugar de intercambiar dos
   valores: así nunca hay dos reglas con el mismo `orden`, que haría la evaluación no
   determinista.
2. **Los ids no se exportan.** Un id es local a la base de datos del dispositivo; reutilizarlo
   al importar provocaría colisiones. Importar **añade**, nunca reemplaza, y cada regla entra
   como nueva conservando el orden relativo del fichero.
3. **La importación valida antes de escribir** y cuenta aparte las rechazadas. Una regla con una
   regex rota que entrase en silencio no reenviaría nunca y sería muy difícil de diagnosticar.
4. **Al duplicar, la copia queda desactivada**, para que no empiece a reenviar antes de
   revisarla.
5. **El envío real de prueba manda solo el primer reenvío** y pide confirmación indicando
   destino y número de partes. Una prueba no debe gastar varios SMS sin que quede claro.
6. **El selector de SIM solo aparece si hay más de una.** Con una sola tarjeta no decidiría
   nada y añadiría una pregunta que el operador no tiene que responder.

#### Un fallo que encontró el propio test

`kotlinx.serialization` omite por defecto los campos cuyo valor coincide con el predeterminado,
así que el `version` del fichero exportado **no se escribía** — justo el campo cuya razón de ser
es que una importación futura sepa qué formato está leyendo. Resuelto con `encodeDefaults = true`.

---

### Fase 5 — Inicio y logs al día · `[x]` completada el 7 oct 2026

La información que se muestra cambia: ya no hay códigos HTTP, hay destinos y reglas.

- Cada SMS muestra remitente, destino y regla aplicada, con un estado por reenvío cuando hay varios.
- Nuevos tipos de log: `REGLA_APLICADA`, `SMS_REENVIADO`, `SIN_REGLA`.
- Baja de `codigo_http` en entidad, modelo, adaptador y exportación.
- Un SMS sin regla que case queda marcado como **descartado**, no como pendiente eterno.

**Verificación:** un SMS sin coincidencia y otro con dos destinos se leen correctamente en Inicio,
en Logs y en el fichero exportado.

**No entra:** rediseño visual. Se mantienen las pestañas y el tema actuales.

#### Resultado real

- `./gradlew test assembleDebug` → **BUILD SUCCESSFUL**, 55 tests y 0 fallos.
- Cada fila de Inicio muestra remitente, texto, regla aplicada y destinos, con franja de color
  y chip de estado. Cuando hay varios destinos aparece el desglose «enviados · en curso ·
  fallidos»; cuando algo falla, el error del destino concreto.
- Logs gana tres filtros: Reglas, Sin regla y Bucles.

#### Decisiones tomadas al implementar

1. **Una sola consulta con `@Relation`, no dos flujos combinados.** `SmsConReenviosEntity` deja
   que Room resuelva el SMS y sus reenvíos juntos, con `@Transaction` obligatorio: sin ella una
   escritura concurrente podría emparejar un SMS con los reenvíos que tenía antes.
2. **`ResumenSms` agrega el estado en el dominio, no en el adaptador.** El orden de las
   comprobaciones destaca el problema: un fallo gana a un envío correcto aunque solo afecte a
   uno de los destinos.
3. **Aviso fijo cuando no hay ninguna regla activa.** Sin reglas la aplicación recibe y no
   reenvía, que es exactamente lo que parecería un fallo de envío. Decirlo en la primera
   pantalla evita el diagnóstico equivocado.
4. **El estado se codifica dos veces**, franja de color y chip, porque es el dato que se busca
   al abrir la pantalla.

---

### Fase 6 — Documentación, limpieza y release · `[x]` completada el 7 oct 2026

Los tres documentos del repositorio describen hoy un gateway HTTP: se reescriben, no se parchean.

- `README.md`, `docs/ARCHITECTURE.md` (las secciones «API corporativa» y «Configuración» enteras)
  y `CLAUDE.md`.
- Pantalla «Acerca de» y aviso legal: gana peso cuando el contenido de los SMS sale hacia el
  teléfono de una persona.
- **Deuda heredada que se salda aquí:**
  - ~~`local.properties` leído sin comprobar que exista~~ → **resuelto en la fase 1**.
  - `CLAUDE.md` afirma que `SmsRepositoryTest` compila; no compilaba. Corregir la afirmación
    (el fichero desaparece en la fase 2).
  - `CLAUDE.md` dice «do not re-add kotlinx.serialization» → la fase 4 la reincorpora con uso real.
  - Decidir Gradle 9.3 + AGP 8.5.2: subir AGP o bajar el wrapper antes de Gradle 10.
  - `RETENTION_DELIVERED_DAYS = 30` mientras `CLAUDE.md` dice 7 días.
  - El README afirma «solo HTTPS» mientras el manifiesto permite tráfico en claro.
  - `CLAUDE.md` atribuye el rescate de huérfanos a `HealthMonitorWorker`, cuando lo hace
    `OrphanRescueWorker`.
- Borrado de `test-server/` y de `SmsRepositoryTest` (placeholder con campos en inglés que no
  existen en el modelo).
- Este `MASTERPLAN.md` actualizado con el estado final, y APK firmado `smstosms-1.0.0.apk`.

**Verificación:** `assembleRelease` produce el APK firmado, se instala en limpio y reenvía un SMS
siguiendo solo las instrucciones del README.

**No entra:** publicación en el repositorio remoto.

#### Resultado real

- `./gradlew test assembleDebug assembleRelease` → **BUILD SUCCESSFUL**. 55 tests, 0 fallos.
- `app/build/outputs/apk/release/smstosms-1.0.0.apk`, **2,7 MB** tras R8 (el debug son 9,5 MB).
  `versionName 1.0.0`, `versionCode 1`, `applicationId com.capicua.smstosms`.
- **El APK sale sin firmar**, porque esta copia de trabajo no tiene `local.properties` con el
  almacén de claves. Verificado: no contiene ficheros de firma en `META-INF`. Para publicarlo
  hay que añadir las cuatro claves y volver a compilar.

#### Documentación reescrita entera

| Fichero | Qué se hizo |
|---------|-------------|
| `README.md` | Reescrito. Nueva sección «Primeros pasos» y tabla de reglas con ejemplos. Aviso legal ampliado: los datos personales salen ahora hacia el teléfono de una persona, y los reenvíos tienen coste |
| `docs/ARCHITECTURE.md` | Reescrito. Secciones nuevas: reglas de reenvío, mecanismo de envío, protecciones antibucle. Catorce incidencias documentadas, todas propias del reenvío por SMS |
| `CLAUDE.md` | Reescrito. Añade una sección de **convenciones de nombres**, que el proyecto no tenía escritas y que fue motivo de duda durante la migración |
| Pantalla «Acerca de» | Tarjeta de aviso de privacidad |

#### Deuda saldada

- `test-server/` eliminado: 12 ficheros del servidor .NET que probaba el endpoint HTTP.
- `proguard-rules.pro` reescrito: fuera las reglas de OkHttp, y dentro lo que R8 no puede
  deducir — en particular los `values()`/`valueOf()` de los enum de `domain.model`, que se
  persisten como texto y se reconstruyen por reflexión. Sin eso, R8 podría renombrar sus
  constantes y la lectura de la base de datos fallaría **solo en release**.
- `CLAUDE.md` ya no afirma que `SmsRepositoryTest` compila, ni prohíbe `kotlinx.serialization`,
  ni atribuye el rescate de huérfanos a `HealthMonitorWorker`.
- La discrepancia de retención queda resuelta documentando el valor real: **30 días**, no 7.

#### Gradle 9.3 + AGP 8.5.2: decisión

**No se toca.** Compila y los tests pasan; cambiar el wrapper o subir AGP no aporta nada hoy y
arriesga un build que funciona. Queda documentado en `CLAUDE.md` y en la §5 de la arquitectura:
antes de pasar a Gradle 10 hay que elegir entre subir AGP o fijar el wrapper a Gradle 8.x,
porque `android.applicationVariants.all` —el bloque que renombra el APK— desaparece.

#### Un fallo de codificación que conviene recordar

Al insertar texto en los XML con scripts a través de un heredoc, las barras invertidas dobles se
colapsan en el transporte: un patrón con `\n` nunca coincide con el `
` literal del fichero.
Dos `replace` fallaron en silencio por esto antes de detectarlo. Para editar recursos conviene
usar anclas sin barras invertidas, o herramientas de edición directa.

---

## 7. Estado

Las seis fases de la migración están completas: la aplicación recibe SMS y los reenvía a otro
número —o de vuelta al propio remitente— según reglas con expresiones regulares, con protección
antibucle, confirmación real de envío y registro de auditoría.

**La fase 7 se cierra con la publicación de la v1.0.0** el 8 oct 2026. Fue la fase de pruebas en
dispositivo real: dos rondas, cinco fallos corregidos —tres de ellos bloqueantes y ninguno
detectable desde un build— y una función nueva pedida al usar la aplicación.

Lo que la cierra es que la interfaz entera se ha usado en un teléfono: las cinco pantallas, el
alta y edición de reglas, el panel de prueba y la exención de batería. Lo que **no** cubre, y
queda para una 1.0.1, es el circuito completo contra una red de operador con dos teléfonos.

| Fase | Estado | Commit |
|------|--------|--------|
| 1 · Renombrado integral | Completada | `c695774` |
| 2 · Modelo de reglas y motor | Completada | `653181b` |
| 3 · Reenvío por SmsManager | Completada | `1a14c30` |
| 4 · Pantalla de reglas | Completada | `dee7db6` |
| 5 · Inicio y logs | Completada | `0baf067` |
| 6 · Documentación y release | Completada | `67d567c` |
| **7 · Pruebas en dispositivo y correcciones** | Completada | `v1.0.0` |

---

### Fase 7 — Pruebas en dispositivo real y correcciones · `[~]` abierta el 8 oct 2026

Las seis fases anteriores se cerraron contra `BUILD SUCCESSFUL` y tests unitarios. Esta se cierra
contra un teléfono: cada ronda es instalar, usar la aplicación, anotar lo que falle y corregirlo.
No se planifica por adelantado — el alcance lo marca lo que aparezca.

#### Ronda 1 · 8 oct 2026 — primera instalación

Las seis fases estaban marcadas como completadas, con 55 tests en verde, y aun así cinco cosas no
funcionaban. Las tres primeras eran bloqueantes: la pantalla de reglas aparecía vacía y de dos
pantallas no se podía salir.

| Síntoma | Causa |
|---------|-------|
| La pantalla de reglas aparece vacía | Al `RecyclerView` de `fragment_reglas.xml` le faltaba el `LayoutManager`. Sin él, RecyclerView registra «No layout manager attached; skipping layout» y no pinta nada — **sin fallar**. Las reglas estaban en la base de datos todo el tiempo |
| No se puede salir de «Probar reglas» | Con `targetSdk 35`, Android 15 dibuja de borde a borde sin posibilidad de desactivarlo, y la barra de navegación del sistema quedaba **encima** del Bottom Navigation. Como esa pantalla no tiene pestaña propia, no había salida |
| El notch se superpone a la aplicación | La misma causa: ningún inset aplicado en ninguna parte |
| No hay forma de contestar al remitente | Función que faltaba. El destino solo admitía un número fijo |
| La exención de batería hay que darla por ADB | Función que faltaba. El teléfono dedicado no suele tener un cable delante |
| Los iconos de la barra de estado no se leen | Blancos sobre el fondo casi blanco del tema. Es la otra mitad del borde a borde: al dibujar bajo la barra de estado, el fondo que hay detrás de la hora pasa a ser el de la aplicación, y el sistema por sí solo mantiene los iconos claros |
| El icono del lanzador es el de SMSGateway | El renombrado de la fase 1 cambió el nombre y el `applicationId`, pero no los recursos del icono. Las dos aplicaciones pueden convivir en el mismo teléfono y se confundían |

**Arreglos:** `MainActivity` reparte los insets (`systemBars or displayCutout`); «Probar reglas»
y el editor ganan botón de volver propio, porque depender de la barra inferior para salir de una
pantalla que no está en ella es frágil de todos modos; `{telefono}` pasa a valer como destino,
exento de la protección antibucle por ser una circularidad intencionada; y Ajustes gana la
sección «Fiabilidad en segundo plano». 64 tests, 0 fallos.

**Icono rehecho.** Cambian a la vez el color y la figura, porque uno solo no basta: dos iconos
del mismo azul se siguen confundiendo en la bandeja aunque el dibujo sea distinto. El fondo pasa
al verde azulado de `md_theme_primary` —el color que ya usa la interfaz— y la figura a un
bocadillo con flecha de reenvío, que dice lo que hace la aplicación. La flecha es un hueco con
`fillType="evenOdd"` en la misma ruta, no una figura encima, para no tener que repetir aquí un
color de fondo que además es un degradado. Se añade la capa monocroma que faltaba: sin ella,
Android 13+ deja el icono fuera del tratamiento temático y es el único de la pantalla que no
sigue al resto. Ya en el lanzador se vio que el dibujo quedaba apretado —la punta del bocadillo
a 6 dp del borde recortado—, así que se reduce al 85 % con un `<group>`, en lugar de reescribir
las coordenadas: la ruta sigue siendo legible y comparable con la de la capa monocroma, que
lleva exactamente la misma.

#### Ronda 2 · 8 oct 2026 — opción de ignorar mayúsculas

Pedida al usar la aplicación: acordarse de `(?i)` y escribirlo en las dos expresiones es
exactamente el tipo de detalle que se olvida y que luego parece un fallo de la aplicación.

La regla gana `ignorarMayusculas`, que aplica `RegexOption.IGNORE_CASE` a los dos criterios al
compilar. El patrón guardado no se toca: cambia cómo se compila, no lo que se escribió. Convive
con un `(?i)` puesto a mano, y la diferencia es el alcance — el ajuste cubre las dos expresiones,
el modificador solo la suya.

**Primera migración de esquema del proyecto.** Es el cambio que cierra la puerta que el plan
dejaba abierta: hasta ahora el esquema v1 se podía editar en sitio desinstalando la aplicación,
pero con la app ya instalada en un teléfono y con reglas dentro, eso significaba o perder los
datos del usuario o que Room abortase al abrir. Así que v2 con `Migraciones.DE_1_A_2`, un
`ALTER TABLE ... DEFAULT 0` que conserva el comportamiento de las reglas existentes: nadie se
encuentra con que sus reglas empiezan a casar con más mensajes después de actualizar.

El formato de intercambio sube a la versión 2. Añadir un campo opcional no rompe la importación
de un fichero de la 1, pero sí importa el caso contrario: una versión antigua leyendo un fichero
nuevo descartaría el campo en silencio —`ignoreUnknownKeys` está activado— y la regla entraría
distinguiendo mayúsculas sin avisar. Con la versión por delante, esa importación se rechaza con
un mensaje que lo explica.

73 tests, 0 fallos.

---

#### La lección, que es sobre el plan y no sobre el código

Las fases 3, 4 y 5 **declaraban cada una su «Verificación» en un dispositivo** —crear tres
reglas y reordenarlas, comprobar que un SMS real toma la ruta esperada, exportar e importar— y
ninguna se ejecutó. Los apartados «Resultado real» de las tres informan exclusivamente de
`./gradlew test assembleDebug`. El plan marcó como completado lo que estaba compilado.

Los dos fallos bloqueantes comparten la propiedad que los hacía invisibles desde un build: **no
lanzan ninguna excepción**. Un `RecyclerView` sin `LayoutManager` escribe una línea en logcat y
sigue; los insets sin aplicar son, literalmente, no hacer nada. Ningún test unitario sobre Kotlin
puro los habría visto, y no porque falten tests, sino porque no son esa clase de fallo. Marcar
una fase de interfaz como completada exige haber mirado la pantalla.

---

### Lo que queda pendiente, por orden de utilidad

1. **Seguir la fase 7**, que la ronda 1 solo empezó. Queda el circuito completo contra una red
   de operador: SIM → regla → `SmsManager` → teléfono destino, con dos teléfonos y las reglas
   reordenadas, más exportar/importar de ida y vuelta.
2. ~~**Firmar el APK de release.**~~ Hecho el 8 oct 2026: `local.properties` y el almacén se
   heredaron de SMSGateway, copiados al proyecto como `smstosms-release.jks`. Es la **misma
   clave** firmando dos `applicationId` distintos, lo que es válido e implica que cualquier
   actualización futura de SMStoSMS tendrá que usar ese almacén. Comprobado con `apksigner`:
   esquema v2, `CN=José Luis Bautista Martín, O=Capicua`.
3. ~~**Etiquetar `v1.0.0`** y publicar.~~ Hecho el 8 oct 2026: etiqueta `v1.0.0` y release en
   GitHub con el APK firmado.
4. **Fase 8, si interesa:** destino extraído de un grupo de captura de la regex, reglas con
   ventana horaria, y tests instrumentados de Room y del worker.

> El punto «decidir la convención de nombres de los casos de uso» desaparece de esta lista:
> `GetSmsListUseCase` ya se renombró a `ObtenerListaSmsUseCase` en la fase 6, y la convención
> está escrita en `CLAUDE.md`. La lista lo arrastraba sin motivo.

---

### Fase 8 — Opcional · `[ ]`

Nada de esto bloquea una 1.0 utilizable: destino extraído de un grupo de captura de la propia
regex, reglas con ventana horaria, y tests instrumentados de Room y del worker.

---

## 4. Riesgos propios de reenviar SMS

| Riesgo | Mitigación |
|--------|-----------|
| **Bucles de reenvío.** Si un destino contesta, o si se configura el propio número, el mensaje vuelve a entrar, casa otra vez y se reenvía — con reintentos automáticos detrás, eso es una factura. | Las tres protecciones de la fase 3. |
| **Reglas que contestan al remitente** (`{telefono}` como destino, fase 7). Quedan exentas de la protección 2 a propósito, así que si el otro extremo también responde solo, los dos aparatos se contestan. | Solo el límite por minuto, que los para pero después de gastar hasta diez SMS. La mitigación real es de configuración: acotar la regla con un `regexMensaje` concreto. El panel de prueba lo avisa en pantalla. |
| **El envío es asíncrono.** `SmsManager` retorna al instante; el resultado llega después por broadcast. Sin esperarlo, todo parece enviado y los fallos se pierden en silencio. | Puente suspendido sobre `sentIntent` con timeout. |
| **Multipart multiplica el coste.** Un mensaje largo se parte y cada parte confirma por separado. Si una falla y otra no, el reenvío queda incompleto. | Se considera fallido y se reintenta entero, aceptando posibles duplicados en el destino. |
| **`SEND_SMS` es un permiso restringido.** | Irrelevante para distribución por APK; cierra la puerta a Google Play sin justificación aprobada. |
| **Datos personales en tránsito.** El contenido de los SMS pasa a enviarse al teléfono de una persona. | Aviso legal explícito en README y «Acerca de». |
| **Remitentes alfanuméricos** («BANCO», «AMAZON») en lugar de números. | La regex trabaja sobre la cadena cruda de la PDU, sin normalizar. Documentado y visible en el panel de prueba. |

---

## 5. Fuera del plan, a propósito

- **No se toca el canal de ingesta.** `SmsReceiver`, `SmsIngestionService`, `OrphanRescueWorker`,
  `BootReceiver` y el patrón outbox funcionan y están documentados.
- **No se migra a Compose ni se rediseña.** Sigue en Fragments con View Binding y Material 3.
- **No se conserva el modo HTTP.** Nada de una app que reenvíe por SMS y además llame a una API:
  duplicaría configuración, modelo de datos y pruebas. SMSGateway sigue existiendo en su propia
  historia de Git.

---

## 6. Git

La historia completa de SMSGateway se conserva. `develop` y `main` quedan intactas y no hay
remoto configurado todavía. Cada fase da lugar a un commit en `feature/2026_SMStoSMS` **cuando el
usuario lo pide**, y al cerrar la fase 6 a una etiqueta `v1.0.0` lista para empujar al repositorio
nuevo.
