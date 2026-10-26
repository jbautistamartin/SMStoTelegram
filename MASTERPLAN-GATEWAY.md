# MASTERPLAN — SMSGateway

> **En SMStoTelegram, este documento es historia.** Cuenta el origen de SMSGateway, la primera
> aplicación de la familia, de la que esta desciende por copia. Se conserva porque explica de dónde
> sale la tubería común —el *outbox*, la idempotencia de cliente, el rescate de huérfanos, la
> `HiltWorkerFactory`—, que sigue aquí sin cambios. Lo que ya **no** vale es todo lo que habla del
> envío por `GET` y de la plantilla de URL. Las mudanzas que llevaron hasta esta aplicación están en [`MASTERPLAN-SMS.md`](MASTERPLAN-SMS.md) y
> [`MASTERPLAN-TELEGRAM.md`](MASTERPLAN-TELEGRAM.md).

> **Este documento se escribió a posteriori, el 25 oct 2026.** SMSGateway no nació de un plan
> escrito: la 1.0.0 se hizo en un solo día (8 jul 2026), y los planes empezaron con la primera
> migración, a SMStoSMS. Este fichero existe para que la familia tenga una estructura coherente
> —cada aplicación lleva el plan de su origen y los de cada mudanza de medio— y para dejar escrito
> lo que los demás planes dan por sabido: **por qué la tubería común es como es**.
>
> Está reconstruido a partir de la historia de Git, de `CHANGELOG.md`, de `CLAUDE.md`, de
> `docs/ARCHITECTURE.md` y del propio código. No hay aquí ninguna decisión que no esté en una de
> esas fuentes. Donde no se sabe por qué se hizo algo, se dice.

Aplicación original de la familia: un teléfono Android dedicado, con SIM, que recibe SMS y los
reenvía a una **API REST** por `GET`, con una plantilla de URL configurable.

- **Repositorio:** `github.com/jbautistamartin/SMSGateway`
- **Primera versión:** v1.0.0, 8 jul 2026 · 44 ficheros Kotlin · 2.819 líneas
- **Hoy:** v1.2.0 · 53 ficheros Kotlin · 4.564 líneas
- **Descendientes:** SMStoSMS → SMStoTelegram → SMStoWhats. Las tres son copias de esta, y cada
  una lleva este documento junto a los de sus propias migraciones.

---

## 1. Idea central

El problema difícil no es enviar, es **no perder nada**. Un SMS llega cuando llega, a un proceso
que Android puede matar en cualquier momento, en un teléfono que nadie mira y que puede reiniciarse
o quedarse sin red. Todo el diseño sale de ahí:

```
SIM → SmsReceiver → SmsIngestionService → Room (outbox) → WorkManager → SmsDispatchWorker → GET
```

| Tramo | Por qué es así |
|---|---|
| `SmsReceiver` | El *broadcast* tiene unos diez segundos de presupuesto en el hilo principal. No hace nada más que extraer el SMS y pasarlo |
| `SmsIngestionService` | Servicio en primer plano: es lo que permite terminar el trabajo fuera de ese presupuesto sin que el sistema mate el proceso |
| Room como *outbox* | El SMS se persiste **antes** de intentar enviarlo. Si el proceso muere después, el mensaje sigue ahí |
| WorkManager | Reintentos que sobreviven al proceso y a los reinicios |
| `GET` con plantilla | Ni endpoint fijo ni cuerpo: la API de destino la elige quien opera la app |

Esa tubería es la que heredaron las tres aplicaciones siguientes. Lo que cambió en cada mudanza
fue solo el último tramo.

---

## 2. Decisiones de diseño

Reconstruidas: no se tomaron en una sesión de decisiones, sino que se leen en el código y en los
commits.

| # | Decisión | Elegido | Consecuencia |
|---|----------|---------|--------------|
| 01 | Cuándo persistir | **Antes de enviar** (patrón *outbox*) | Una caída no pierde mensajes. Las cuatro aplicaciones lo conservan sin cambios |
| 02 | Idempotencia | **De cliente**: el *worker* relee la fila y no envía si ya está confirmada | No se da por hecho que el servidor deduplique. El UUID del SMS puede ir en la plantilla si el servidor quiere deduplicar también |
| 03 | Método HTTP | **`GET` con plantilla de URL** | Marcadores `{mensaje}` (obligatorio), `{telefono}` y `{fecha}` (ISO-8601 en UTC), codificados para URL. La autenticación, si hace falta, va en la propia URL |
| 04 | Política de reintentos | **Lineal**, cada ~30 s | Empezó exponencial y pasó a lineal en la 1.0.0 (commit `07d56fe`), para reintentos regulares en lugar de 30 → 60 → 120 → 240 s |
| 05 | Rescate de huérfanos | **`OrphanRescueWorker` cada 30 s**, encadenado | WorkManager no admite trabajo periódico por debajo de 15 min, así que el *worker* se vuelve a programar a sí mismo con `setInitialDelay` |
| 06 | Limpieza | **`HealthMonitorWorker` cada 15 min** | Purga lo enviado y los logs de más de 30 días. El reencolado se le quitó y pasó a `OrphanRescueWorker` |
| 07 | Migraciones de Room | **Explícitas**, sin `fallbackToDestructiveMigration` | Una actualización no puede borrar datos en silencio |
| 08 | Distribución | **APK firmado**, no Google Play | `RECEIVE_SMS` y `READ_SMS` son permisos restringidos. La clave compartida `Claves/capicua.jks` es lo que permite actualizar encima |

