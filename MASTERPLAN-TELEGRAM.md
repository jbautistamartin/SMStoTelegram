# MASTERPLAN — SMStoSMS → SMStoTelegram

> `MASTERPLAN-SMS.md` recoge la migración anterior, **SMSGateway → SMStoSMS**, y sigue siendo la
> referencia de por qué la arquitectura es como es. Este documento recoge la siguiente:
> cambiar el **medio de salida** sin tocar la tubería.

Fecha de apertura: **12 oct 2026** · Partida: copia literal de SMStoSMS v1.2.0 (commit `d83e5cb`).

---

## 1. Idea central

SMStoSMS recibe un SMS y lo reenvía **como SMS** a otro teléfono. SMStoTelegram recibe el mismo
SMS y lo entrega **como mensaje de Telegram** a una persona, un grupo o un canal, a través de un
bot propio.

Lo que no cambia es casi todo:

```
SIM → SmsReceiver → SmsIngestionService → Room (outbox) → EvaluadorDeReglas
    → ReenvioEntity × N → ColaDeEnvios → DispatchWorker → [TRANSPORTE]
```

Esa tubería es agnóstica del medio. El patrón outbox, la idempotencia de cliente, el rescate de
huérfanos cada 30 s, el purgado del `HealthMonitorWorker`, el historial, los logs, las cinco
pestañas y la copia de seguridad valen igual. **El único punto de la cadena que sabe de SMS en
la salida es `SmsSender`.**

Y `ResultadoEnvio` ya tiene la forma correcta —`Enviado` / `ErrorTransitorio` / `ErrorPermanente`—
que es exactamente la clasificación que hace falta para los códigos del Bot API. Esa es la razón
de que este cambio sea una fase de transporte y no una reescritura.

## 2. Decisiones cerradas

| Decisión | Valor | Por qué |
|----------|-------|---------|
| Aplicación | **Nueva**, no una variante de SMStoSMS | `applicationId` propio, base de datos propia, releases propias. Convivir con SMStoSMS en el mismo teléfono es un caso real: una cosa es reenviar por SMS y otra por Telegram. |
| `applicationId` | `com.capicua.smstotelegram` | |
| Esquema Room | **Vuelve a v1**, sin migraciones | Nadie tiene esta base de datos instalada. Es el mismo razonamiento, y el mismo precedente, que el KDoc de `SmsDatabase` ya documenta para SMSGateway → SMStoSMS. |
| Transporte | **Solo Telegram** | Multi-transporte duplica la superficie de interfaz —selector por regla, configuración por transporte, guardias por transporte— para un caso que no existe. El interfaz se define igualmente, porque documenta la juntura y no cuesta nada. |
| Cliente HTTP | `HttpURLConnection` + `kotlinx.serialization` | Es el patrón que `data/update` ya demuestra. **Retrofit y OkHttp no vuelven.** |
| Formato del mensaje | Texto plano, **sin `parse_mode`** | Ver la trampa en la fase 3. |
| Credencial | Token del bot en Ajustes, **fuera de la copia de seguridad** | Un token permite escribir en nombre del bot. El fichero de copia es JSON en claro. Mismo criterio que el id de SIM, motivo distinto. |
| Versión de partida | `versionCode 1`, `versionName 0.1.0` | Subió a `versionCode 2` / `1.0.0` al cerrar la fase 8. |
| Firma | El mismo almacén de la casa (`capicua.jks`) | Con `applicationId` distinto no hay conflicto, y mantiene la cadena de actualización de esta app a partir de su primera release. |

### Lo que el cambio de medio trae consigo

**Se simplifica:**

- **No hay multipart.** 4.096 caracteres por mensaje en vez de 160. `divideMessage`,
  `contarPartes`, la columna `partes` y los avisos de coste se van.
- **No hay `SEND_SMS`** ni selector de SIM de envío. (Ojo: `RECEIVE_SMS` está en el mismo grupo
  restringido de Google Play, así que la puerta de Play no se abre sola por esto.)
- **Dos de las tres protecciones de bucle dejan de tener sentido.** Un mensaje de Telegram no
  vuelve nunca como SMS: ni el remitente puede ser un destino configurado, ni el destino puede
  ser el remitente. Se borran, y con ellas la mitad de `NormalizadorTelefono`.
- **Reenviar deja de costar dinero.** Eso cambia el tono de media interfaz.

**Se complica:**

- **El destino es un `chat_id`, no un teléfono**, y nadie se sabe su `chat_id` de memoria.
- **El bot no puede escribir primero.** Quien vaya a recibir los avisos tiene que mandarle
  `/start` antes. Es el problema de soporte número uno de cualquier app con bot.
- **El contenido de los SMS sale a la red.** Esto obliga a reescribir el aviso de privacidad, y
  es el cambio más importante de toda la migración desde el punto de vista de quien la instala.
- **Hay límites de tasa ajenos** (≈30 msg/s global, ≈1/s por chat, 20/min por grupo) y un `429`
  que dice en la respuesta cuánto hay que esperar.

---

## 3. Fases

### Fase 1 — Renombrado integral y esquema v1 limpio · `[x]` completada el 12 oct 2026

