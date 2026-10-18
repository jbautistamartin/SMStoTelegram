# SMStoTelegram

[![Última versión](https://img.shields.io/github/v/release/jbautistamartin/SMStoTelegram?label=descargar&color=2E7D32)](https://github.com/jbautistamartin/SMStoTelegram/releases/latest)
[![Licencia](https://img.shields.io/badge/licencia-LGPL--2.1-blue)](LICENSE)
[![Donar con PayPal](https://img.shields.io/badge/Donar-PayPal-00457C?logo=paypal&logoColor=white)](https://paypal.me/jbautistamartin)

Aplicación Android que convierte un dispositivo con SIM en un **puente de SMS a Telegram**:
recibe los mensajes que llegan a su número y los entrega automáticamente en Telegram —a una
persona, un grupo o un canal— a través de un bot propio, según reglas con expresiones regulares
sobre el remitente y el texto.

> **Esta aplicación nace de [SMStoSMS](https://github.com/jbautistamartin/SMStoSMS)**, que hace
> lo mismo con la entrada y reenvía como SMS a otro teléfono. Son aplicaciones **distintas**, con
> `applicationId` propio y base de datos propia, y pueden convivir en el mismo dispositivo.

**Versión actual: [v1.2.0](https://github.com/jbautistamartin/SMStoTelegram/releases/tag/v1.2.0)** —
descarga el APK firmado desde la
[página de releases](https://github.com/jbautistamartin/SMStoTelegram/releases). No está en
Google Play: `RECEIVE_SMS` pertenece a un grupo de permisos restringido cuya publicación exige
una declaración aprobada. Lo que cambia en cada versión está en [`CHANGELOG.md`](CHANGELOG.md).

---

## Para qué sirve

El caso típico: un número al que llegan avisos —códigos de verificación, notificaciones de un
proveedor, alertas de un sistema de monitorización— y una o varias personas que necesitan
recibirlos donde ya están mirando, que es Telegram.

Las reglas permiten separar ese tráfico: los SMS del banco a una persona, los de la alarma a un
grupo del equipo, y el resto a ninguna parte.

Frente a reenviar por SMS, entregar en Telegram cambia tres cosas de fondo:

- **No cuesta nada** y no hay forma de que se convierta en una factura por una regla mal puesta.
- **Caben 4.096 caracteres** por mensaje en lugar de 160, así que no hay mensajes troceados.
- **Un grupo o un canal** puede ser el destino, de modo que el aviso llega a varias personas sin
  repetir el envío.

Y añade una que hay que tener presente: **el contenido de los SMS sale a internet** y pasa por
los servidores de Telegram. Está explicado en el [aviso legal](#aviso-legal) y dentro de la propia
aplicación.

---

## Características principales

- **Reglas ordenadas** — expresiones regulares sobre el remitente y el mensaje; gana la primera
  que casa, y una regla puede dejar que la evaluación continúe para entregar en varios chats
- **Plantilla por regla** — el texto que sale se compone con `{mensaje}`, `{telefono}` y
  `{fecha}`. Conviene dejar los tres: en Telegram todo llega del mismo bot, y sin el remitente no
  se sabe de quién era cada SMS
- **El destino se elige de una lista** — un botón pregunta al bot quién le ha escrito y ofrece los
  chats, porque nadie se sabe su `chat_id`. Si la lista viene vacía, la app explica que un bot no
  puede escribir primero y que falta un `/start` del otro lado
- **El token se comprueba antes de usarlo** — un botón pregunta a Telegram y responde con el
  `@usuario` del bot, en lugar de descubrir el fallo cuando llegue el primer SMS
- **Panel de prueba** — se pega un SMS de ejemplo y la app dice qué regla casa, a qué chat iría y
  con qué texto, sin entregar nada
- **Sin pérdida de mensajes** — patrón outbox: el SMS se persiste en Room antes de hacer nada más
- **Confirmación real de entrega** — se espera la respuesta del Bot API; una entrega solo se da
  por buena cuando Telegram la confirma
- **Mayúsculas opcionales** — cada regla decide si sus expresiones distinguen mayúsculas
- **Aviso cuando no se puede entregar** — sin token, o con un token rechazado, el Panel lo dice.
  Es el único fallo que afecta a todas las entregas por igual
- **Reintentos automáticos** — WorkManager con backoff configurable, rescate cada 30 segundos de
  lo que se quede atascado, y respeto del `retry_after` que pide Telegram ante un `429`
- **Arranque automático** — los workers se reactivan tras reiniciar el dispositivo, y Ajustes
  lleva al permiso de inicio automático del fabricante, que es lo que lo impide en Xiaomi,
  Huawei, Oppo y Vivo
- **Interruptor de recepción** — un ajuste apaga la lectura de SMS sin desinstalar ni
  desconfigurar nada; lo que ya estuviera en cola se termina de entregar
- **Registro de auditoría** — cada paso queda anotado, con filtros y exportación a fichero
- **Copia de seguridad** — ajustes y reglas en un solo fichero JSON, desde la pestaña
  Configuración. **El token del bot no viaja en él**: es una credencial
- **Aviso de versión nueva** — comprueba una vez al día si hay un release más reciente, lo marca
  con un punto en la pestaña **Acerca de** y desde ahí se descarga e instala; se puede apagar

---

## Arquitectura

```
Presentation  →  Domain  →  Data
   MVVM          Reglas      Room · Bot API · DataStore · WorkManager
```

Clean Architecture con tres capas estrictas, inyección de dependencias con **Hilt** y
reactividad completa mediante **Kotlin Flow**.

El recorrido de cada SMS:

```
SIM → SmsReceiver → SmsIngestionService → Room (outbox)
                                             ↓
                                       límite por minuto
                                             ↓
                                      EvaluadorDeReglas
                                             ↓
                                   reenvíos (uno por chat)
                                             ↓
                                       DispatchWorker
                                             ↓
                            TelegramSender → sendMessage → chat
```

**El único punto de la cadena que sabe de Telegram es `TelegramSender`.** Todo lo que hay antes
—recibir, persistir, evaluar reglas, encolar, reintentar, rescatar lo atascado— es agnóstico del
medio, y es lo que permitió pasar de SMS a Telegram sin tocar la tubería: `TransportModule` es la
única clase que nombra una implementación concreta del transporte.

Consulta [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) para la documentación técnica completa,
[`MASTERPLAN_TELEGRAM.md`](MASTERPLAN_TELEGRAM.md) para la historia del cambio de medio desde
SMStoSMS y [`MASTERPLAN.md`](MASTERPLAN.md) para la anterior, desde SMSGateway.

---

## Stack tecnológico

| Categoría | Tecnología |
|-----------|-----------|
| Lenguaje | Kotlin 2.0.21 |
| SDK mínimo | Android 8.0 (API 26) |
| SDK objetivo | Android 15 (API 35) |
| Inyección de dependencias | Hilt 2.52 |
| Base de datos local | Room 2.6.1 + WAL mode |
| Tareas en background | WorkManager 2.9.1 |
| Entrega de mensajes | Bot API de Telegram vía `HttpURLConnection` |
| Configuración | DataStore Preferences 1.1.1 |
| Serialización | kotlinx.serialization 1.7.3 |
| Concurrencia | Coroutines + Flow 1.9.0 |
| UI | Material Design 3 + Navigation Component |

La red se usa con `HttpURLConnection` y `kotlinx.serialization`, sin Retrofit ni OkHttp: toda
la comunicación con Telegram son dos peticiones —`sendMessage` y `getMe`— y una dependencia nueva
para eso no se paga.

---

## Requisitos

- Android Studio Ladybug (2024.2.1) o superior
- **JDK 17 o 21** — AGP 8.5.2 no admite JDK 25
- Android SDK con API 35
- Dispositivo Android 8.0+ con SIM activa y conexión a internet
- Un **bot de Telegram**, que se crea gratis escribiendo a
  [@BotFather](https://t.me/BotFather) y da un token para pegar en Ajustes

---

## Instalar la versión publicada

La vía corta si no vas a tocar el código:

1. Descarga el APK de la
   [última release](https://github.com/jbautistamartin/SMStoTelegram/releases/latest).
2. Pásalo al teléfono e instálalo, aceptando «instalar de orígenes desconocidos» cuando lo pida.
3. **Abre la aplicación una vez** y concede los permisos que pide. Abrirla no es opcional: una
   aplicación recién instalada que nadie ha abierto todavía no recibe **ningún** broadcast de
   Android, los SMS incluidos. Es la causa número uno de «la he instalado y no recibe nada».
4. **Crea el bot**: escribe a [@BotFather](https://t.me/BotFather) en Telegram, manda `/newbot`,
   sigue los dos pasos que pide y copia el token que te dé.
5. **Ajustes → Bot de Telegram** → pega el token y pulsa **Comprobar el token**. Debe responder
   con el nombre de tu bot.
6. **Abre el bot en Telegram desde la cuenta que vaya a recibir los avisos y mándale `/start`.**
   Un bot no puede escribir primero: sin ese paso no hay chat al que entregar nada.
7. **Ajustes → Fiabilidad en segundo plano → Desactivar ahorro de batería.** Sin esto el modo
   Doze puede retrasar las entregas horas con la pantalla apagada.

> El APK está firmado con la clave de Capicua. Las futuras actualizaciones se firman con la
> misma: un APK de otra procedencia no se instalará encima, habrá que desinstalar primero y se
> perderán las reglas. Haz antes una copia desde **Config → Copia de seguridad → Exportar**.

---

## Compilar e instalar desde el código

### 1. Compilar

```bash
# Si el JDK del PATH es más nuevo que el 21
export JAVA_HOME="/ruta/al/jdk-21"

# Compilación debug
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk

# Compilación release (requiere claves de firma en local.properties; ver CLAUDE.md)
./gradlew assembleRelease
# APK → app/build/outputs/apk/release/smstotelegram-<versionName>.apk
```

Sin las claves de firma el build de release **no falla**: produce un APK sin firmar, que habrá
que firmar aparte antes de instalarlo.

### 2. Instalar mediante ADB

> Requiere [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools)
> y el dispositivo en modo depuración USB (**Ajustes → Opciones de desarrollador → Depuración USB**).

```bash
# Instalar APK debug
adb install app/build/outputs/apk/debug/app-debug.apk

# Reemplazar una versión ya instalada conservando sus datos
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Verificar que quedó instalada
adb shell pm list packages | grep capicua
```

Alternativamente, `./gradlew installDebug` compila e instala en un solo paso.

### 3. Conceder permisos y configurar el dispositivo

```bash
adb shell pm grant com.capicua.smstotelegram android.permission.RECEIVE_SMS
adb shell pm grant com.capicua.smstotelegram android.permission.READ_SMS
adb shell pm grant com.capicua.smstotelegram android.permission.POST_NOTIFICATIONS

# Excluir de la optimización de batería (obligatorio para una entrega fiable)
adb shell dumpsys deviceidle whitelist +com.capicua.smstotelegram
```

Sin la exención de batería, el modo Doze de Android puede retrasar las entregas horas cuando la
pantalla está apagada.

Si no tienes el cable a mano, la propia app lo resuelve: **Ajustes** → **Fiabilidad en segundo
plano** dice si la exención está concedida y el botón abre la pantalla del sistema que la
concede. Los pasos manuales por fabricante están en [`CLAUDE.md`](CLAUDE.md).

---

## Primeros pasos en la app

0. **Antes de nada**, configura el bot: **Ajustes → Bot de Telegram**, pega el token de
   @BotFather y pulsa **Comprobar el token**. Y que quien vaya a recibir los avisos le mande
   `/start` al bot desde su Telegram. Sin esos dos pasos no hay nada que hacer en las reglas.
1. **Reglas** → **+** para crear la primera regla.
2. Rellena el nombre y al menos un criterio. Para el destino, pulsa **Elegir de los chats del
   bot** y selecciona de la lista. Deja los dos criterios vacíos solo si quieres entregar *todos*
   los SMS.
3. **Probar** → pega un SMS de ejemplo y comprueba que la regla casa y que el texto sale como
   esperas, antes de que llegue uno real.
4. Opcionalmente, **Enviar de prueba** entrega un mensaje real en el primer chat para confirmar
   el circuito completo.

Mientras no haya ninguna regla activa, la pantalla de Inicio lo advierte: los SMS se reciben y
se marcan como «sin regla», pero no se entrega nada. Y si falta el token, o Telegram lo ha
rechazado, el Panel lo dice en rojo por encima de todo lo demás.

### Las reglas, en detalle

Una regla casa cuando se cumplen **sus dos criterios**. Un criterio vacío se considera cumplido.

| Campo | Qué hace |
|-------|----------|
| Expresión del remitente | Se aplica al número tal como llega, sin normalizar. Puede ser alfanumérico: `BANCO`, `AMAZON` |
| Expresión del mensaje | Se aplica al texto completo del SMS |
| Chat de destino | Dónde se entrega: un `chat_id` —negativo si es grupo o canal— o un `@usuario` de canal público. No admite marcadores |
| Plantilla | Qué texto se envía, con `{mensaje}`, `{telefono}` y `{fecha}` |
| Ignorar mayúsculas | Aplica las dos expresiones sin distinguir mayúsculas de minúsculas |
| Activa | Desactívala para que no se evalúe, sin borrarla |
| Seguir evaluando | Tras casar, continúa con las reglas siguientes |

La búsqueda es **parcial**: el patrón `codigo` casa con «Tu codigo es 4821». Para exigir el texto
completo, ánclalo con `^…$`.

Las expresiones **distinguen mayúsculas** salvo que actives **Ignorar mayúsculas** en la regla,
que las aplica sin distinguir a las dos a la vez. También puedes escribir `(?i)` dentro de una
expresión concreta si solo quieres que afecte a esa; activar las dos cosas no da problemas.

Ejemplos:

```
# Solo los SMS del banco, al chat privado de una persona
Remitente: ^BANCO$          Mensaje: (vacío)       → 123456789

# Cualquier mensaje con un código de verificación, al mismo chat
Remitente: (vacío)          Mensaje: (?i)codigo    → 123456789

# Todo lo que llegue de un número concreto, a una persona y a un grupo
Remitente: ^\+34700        Mensaje: (vacío)        → 123456789       [seguir evaluando]
Remitente: ^\+34700        Mensaje: (vacío)        → -1001234567890

# Las alertas de la alarma, a un canal público del que ya eres administrador
Remitente: (?i)alarma       Mensaje: (vacío)        → @avisos_de_casa
```

Los `chat_id` de los ejemplos son inventados. Los reales se eligen con **Elegir de los chats del
bot**; teclearlos a mano solo tiene sentido si ya los conoces.

### Cómo encontrar el chat de destino

Nadie se sabe su `chat_id`: es un número que la aplicación de Telegram no muestra en ninguna
pantalla. Para eso está **Elegir de los chats del bot** en el editor de la regla, que pregunta al
bot quién le ha escrito y ofrece la lista.

Dos cosas que conviene saber antes de pulsarlo:

- **Un bot no puede escribir primero.** Hasta que una persona le manda algo —`/start` basta— el
  chat no existe y no aparecerá en la lista. Si vas a entregar en un grupo, añade el bot al grupo
  y escribe cualquier cosa.
- **Telegram guarda esos mensajes 24 horas.** Un chat que escribió la semana pasada no sale en la
  lista. No es un fallo: que vuelva a escribir al bot y aparecerá.

Para un **canal público** no hace falta nada de esto: vale su `@usuario` directamente, siempre que
el bot sea administrador del canal. Para una persona, en cambio, el `@usuario` **no sirve**: un bot
necesita su `chat_id` numérico.

### Por qué conviene dejar `{telefono}` en la plantilla

Por SMS, el reenvío llegaba como un SMS más y el teléfono ya enseñaba de quién venía. En Telegram
**todos los avisos llegan del mismo bot**: cuatro mensajes seguidos sin el remitente dentro son
cuatro mensajes indistinguibles. La plantilla por defecto —`{telefono} · {fecha}` y el mensaje
debajo— pone el contexto que el SMS daba gratis.

---

## Permisos

| Permiso | Para qué |
|---------|----------|
| `RECEIVE_SMS` | Recibir el broadcast de SMS entrantes |
| `READ_SMS` | Leer el contenido de las PDU |
| `INTERNET` | Entregar los mensajes a Telegram, y comprobar si hay versión nueva |
| `POST_NOTIFICATIONS` | Notificación del servicio en primer plano (Android 13+) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Mantener vivo el proceso durante la ingesta |
| `RECEIVE_BOOT_COMPLETED` | Reactivar los workers tras reiniciar |
| `WAKE_LOCK` | Completar el trabajo en curso con la pantalla apagada |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Solicitar la exención de Doze |
| `REQUEST_INSTALL_PACKAGES` | Abrir el instalador del sistema con el APK de una versión nueva |

**No se declara `SEND_SMS`.** Esta aplicación no envía ningún SMS: recibe por la radio y entrega
por internet. Tampoco `READ_PHONE_STATE`, que en SMStoSMS servía para elegir con qué SIM enviar.

`RECEIVE_SMS` y `READ_SMS` pertenecen a un grupo de permisos restringido por Google Play, cuya
publicación exige una declaración aprobada. Para distribución por APK, como es el caso, no supone
ningún problema.

---

## Estructura del proyecto

```
app/src/main/java/com/capicua/smstotelegram/
├── data/
│   ├── config/          # AppConfig, ConfigDataStore, ConfigBackup
│   ├── local/db/        # Room: entidades, DAOs, relaciones, SmsDatabase, Migraciones
│   ├── repository/      # Sms, Regla, Reenvio y Log + sus implementaciones
│   ├── rules/           # ReglasJson: formato de exportación
│   ├── telegram/        # TelegramSender, MapeoDeRespuestas, TokenDeBot,
│   │                    # DestinoDeTelegram, DescubridorDeChats, ComprobadorDelBot,
│   │                    # EstadoDelBot
│   └── update/          # Comprobación de versiones y descarga del APK
├── di/                  # Módulos Hilt: Database, Config, Repository, Worker, Transport
├── domain/
│   ├── model/           # SmsMessage, Regla, Reenvio, estados
│   ├── rules/           # EvaluadorDeReglas
│   ├── transport/       # EnviadorDeMensajes y ResultadoEnvio: la juntura del medio
│   └── usecase/         # ProcesarSmsEntranteUseCase, ObtenerListaSmsUseCase
├── presentation/
│   ├── home/            # Pestana, HomeFragment y el ViewPager2 de las cinco pestañas
│   ├── common/          # NestedScrollableHost: reparte el gesto dentro del pager
│   ├── dashboard/       # Inicio: SMS recibidos y qué se hizo con ellos
│   ├── rules/           # Lista de reglas, editor y panel de prueba
│   ├── logs/            # Registro con filtros y exportación
│   ├── settings/        # Bot, recepción, límites, reintentos y copia de seguridad
│   └── about/           # Acerca de
├── receiver/            # SmsReceiver, BootReceiver
├── service/             # SmsIngestionService
├── util/                # Constants, Extensions, OptimizacionBateria, InicioAutomatico,
│                       # ControlRecepcion
├── worker/              # DispatchWorker, ColaDeEnvios, OrphanRescue, HealthMonitor
└── SmsToTelegramApplication.kt
app/schemas/             # Esquemas Room exportados (versionados)
docs/
└── ARCHITECTURE.md      # Documentación técnica completa
```

---

## Base de datos

SQLite vía Room, cuatro tablas:

- **`sms`** — mensajes recibidos, con su estado de tramitación
- **`reglas`** — reglas de reenvío, ordenadas por prioridad
- **`reenvios`** — una entrega a un chat, con su propio estado, intentos y último error
- **`log_entries`** — registro de auditoría de todos los eventos

Los esquemas se exportan a `app/schemas/` y se versionan: son la referencia para escribir
migraciones. No se usa `fallbackToDestructiveMigration`, porque perdería mensajes pendientes.

---

## Tests

```bash
./gradlew test
```

Cien tests sobre las cuatro piezas que se pueden probar sin red ni Android, que son también las
que más importa que estén bien:

| Suite | Qué fija |
|-------|----------|
| `EvaluadorDeReglasTest` | El motor de reglas, y que en el destino no se resuelve ningún marcador |
| `MapeoDeRespuestasTest` | Qué respuesta del Bot API es transitoria y cuál permanente |
| `DestinoDeTelegramTest` | Qué se acepta como chat, y la ambigüedad entre un móvil y un `chat_id` |
| `TokenDeBotTest` | La forma de un token, sin apretarla hasta rechazar uno legítimo |
| `ReglasJsonTest` | El formato de intercambio, y que el token **no** aparece en él |
| `VersionPublicadaTest` | La comparación de versiones como números y no como texto |

Son Kotlin puro, sin Android, sin Robolectric y sin base de datos en memoria.

---

## Contribuir

1. Haz un fork del repositorio
2. Crea una rama para tu feature: `git checkout -b feature/nombre-feature`
3. Respeta las convenciones de nombres del proyecto, descritas en [`CLAUDE.md`](CLAUDE.md)
4. Realiza tus cambios y añade tests
5. Abre un Pull Request con una descripción clara del cambio

---

## Apoyar el proyecto

SMStoTelegram es gratuito y de código abierto. Si te resulta útil, puedes invitarme a un café:

[![Donar con PayPal](https://img.shields.io/badge/Donar-PayPal-00457C?logo=paypal&logoColor=white)](https://paypal.me/jbautistamartin)

El enlace también está dentro de la aplicación, en la pestaña **Acerca de** («Invitar a un café»).
Es completamente opcional: la aplicación no muestra avisos ni limita ninguna función.

---

## Licencia

Este proyecto se distribuye bajo la **GNU Lesser General Public License v2.1**.
Consulta el fichero [`LICENSE`](LICENSE) para más detalles.

---

## Aviso legal

Este software se proporciona **"tal cual"**, sin garantía de ningún tipo, expresa o implícita.

Esta aplicación **entrega el contenido de mensajes SMS en Telegram**. Eso implica que datos
personales —códigos de acceso, avisos bancarios, comunicaciones privadas— salen del dispositivo
que los recibió, **viajan por internet y pasan por los servidores de Telegram**, que es una
empresa ajena a este proyecto, antes de quedar guardados en el chat de destino.

Ni Capicua ni el autor reciben ninguno de esos mensajes. Telegram sí, y sus propias condiciones y
su política de privacidad son las que rigen lo que hace con ellos.

Antes de desplegarla, considera:

- El **remitente original no sabe** que su mensaje se está reenviando y no ha dado su
  consentimiento para ello.
- El mensaje **queda en el chat de destino** de forma indefinida, y en el dispositivo de cada
  persona que tenga acceso a ese chat. Si el destino es un grupo o un canal, lo verá todo el que
  esté dentro, ahora y en el futuro.
- El contenido de los SMS queda almacenado también en este dispositivo durante el periodo de
  retención configurado, y el registro de auditoría guarda los números implicados.
- El **token del bot es una credencial**: quien lo obtenga puede escribir en nombre del bot. No
  viaja en el fichero de copia de seguridad, pero sí está guardado en este dispositivo.

El autor no se hace responsable de:

- Pérdida, alteración o interceptación de mensajes durante el tránsito o el almacenamiento, ni
  del tratamiento que Telegram haga de ellos.
- Uso indebido de la aplicación para capturar o redirigir comunicaciones sin el consentimiento de
  los remitentes.
- Daños directos o indirectos derivados del uso, mal uso o imposibilidad de uso del software.
- Incumplimientos normativos (RGPD, LOPD u otras regulaciones de privacidad) derivados de una
  configuración o despliegue inadecuados, incluida la transferencia de datos personales a un
  tercero que esta aplicación realiza por diseño.

**El operador es el único responsable** de garantizar que el uso de esta aplicación cumple con la
legislación vigente en su jurisdicción, incluyendo la obtención del consentimiento necesario para
el tratamiento de los datos personales contenidos en los SMS.

---

## Desarrollo asistido por IA

El diseño, la arquitectura y el código de este proyecto se desarrollaron con asistencia de
[Claude](https://claude.ai) (Anthropic). El código fue revisado, validado y adaptado por el autor
antes de cada commit.