---

## 3. Fases

Son los releases, en orden. Cada uno es una fase cerrada.

### Fase 1 — v1.0.0: la tubería · `[x]` 8 jul 2026

Recepción con `SmsReceiver` y servicio en primer plano, *outbox* en Room con WAL, envío con
WorkManager, idempotencia de cliente, rescate de huérfanos, limpieza periódica, arranque tras
reinicio, configuración en caliente (URL, tiempo límite, reintentos, certificados autofirmados) y
registro de auditoría con filtros y exportación. Venía con `test-server/`, un servidor de pruebas
en C# con historial persistente, que SMStoSMS borró en su fase 6.

#### El fallo que casi la deja sin enviar

Los *workers* con `@AssistedInject` no arrancaban: WorkManager se inicializaba solo, por AndroidX
Startup, con su fábrica por defecto, y fallaba con *"Could not create Worker"*. La solución fue
desactivar `WorkManagerInitializer` en el manifiesto para que `GatewayApplication`
(`Configuration.Provider`) entregue la `HiltWorkerFactory`. Las cuatro aplicaciones lo llevan.

#### Lo que se quitó

El indicador *Online/Offline* y `NetworkMonitor`: los *workers* no tienen restricción de red y
reintentan, así que el indicador no decía nada útil sobre si un SMS iba a salir.

### Fase 2 — v1.0.1: firma y red local · `[x]` 9 jul 2026

- HTTP en claro permitido, para servidores locales o corporativos sin TLS.
- Firma de release leída de `local.properties`, sin credenciales en el repositorio.
- APK con la versión en el nombre: `smsgateway-<versión>.apk`.

### Fase 3 — v1.1.0: pestañas, fiabilidad y actualizaciones · `[x]` 11 oct 2026

Llegó **después** de SMStoSMS, y en buena parte trae de vuelta cosas que se habían hecho allí:

- `ViewPager2` sincronizado con la barra inferior en lugar del grafo de navegación, y
  *edge-to-edge*.
- Interruptor general de recepción (`ControlRecepcion`), que desactiva el componente
  `SmsReceiver` en lugar de descartar dentro de `onReceive`.
- Botones de exención de Doze (`OptimizacionBateria`) y de inicio automático del fabricante
  (`InicioAutomatico`).
- Copia de la configuración en JSON sobre un `Uri` de SAF, con `org.json`.
- Aviso de versión nueva contra GitHub Releases, enlace al proyecto, descargo de responsabilidad
  y donativo en «Acerca de».
- `SmsRepositoryTest` estaba escrito contra el modelo en inglés y no compilaba, así que ningún test
  del módulo se ejecutaba. Se reescribió.

### Fase 4 — v1.2.0: de la familia · `[x]` 17 oct 2026

Que SMSGateway pareciera de la misma casa que las otras tres: icono del sistema común (bocadillo
con flecha de subida calada y capa monocroma), paleta violeta propia, el mismo orden de secciones
en «Configuración» y de tarjetas en «Acerca de», aviso de privacidad, filtros del registro en una
sola fila, y copiar una entrada del registro con una pulsación larga.

### Fase 5 — Toolchain al día · `[x]` 25 oct 2026

Paso a AGP 9.4.1, Kotlin 2.4.21, Gradle 9.8.2, `compileSdk 37`, Temurin 25 y las últimas versiones
estables de las dependencias, entre ellas OkHttp 5. Se hizo a la vez en las cuatro aplicaciones;
el detalle está en `CHANGELOG.md`.

---

## 4. Riesgos propios de reenviar SMS a una API

| Riesgo | Mitigación |
|--------|-----------|
| **Doze** pospone WorkManager durante horas con la pantalla apagada | Exención de optimización de batería, obligatoria. Botón en Ajustes y comando `adb` |
| **Inicio automático** del fabricante vacío por defecto (MIUI, HyperOS, EMUI, ColorOS, Funtouch) | No se puede conceder ni consultar desde código: `InicioAutomatico` abre la pantalla |
| **Estado detenido**: una app recién instalada no recibe *broadcasts* hasta que alguien la abre | Abrir la app una vez después de instalar |
| **Duplicados** si la API responde tarde y el *worker* reintenta | Idempotencia de cliente; el UUID del SMS en la plantilla, si el servidor quiere deduplicar |
| **HTTP en claro** expone el contenido de los SMS en la red | Permitido a propósito para redes locales. Se restringe cambiando el manifiesto y `network_security_config.xml` |
| **El fichero de configuración** lleva la plantilla de URL, a veces con una clave de API | La pantalla avisa de que es sensible |

---

## 5. Fuera del plan

- **Reglas.** Todos los SMS salen por la misma URL. Las reglas con expresiones regulares nacieron
  con SMStoSMS y no se han traído aquí.
- **`POST` y cuerpo JSON.** Nunca han existido. No consta por qué se eligió `GET`; lo que sí
  consta es que una plantilla de URL basta para las API de destino que se pensaban usar.
- **Biblioteca de serialización.** La configuración y la respuesta de GitHub se leen con
  `org.json`/`JSONObject`; `kotlinx.serialization` se quitó del catálogo por no usarse.

---

## 6. Git

`main` y `develop`, con ramas `release/vX.Y.Z` en los primeros releases. Etiquetas `v1.0.0`,
`v1.0.1`, `v1.1.0` y `v1.2.0`.