Primero porque el nombre está incrustado en todas partes y porque hacerlo después de portar el
envío significa volver a pasar por todo. Es mecánico y desbloquea el resto.

- Paquete `com.capicua.smstosms` → `com.capicua.smstotelegram`: directorios de `main` y `test`,
  `package`/`import`, referencias en `nav_graph.xml` y `fragment_logs.xml`.
- `namespace` y `applicationId` en `app/build.gradle.kts`; `rootProject.name`; nombre del APK de
  release (`smstotelegram-<versionName>.apk`); `versionCode 1` / `versionName 0.1.0`.
- `SmsToSmsApplication` → `SmsToTelegramApplication`.
- Cabecera de licencia de los 95 ficheros: `// SMStoSMS para Android` → `// SMStoTelegram para Android`.
- Literales: `NOMBRE_BD`, `NOTIFICATION_CHANNEL_ID`/`_NAME`, nombre del DataStore, acción del
  broadcast de confirmación, nombre del fichero de copia, nombre del fichero de logs exportados,
  `User-Agent`, `URL_API` y `URL_PROYECTO` del repositorio, `app_name` y los strings que nombran
  la app en instrucciones al usuario.
- Esquema: `version = 1`, `Migraciones.TODAS` vacío, `DatabaseModule` sin `addMigrations`,
  borrar `app/schemas/com.capicua.smstosms…/` y regenerar `1.json` con `ignorar_mayusculas` ya
  dentro.

**Hecho cuando:** `./gradlew assembleDebug test` pasa, no queda ninguna aparición de `smstosms`
ni `SMStoSMS` fuera de `MASTERPLAN.md` y `CHANGELOG.md`, y `app/schemas/` tiene un solo `1.json`.

#### Resultado real

`assembleDebug test` en verde. 120 ficheros tocados, `git mv` para los dos árboles de paquetes
—`main` y `test`— de modo que el historial sigue siguiendo cada fichero. `app/schemas/` tiene un
único `1.json` de la versión 1, con `ignorar_mayusculas` ya dentro, y los 85 tests de las cuatro
suites pasan sin tocar ni una línea de test: `EvaluadorDeReglas` (45), `NormalizadorTelefono` (18),
`VersionPublicada` (12) y `ReglasJson` (10).

Quedan **dos menciones deliberadas a SMStoSMS en el código**, las dos en KDoc: el de `SmsDatabase`
y el de `Migraciones`, explicando por qué el esquema nace otra vez en la v1. Son linaje, no restos.

#### Tres cosas que conviene recordar

1. **El entorno de build necesita `JAVA_HOME` a mano.** El JDK del `PATH` es un **JRE 25**, que
   AGP 8.5.2 no soporta —y además es un JRE, no un JDK—. Lo que funciona en esta máquina:
   `JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"`.
2. **KSP falló una vez con una traza RMI** (`Error occurred in KSP, check log for detail` sobre un
   volcado de `sun.rmi.transport`). No era un error de código: es el demonio de compilación de
   Kotlin cayéndose. La misma tarea, repetida sin tocar nada, terminó correctamente. Si vuelve a
   pasar, repetir antes de buscar la causa en el código.
3. **`versionCode` vuelve a 1 y `versionName` a `0.1.0`.** Es una aplicación nueva, con
   `applicationId` nuevo y, por tanto, sin base instalada que respetar. Sube a 1.0.0 en la fase 8.

#### Lo que la fase 1 deliberadamente **no** toca

La columna `reenvios.partes` sigue en el esquema v1, y `SEND_SMS` sigue en el manifest, porque el
transporte todavía es SMS: quitarlos aquí habría dejado el proyecto sin compilar a mitad de una
fase mecánica. Caen en la 2 y la 3, y con ellos el `1.json` se regenerará una vez más antes de que
el esquema quede fijado. La documentación (`README`, `CLAUDE.md`, `docs/ARCHITECTURE.md`) sigue
hablando de SMStoSMS a propósito: es la fase 7, y reescribirla ahora significaría hacerlo dos
veces, porque la mitad de lo que dice va a cambiar en las fases 2 a 6.

### Fase 2 — La juntura: interfaz de transporte · `[x]` completada el 12 oct 2026

- `domain/transport/EnviadorDeMensajes.kt`: `suspend fun enviar(destino, texto): ResultadoEnvio`.
- `ResultadoEnvio` sube de `data/sms` a `domain/transport` — deja de ser un detalle del SMS.
  `Enviado.partes` desaparece.
- `SmsDispatchWorker` → `DispatchWorker`, dependiendo del interfaz y no de `SmsSender`.
- `SmsSender`, `ProveedorDeSims` y el ajuste de SIM se borran. El `subscriptionId` sale de
  `AppConfig`; `timeoutEnvioSegundos` se queda, ahora es el timeout HTTP.
- Implementación provisional que devuelve `ErrorPermanente("transporte sin configurar")`.

**Hecho cuando:** compila y los tests pasan con el transporte en blanco, y nada fuera de
`data/telegram` (todavía vacío) menciona el medio.

#### Resultado real

`assembleDebug test` en verde, 85 tests y 0 fallos. **El paquete `data/sms` ha desaparecido
entero**: la aplicación ya no sabe enviar un SMS. Lo que queda de SMS es la entrada —
`SmsReceiver`, `SmsIngestionService`— que es lo que esta aplicación es.

El interfaz quedó con tres miembros, y los tres salieron de necesidades reales de quien llama:

| Miembro | Para qué | De dónde salió |
|---------|----------|----------------|
| `enviar(destino, texto, timeoutSegundos)` | Entregar y **esperar** | `SmsSender.enviar` sin el `subscriptionId` |
| `limiteCaracteres` | Avisar mientras se escribe la plantilla | Sustituye a `contarPartes` |
| `motivoNoDisponible(): String?` | Avisar **antes** de pulsar enviar | Sustituye a `tienePermisoEnviar` |

`motivoNoDisponible` es `suspend` porque el transporte de Telegram tendrá que leer el token del
DataStore, y eso obligó a que `ProbarReglasViewModel.construirAvisos` también lo sea. Es la forma
correcta: devuelve el **motivo** en texto, no un booleano, porque la pantalla tiene que poder
decir *por qué* no se puede enviar y los motivos van a cambiar con el medio.

`TransportModule` es el único sitio del proyecto que nombra una implementación concreta del medio.
Cambiar de transporte es cambiar su `@Binds`, y eso es precisamente lo que hará la fase 3.

#### Dos decisiones tomadas al implementar

1. **La restricción de red se adelantó de la fase 3 a esta.** El KDoc de `ColaDeEnvios` explicaba
   que no había ninguna *porque un SMS sale por la radio del operador*. Al retirar el SMS esa
   frase pasaba a ser falsa, y dejar un comentario falso una fase entera es peor que adelantar una
   línea de código. `NetworkType.CONNECTED`, no `UNMETERED`: esperar al wifi sería dejar de
   reenviar. El KDoc recoge ahora las tres respuestas que ha tenido el proyecto —SMSGateway no la
   ponía a propósito, SMStoSMS no la necesitaba, SMStoTelegram sí— para que nadie la «arregle».
2. **`SEND_SMS` y `READ_PHONE_STATE` salieron del manifest ya**, con el código que los usaba, en
   lugar de esperar. `SEND_SMS` era un permiso restringido; `READ_PHONE_STATE` solo servía para
   enumerar SIM. El diálogo de permiso obligatorio pasa a exigir solo `RECEIVE_SMS`.

#### Lo que se fue con el medio

El selector de SIM completo (ajuste, sección del layout, cuatro strings y `ProveedorDeSims`), el
recuento de partes (`ReenvioEntity.partes`, `Reenvio.partes`, el plural `regla_partes` y los
mapeos de los repositorios) y el parámetro `simActual` de `ConfiguracionJson.aDominio()`. El
contador de partes se sustituyó por uno de longitud contra `limiteCaracteres`, que es el aviso que
de verdad importa ahora: pasarse del límite del transporte es un fallo **permanente**, no un
cargo extra. El `1.json` se regeneró sin `partes`.

El criterio de «esto no viaja en la copia de seguridad» sobrevivió a la SIM que lo motivó, y está
escrito en el KDoc de `ConfiguracionJson` esperando a que la fase 4 lo aplique al token.

#### Pendiente anotado para la fase 3

El comentario del bloque `INTERNET` en el manifest sigue afirmando que «ningún contenido de los
mensajes viaja por aquí». Hoy es verdad —no hay transporte— y deja de serlo en el momento en que
`TelegramSender` exista. **Reescribirlo es parte de la fase 3**, no de la 7: un permiso mal
documentado en el manifest es el sitio donde peor sienta.

### Fase 3 — Cliente de Telegram · `[x]` completada el 12 oct 2026

`data/telegram/`: `ApiTelegram` (el `POST` a `sendMessage`), modelos serializables de la
respuesta, y `TelegramSender : EnviadorDeMensajes`.

**La trampa del formato.** Con `parse_mode` en `Markdown` o `HTML`, cualquier SMS que lleve `_`,
`*`, `[` o `<` devuelve `400 Bad Request: can't parse entities` o se renderiza mal. Se envía
**texto plano**. Es la misma clase de problema que ya está resuelta en el evaluador sustituyendo
`{mensaje}` en último lugar: el cuerpo del SMS es contenido de terceros y no se interpreta nunca.

**El mapeo de errores es el corazón de la fase:**

| Respuesta | Resultado |
|-----------|-----------|
| `200` con `ok:true` | `Enviado` |
| `429` | `ErrorTransitorio`, **respetando `parameters.retry_after`** |
| `5xx`, timeout, `IOException` | `ErrorTransitorio` |
| `400` (chat inexistente, texto vacío o excesivo) | `ErrorPermanente` |
| `403` (bot bloqueado o expulsado del grupo) | `ErrorPermanente` |
| `401` / token inválido | **Error de configuración, no de este reenvío** |

El último merece trato aparte: con un token mal puesto fallan *todos* los reenvíos por igual, y
enterrar eso en el log del primero de cuatrocientos desperdicia la única información útil. Sale
al Panel, como ya sale el aviso de versión nueva.

Además: `Constraints(NetworkType.CONNECTED)` en `ColaDeEnvios`. Hoy no hay ninguna, porque el SMS
no la necesitaba; sin ella se quemarían reintentos sin cobertura. Y troceado por los 4.096
caracteres del límite, que solo entra en juego con un SMS concatenado muy largo más una plantilla
generosa.

**El token es configuración, no código.** `AppConfig.tokenBot` y su campo en Ajustes entran en
esta fase y no en la siguiente: sin un sitio donde escribirlo, la única forma de probar el
transporte sería hurgar en el DataStore a mano, y un token en el código fuente de un APK es un
token regalado. Lo que queda para la fase 4 es el trato de la credencial, no su existencia.

**Hecho cuando:** se pega un token en Ajustes, se pone un `chat_id` en una regla, y un SMS
simulado con `adb emu sms send` llega a Telegram. Los tests del mapeo de errores pasan sin red.

#### Resultado real

`assembleDebug test` en verde con **109 tests** y 0 fallos: 24 nuevos entre `MapeoDeRespuestasTest`
(18) y `TokenDeBotTest` (6). La entrega real contra un bot vivo queda para la fase 8, que es la de
dispositivo; todo lo que se puede verificar sin red está verificado.

Cinco piezas nuevas en `data/telegram/`:

| Fichero | Papel |
|---------|-------|
| `TelegramSender.kt` | El `POST` a `sendMessage` y la implementación de `EnviadorDeMensajes` |
| `MapeoDeRespuestas.kt` | Función **pura** de (código HTTP, cuerpo) a `ResultadoEnvio`, y el troceado |
| `TokenDeBot.kt` | Lo que se puede decir de un token sin preguntar a Telegram |
| `EstadoDelBot.kt` | Recuerda si el token fue rechazado, para que el Panel pueda decirlo |

`MapeoDeRespuestas` está separado de quien hace la petición **precisamente** para que sea
testable: es la pieza que más importa que esté bien y la única que se puede probar sin red.

#### Decisiones tomadas al implementar

1. **El token entró en esta fase y no en la 4.** Sin un campo donde escribirlo, probar el
   transporte habría exigido hurgar en el DataStore a mano. Lo que queda para la 4 es el trato de
   la credencial —enmascarado, `getMe`, formato 4 del fichero—, no su existencia.
2. **Importar una copia habría borrado el token.** `ConfiguracionJson.aDominio()` construye un
   `AppConfig` completo y los ajustes se *reemplazan* al importar, así que un token ausente del
   fichero habría entrado como cadena vacía y la aplicación se habría quedado muda sin que nadie
   tocara nada. `aDominio(tokenActual)` lo arregla, y es el mismo papel que tenía `simActual`.
   Era un fallo de pérdida de datos y no podía esperar a la fase 4.
3. **`retry_after` se respeta durmiendo antes de devolver `retry()`.** WorkManager cuenta su
   backoff **desde que el worker termina**, así que dormir dentro del worker es la única forma que
   tiene de retrasar el siguiente intento. Con tope de 120 s: un worker tiene unos diez minutos
   antes de que el sistema lo pare, y bloquear uno un cuarto de hora para respetar un
   `retry_after` enorme cambia un problema por otro peor.
4. **Un `404` es un fallo de credencial, no un chat inexistente.** El token va *dentro de la URL*
   del Bot API, así que la ruta entera depende de él: un «no existe» ahí significa que el token no
   existe. `esFalloDeCredencial()` agrupa 401 y 404, y solo esos dos encienden el aviso del Panel;
   un 400 o un 403 son problemas de *ese* destino y mandar a revisar el token sería mandar a mirar
   donde no es.
5. **Troceado en vez de truncado.** Pasarse de 4.096 caracteres exige un SMS concatenado muy largo
   más una plantilla generosa, así que casi nunca ocurre. Cortar y tirar el resto perdería
   contenido en silencio; dejar que Telegram conteste `400` convertiría un mensaje largo en un
   reenvío fallido. Se trocea por el último salto de línea, y si no hay, por el último espacio.
6. **El aviso del Panel es un `Flow`, no una comprobación puntual.** Se recalcula con cada cambio
   de la configuración y con el flag de token rechazado, que es cuando puede dejar de ser cierto.
   Guardar en Ajustes limpia el flag: es el gesto de quien viene a arreglarlo, y si el token nuevo
   tampoco sirve, el siguiente envío lo pondrá otra vez.

#### La trampa que no se pisó

**Nada de `parse_mode`.** Con `Markdown` o `HTML`, cualquier SMS que lleve un `_`, un `*`, un `[`
o un `<` —un enlace de un banco, una carita— hace que Telegram devuelva
`400 Bad Request: can't parse entities`, y el reenvío se cierra como fallido permanente: el
mensaje **no se entrega** por haber querido ponerlo en negrita. El cuerpo del SMS es contenido de
un tercero y no se interpreta nunca, que es la misma regla que el evaluador ya aplicaba al
sustituir `{mensaje}` en último lugar.

#### El comentario del manifest, reescrito

Dicho y hecho: el bloque de `INTERNET` afirmaba que «ningún contenido de los mensajes viaja por
aquí». Ahora dice lo contrario, que es la verdad, y además dice que **hasta la versión anterior la
frase era la otra y se mantuvo escrita mientras fue cierta**. El aviso de privacidad de «Acerca
de» sigue siendo de la fase 7.

### Fase 4 — El bot, bien tratado · `[x]` completada el 12 oct 2026

Lo que no es «que funcione», que ya lo hizo la fase 3, sino «que la credencial esté bien»:

- Enmascarado en la interfaz y **nunca** en los logs, ni en un `Timber.d` de depuración.
- Botón de validación que llama a `getMe` y muestra el `@usuario` del bot: retroalimentación
  inmediata de que el token sirve, en vez de descubrirlo cuando falle el primer reenvío.
- `ConfigBackup` sube a **formato 4**: el token **no viaja** en el fichero. Importar una copia en
  otro teléfono no se lleva la credencial, igual que no se llevaba la SIM, y por un motivo peor:
  el fichero es JSON en claro y el token permite escribir en nombre del bot.

**Hecho cuando:** exportar e importar funciona, el token no aparece en el JSON exportado ni en
`adb logcat`, y la pantalla dice de qué bot se trata.

#### Resultado real

110 tests, 0 fallos. `ComprobadorDelBot` es la pieza nueva, y hace `getMe`.

**La validación no contesta «sí» o «no»: contesta de qué bot se trata.** Enseñar el nombre y el
`@usuario` es lo que convierte la respuesta en algo comprobable por quien la pidió, que puede
reconocer su bot —o darse cuenta de que ha pegado el de otro, que con dos bots en la misma cuenta
es un error fácil—. Un «✓ correcto» a secas obliga a creerse la pantalla.

Cuatro resultados y no dos, porque «no se pudo preguntar» no es «el token está mal»:

| Resultado | Cuándo | Color |
|-----------|--------|-------|
| `Correcto(nombre, usuario)` | `getMe` contestó | Verde |
| `Rechazado(motivo)` | 401/404, o la forma ni siquiera cuadra | Rojo |
| `NoSePudo(motivo)` | Sin red, Telegram caído, respuesta ilegible | Ámbar |
| `SinToken` | El campo está vacío | Neutro |

Decir «el token está mal» cuando lo que pasa es que no hay cobertura mandaría a cambiar un token
que funciona.

#### Tres decisiones

1. **Se valida el texto del campo, no lo guardado.** Obligar a guardar primero para poder
   comprobar significaría dejar guardado un token que no vale. `comprobar(tokenCandidato)` acepta
   el candidato y el parámetro es opcional para cuando se quiera comprobar el que ya está.
2. **Un `Correcto` borra el aviso de «token rechazado»; un `Rechazado` no lo enciende.** Ese aviso
   habla del token que la aplicación *está usando*, y lo pone quien intenta usarlo de verdad
   —`TelegramSender`—, no una comprobación sobre un candidato que a lo mejor ni se guarda.
3. **`endIconMode="password_toggle"` en vez de un campo oculto sin más.** Enmascarado por defecto
   porque es una credencial y no debe quedarse legible en pantalla, pero con el ojo para
   comprobarlo: esconderlo sin poder verlo haría imposible detectar un pegado a medias, que es el
   error más frecuente.

#### Un apaño que duró diez minutos

La primera versión de `ComprobadorDelBot` quería borrar el flag desde `interpretar()`, que no es
`suspend`, y lo resolvía con un `@Volatile` más un `aplicarPendientes()` que quien llama tenía que
acordarse de invocar. Un contrato así se incumple la primera vez que alguien añade una llamada.
Se sustituyó por hacerlo en `comprobar()`, donde sí se puede suspender; `interpretar()` se queda
siendo una función sin efectos, que es lo que debía ser.

#### Lo que se movió a la fase 5

**El formato del fichero no subió a 4 aquí.** `ConfiguracionJson` no cambió de campos —el token
nunca estuvo— así que subir la versión no habría descrito ningún cambio. Pero al revisarlo apareció
algo peor que merece la fase en la que de verdad ocurre: un fichero del **formato 3** escrito por
SMStoSMS tiene exactamente la misma forma que uno de esta aplicación, y sus `destino` son **números
de teléfono**. Importarlo aquí crearía reglas cuyos destinos no son chats y que fallarían una por
una sin explicación. La versión sube en la fase 5, que es donde `destino` cambia de significado,
y la importación tiene que rechazar los ficheros anteriores.

### Fase 5 — Reglas: el destino es un chat · `[x]` completada el 12 oct 2026

- `destino` deja de ser un número y pasa a ser un `chat_id` (numérico, negativo en grupos) o un
  `@usuario` de canal. Validación propia en vez de la de teléfonos.
- **Descubrimiento en vez de teclear:** botón que llama a `getUpdates` y ofrece la lista de quién
  ha escrito al bot, para elegir. Es el equivalente del botón «Contestar al remitente»: una
  función que nadie conoce no es una función.
- La pantalla explica que el destinatario tiene que mandar `/start` al bot primero.
- `{telefono}` **desaparece como destino** (no hay forma de resolver un remitente de SMS a un chat)
  y «Contestar al remitente» se va con él.
- `{telefono}` **gana peso en la plantilla**: en Telegram todo llega del mismo bot, así que sin el
  remitente se pierde el contexto que el SMS daba gratis. La plantilla por defecto pasa de
  `{mensaje}` a `{telefono} · {fecha}` + salto de línea + `{mensaje}`.
- `ReglasJson` sube de versión y `ProbarReglasFragment` se ajusta.

**Hecho cuando:** se crea una regla de principio a fin sin saber qué es un `chat_id`.

#### Resultado real

100 tests, 0 fallos. El recuento **baja** de 110 y eso es la noticia: se fueron los 18 de
`NormalizadorTelefonoTest` junto con la clase que probaban, y entraron 10 de
`DestinoDeTelegramTest` más los retoques del evaluador. Código que se borra es trabajo hecho.

#### La fase absorbió las guardias de la fase 6

No por adelantar trabajo, sino porque **no se podían separar**. Las dos protecciones antibucle
comparaban el destino de la regla con el remitente del SMS, y en el momento en que el destino
pasa a ser un `chat_id` esa comparación deja de significar nada: un chat de Telegram y un número
de teléfono son identidades sin relación, no pueden ser «el mismo» ni cerrar un círculo. El SMS
que antes volvía por el receptor ahora llega a Telegram y se queda ahí.

Dejarlas una fase más habría significado mantener un `if` que no puede ser cierto y un
interruptor en Ajustes que no apaga nada. Se fueron con ellas:

- Las guardias 1 y 2 de `ProcesarSmsEntranteUseCase` y la comprobación de destino enviable.
- `AppConfig.protegerBucles`, su clave en DataStore, su campo en el fichero y su tarjeta en
  Ajustes, con la sección «Protecciones» que solo contenía ese interruptor.
- `NormalizadorTelefono` **entero** y sus 18 tests.
- `ReglaDao.obtenerDestinosActivos()` y su camino por el repositorio, que solo servía a la
  guardia 1.

Lo que queda para la fase 6 es lo que de verdad es suyo: el límite por minuto redocumentado, los
tipos de log y los textos que todavía hablan de coste.

#### Lo que entró

| Pieza | Papel |
|-------|-------|
| `DestinoDeTelegram` | Qué puede escribirse en el destino. Sustituye a `NormalizadorTelefono` |
| `DescubridorDeChats` | `getUpdates`: la lista de quién ha escrito al bot |

**El descubrimiento no es un adorno.** Nadie se sabe su `chat_id`: es un número que Telegram no
muestra en ninguna de sus pantallas, y obtenerlo «a mano» exige hablar con un bot de terceros o
leer una URL de la API en el navegador. Es el mismo problema que resolvía el botón «Contestar al
remitente», y la misma respuesta: un dato que hay que conocer de antemano no se usa.

Y el caso vacío se explica en lugar de dejar una lista en blanco: **un bot no puede escribir
primero**, así que si no aparece nadie lo que falta es un `/start` del otro lado. Es el problema
de soporte número uno de cualquier aplicación con bot.

#### Cuatro decisiones

1. **El formato del fichero sube a 4 y rechaza los anteriores** — la primera vez en el proyecto
   que una importación se rompe a propósito. Un fichero del formato 3 tiene *exactamente la misma
   forma*: ningún campo cambió de nombre ni de tipo. Lo que cambió es lo que `destino` significa.
   Entraría sin un solo error de parseo y dejaría reglas cuyos destinos no existen, fallando una
   por una cuando llegase un SMS y sin nada que apuntase al fichero importado.
2. **La plantilla por defecto pasa de `{mensaje}` a `{telefono} · {fecha}` + el mensaje.** Con el
   SMS bastaba el cuerpo, porque el reenvío llegaba como un SMS y el teléfono ya enseñaba de
   quién era. Por Telegram todo llega del mismo bot: cuatro avisos seguidos serían cuatro
   mensajes indistinguibles.
3. **`ReglaEntity` pierde su `defaultValue` de la plantilla.** Estaba escrita dos veces —ahí y en
   `Regla.PLANTILLA_POR_DEFECTO`— y al cambiar una habrían dejado de coincidir. Ninguna inserción
   usa la de la entidad, así que sobra, y quitarla deja una sola fuente de verdad.
4. **La validación del destino da tres mensajes y no uno.** El error probable no es una letra de
   más: es escribir un número de teléfono, porque uno viene de SMStoSMS o porque es lo que se
   supone que va en un campo llamado «destino». «No parece un chat» es verdad y no sirve de nada.

#### Una ambigüedad que encontró el propio test

`600112233` son nueve dígitos seguidos: es a la vez un móvil español escrito a pelo y un `chat_id`
de persona perfectamente legítimo. No hay forma de distinguirlos. El test lo dio por teléfono, el
código por chat, y **el código tenía razón**: rechazar un `chat_id` válido rompe una configuración
correcta con un mensaje de error que además es falso, mientras que un teléfono que se cuele acaba
en un `400 chat not found` con su motivo en el registro. `pareceTelefono` solo reconoce lo que *no
puede* ser un chat —lo que lleva `+`, espacios, paréntesis o guiones— y hay un test con su KDoc
fijando la ambigüedad para que nadie la «arregle» al revés.

### Fase 6 — Guardias y límites · `[x]` completada el 12 oct 2026

- Se borran las guardias 1 y 2 de `ProcesarSmsEntranteUseCase`, el ajuste `protegerBucles`, la
  comprobación de `esDestinoEnviable` y lo que queda sin uso de `NormalizadorTelefono`.
- `maxReenviosPorMinuto` se queda, **redocumentado**: ya no es un cortafuegos económico sino el
  respeto a los límites del Bot API.
- `LogTipo.BUCLE_EVITADO` desaparece o se reutiliza para los descartes por límite de tasa.

**Hecho cuando:** no queda código inerte y ningún texto de la interfaz habla de facturas.

#### Resultado real

La fase 5 se llevó las guardias por delante, así que lo que quedaba aquí es lo que de verdad era
suyo: **qué significa ahora el límite, y qué dice la interfaz**.

`maxReenviosPorMinuto` se queda, y era la única de las tres protecciones originales que tenía
sentido conservar — pero **no por el mismo motivo**, y eso es lo que había que reescribir. Era un
cortafuegos económico: un bucle de reenvíos con los reintentos detrás se convertía en una factura.
Entregar por Telegram es gratis, así que ahora protege de otra cosa, los límites de quien recibe:
el Bot API admite del orden de 30 mensajes por segundo en total, **uno por segundo y chat** y unos
20 por minuto en un grupo. Pasarse no cuesta dinero, cuesta `429`, y con un grupo de destino eso
llega enseguida. El valor por defecto queda holgadamente por debajo de los tres techos.

#### `BUCLE_EVITADO` → `LIMITE_ALCANZADO`

El tipo de log se llamaba por la protección que ya no existe. Con las guardias fuera, el único
descarte que queda es el del límite de frecuencia, así que el tipo pasa a llamarse por lo que de
verdad registra, y con él el chip del filtro («Bucles» → «Descartados»), su color y la cadena.
Renombrar un valor de enum que se guarda como texto en la base de datos sería una migración si
hubiera datos; no los hay, y es la última ocasión de hacerlo gratis.

#### La sección «Protecciones» pasa a llamarse «Límites»

Contenía dos cosas y se quedó con una. Un encabezado en plural sobre un solo campo que además ya
no protege de un bucle sino de un `429` describía mal lo que hay debajo.

#### Código inerte encontrado al revisar

`EvaluadorDeReglas.MARCADORES` —«los tres marcadores admitidos, para documentación y validación»—
estaba declarado y **no lo usaba nadie**. Ni la pantalla de reglas, que escribe los marcadores en
su texto de ayuda, ni la validación, que no valida marcadores. Fuera. Lo demás estaba limpio: una
pasada buscando `esDestinoEnviable`, `estaEnLista`, `mismoNumero`, `respondeAlRemitente`,
`resolverDestino` y `protegerBucles` no encontró ni una aparición.

#### Textos corregidos

| Antes | Ahora |
|-------|-------|
| «Cortafuegos de coste» | Los límites reales de Telegram, con las cifras |
| «envía un SMS de verdad al primer destino, y se cobra como cualquier otro» | «entrega un mensaje de verdad en el primer chat de destino» |
| «reenviar el mismo SMS a varios números» | «entregar el mismo SMS en varios chats» |

Queda **una** mención a «coste» en la interfaz, en el descargo de responsabilidad de «Acerca de»,
y esa se queda: habla de los costes de los que el autor no responde, no de lo que cuesta un
reenvío.

### Fase 7 — Documentación y privacidad · `[x]` completada el 12 oct 2026

- `CLAUDE.md` y `docs/ARCHITECTURE.md`: la afirmación «no message content ever travels over the
  network» pasa a ser **falsa** y hay que reescribirla, no matizarla.
- `about_privacidad_detalle`: el contenido de los SMS de terceros se entrega a los servidores de
  Telegram. Con el descargo de responsabilidad que ya está al lado, ese párrafo es la parte más
  importante de la documentación de esta app.
- `README.md` entero, `CHANGELOG.md` desde cero, `FUNDING.yml` y los enlaces de «Acerca de».

#### Resultado real

Cuatro documentos y la interfaz. Lo importante de la fase no es el volumen, es **una afirmación
que había que retirar**: hasta aquí el proyecto decía, en el manifest, en `CLAUDE.md`, en el
`README`, en `ARCHITECTURE.md` y en el aviso de privacidad de la aplicación, que el contenido de
los mensajes no viajaba por la red. Era verdad y se mantuvo escrita mientras lo fue. Ya no lo es.

#### El aviso de privacidad

Es el texto más importante del proyecto y se reescribió entero. Lo que ahora dice, y no decía:

- El contenido de los SMS **se envía por internet a los servidores de Telegram**, que es una
  empresa ajena al proyecto. Ni Capicua ni el autor reciben esos mensajes; Telegram sí.
- El mensaje **queda en el chat de destino**, y si ese chat es un grupo o un canal lo ve todo el
  que esté dentro, ahora y en el futuro.
- Lo que ya decía y sigue valiendo: el remitente original no lo sabe ni ha consentido, pueden ser
  claves de un solo uso o avisos de un banco, y quien instala es el único responsable.

El descargo de responsabilidad de al lado se amplió con la transferencia a un tercero, que esta
aplicación hace **por diseño** y no como efecto colateral.

#### Qué se tocó

| Documento | Alcance |
|-----------|---------|
| `app/src/main/res/values/strings.xml` | Privacidad, descripción, marcadores, subtítulo de Ajustes |
| `CHANGELOG.md` | **Desde cero**, con la nota de linaje y el aviso de incompatibilidad |
| `README.md` | 22 bloques: cabecera, para qué sirve, características, flujo, permisos, estructura, reglas, ejemplos, tests y aviso legal |
| `CLAUDE.md` | 30 bloques en dos pasadas |
| `docs/ARCHITECTURE.md` | 36 bloques más la sección 11 reescrita entera |

El `CHANGELOG` empieza de cero en lugar de continuar el de SMStoSMS porque son aplicaciones
distintas, y lleva por delante la advertencia que más falta hará: **un fichero de copia de
SMStoSMS no se puede importar aquí**, y la aplicación lo rechaza explicándolo.

#### Lo que se añadió, no solo corrigió

Documentar obligó a escribir cosas que no estaban en ninguna parte:

- **Cómo encontrar un `chat_id`**, con los dos límites que el botón no puede esconder: que un bot
  no puede escribir primero, y que Telegram guarda los updates 24 horas.
- **Por qué `{telefono}` conviene dejarlo en la plantilla**: todo llega del mismo bot.
- **Las tres formas de destino** del Bot API, con el aviso de que el signo menos de los grupos es
  lo que más se copia mal.
- **Cuatro incidencias nuevas** en `ARCHITECTURE.md` §14 —«chat not found», «bot was blocked»,
  «Too Many Requests» y los errores de red— y la retirada de las tres que hablaban de cobertura,
  saldo y fallos del operador.
- **La sección 11 entera**, que ahora explica *por qué* dos de las tres protecciones ya no existen
  en lugar de describir las tres. Mantener código que no puede ejecutarse es dejar una trampa
  para quien lea el fichero el año que viene y dé por hecho que algo lo protege.

#### Lo que se dejó a propósito

Las menciones a SMStoSMS que quedan en el código y en los documentos **son linaje, no restos**:
los KDoc de `SmsDatabase` y `Migraciones` explicando por qué el esquema nace otra vez en la v1, la
nota de cabecera del `README` y del `CHANGELOG`, y las comparaciones de cada sección que cambió.
Casi todas las decisiones de esta aplicación se entienden mejor sabiendo de qué vienen.

### Fase 8 — Dispositivo real y release 1.0.0 · `[x]` completada el 12 oct 2026

Instalar en el teléfono dedicado, verificar la cadena completa con el `SmsReceiver` del sistema,
comprobar que la exención de Doze sigue haciendo lo que hacía, y publicar.

#### Resultado real

Instalada la **release firmada** —no la de depuración, y eso era deliberado— en un Xiaomi con
HyperOS 2 sobre Android 16, donde además conviven SMStoSMS y SMSGateway.

**Lo que esta fase estaba ahí para descubrir era R8.** El build de publicación ofusca y recorta,
y Hilt, Room y `kotlinx.serialization` dependen de reflexión: si faltara una regla de ProGuard,
la aplicación arranca en depuración y se estrella en publicación. No pasó — ni una excepción, ni
un `ClassNotFoundException` propio, inyección de dependencias arriba y proceso vivo.

El aviso del Panel se vio funcionando con el token sin configurar, que es exactamente para lo que
se escribió: «No se puede entregar nada: no hay ningún token de bot configurado en Ajustes».

#### Dos cosas que solo se aprenden instalando

1. **`INSTALL_FAILED_USER_RESTRICTED`.** MIUI y HyperOS bloquean la instalación por `adb` hasta
   que se activa *Instalar vía USB* —y en HyperOS además *Depuración USB (ajustes de seguridad)*,
   que pide cuenta Mi iniciada—. No se puede esquivar desde `adb`: lo impone su PackageManager, y
   `pm install` desde el propio dispositivo falla igual. La alternativa cuando no se quiere pelear
   es copiar el APK al teléfono e instalarlo tocándolo desde el explorador de archivos.
2. **`versionCode` sube a 2 con la 1.0.0.** La 0.1.0 ya estaba instalada con `versionCode 1`, y
   Android rechaza un APK cuyo `versionCode` no sea mayor que el instalado. Que una versión 1.0.0
   lleve el código 2 es normal y es justo lo que la nota de la sección «Releases» de `CLAUDE.md`
   advierte.

---

## 4. Riesgos propios de entregar por Telegram

| Riesgo | Mitigación |
|--------|------------|
| El token se filtra en una copia de seguridad o un log | No viaja en el fichero (fase 4) y no se registra nunca |
| Telegram inalcanzable (red, bloqueo, caída) | `ErrorTransitorio` + backoff + rescate de huérfanos: el outbox no pierde nada |
| Token revocado o bot borrado | Fallo de configuración visible en el Panel, no cuatrocientos errores iguales en el log |
| El destinatario bloquea el bot | `403` → `ErrorPermanente`, se registra y no se insiste |
| Contenido de terceros enviado a un tercero | Aviso de privacidad reescrito; es responsabilidad de quien instala |
