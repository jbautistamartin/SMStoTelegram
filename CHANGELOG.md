# Registro de cambios

Todas las versiones publicadas de **SMStoTelegram**, con lo que cambió en cada una.
El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y las versiones,
[SemVer](https://semver.org/lang/es/).

> **SMStoTelegram nace de [SMStoSMS](https://github.com/jbautistamartin/SMStoSMS)**, que recibe
> SMS y los reenvía como SMS a otro teléfono. Esta aplicación hace lo mismo con la entrada y
> cambia la salida: entrega en Telegram. Son **aplicaciones distintas**, con `applicationId`
> propio y base de datos propia, y pueden convivir en el mismo dispositivo. Por eso este registro
> empieza de cero en lugar de continuar el de aquella.

---

## [1.2.0] - 2026-10-18

### Añadido

- **La copia de seguridad puede llevar el token del bot, si se pide.** Una casilla nueva en
  Ajustes → Copia de seguridad, **apagada por defecto**, decide si el token viaja dentro del
  fichero. Sin marcarla, el comportamiento es el de siempre y las copias antiguas se siguen
  importando igual.

  Hasta ahora el token nunca viajaba, y eso hacía que una copia no sirviese para lo que más
  falta hace: restaurar un teléfono entero o clonar la configuración en otro. El precio es que
  el fichero pasa a ser un secreto, así que la casilla enseña un aviso en rojo mientras está
  encendida, y el mensaje de confirmación al terminar también lo dice —entre marcarla y elegir
  carpeta se pasa por el selector del sistema, que es donde se decide dónde acaba el fichero—.

  La casilla **no se recuerda**: vuelve a estar apagada cada vez que se entra en Ajustes. No es
  un ajuste de la aplicación, es una decisión sobre esa exportación concreta, y dejarla
  encendida de una vez para otra acabaría escribiendo el token en un fichero que alguien creía
  inocuo.

  Al importar, un token presente en el fichero sustituye al del teléfono y se avisa de ello; uno
  ausente o en blanco deja el que hubiera, porque cambiar un token que funciona por nada deja la
  aplicación muda sin rastro de por qué.

### Documentación

- **`CLAUDE.md` pasa íntegramente al español**, igual que el resto de la documentación del
  proyecto y que los comentarios del código. Antes mezclaba estructura y encabezados en inglés
  con las explicaciones largas en español.

## [1.1.0] - 2026-10-17

### Añadido

- **Copiar una entrada del registro con una pulsación larga.** Copia la misma línea que escribe
  el fichero exportado —fecha, tipo y detalle—, para pegarla en un correo o en un parte de
  incidencia sin tener que exportar el registro entero. El aviso se enseña hasta Android 12;
  desde Android 13 lo saca el propio sistema, con vista previa de lo copiado, y poner otro encima
  serían dos avisos de lo mismo.

### Cambiado

- **«Ignorar mayúsculas» viene activado en las reglas nuevas.** Es lo contrario de lo que parece
  prudente y es lo que pide la práctica: el mismo remitente escribe «CODIGO», «Codigo» o «codigo»
  según el operador y el día, y una regla que distinga deja de casar sin dar ningún error —no
  falla, simplemente no reenvía—. Distinguir mayúsculas pasa a ser la excepción, y se pide
  apagando el interruptor que ya existía en la pantalla de la regla.

  **Las reglas ya guardadas no cambian.** Conservan el criterio que tuvieran: cambiárselo por
  detrás alteraría en silencio lo que casan en un teléfono que ya está funcionando. Esto solo
  afecta a las reglas que se creen a partir de ahora.

- **Orden común en «Configuración».** Las cuatro aplicaciones de la familia presentan ahora las
  secciones en el mismo orden: **Recepción de SMS**, **Fiabilidad en segundo plano**, la sección
  propia del destino, **Límites**, **Envío y reintentos** y **Copia de seguridad**. Quien salta de
  una a otra encuentra lo mismo en el mismo sitio.

- **La sección del bot ya no va primera**, sino tercera, donde las otras tres ponen su destino.
  Que sea el único ajuste que una instalación nueva trae vacío lo siguen diciendo el subtítulo de
  la pantalla y el aviso del panel.

---

## [1.0.0] — 2026-10-12

Primera versión publicada. Es la aplicación completa, no un adelanto: lo que la numera como 1.0.0
y no como 0.x es que la cadena entera —recibir, evaluar, entregar, reintentar, rescatar lo
atascado y purgar— está terminada y verificada en un dispositivo real.

### Añadido

- **Entrega por Telegram.** Los SMS recibidos se entregan como mensajes de Telegram a través de un
  bot propio, a una persona, un grupo o un canal.
- **Token del bot configurable en Ajustes**, con un botón que lo comprueba contra Telegram
  (`getMe`) y dice **de qué bot se trata**, no solo si vale. El token se enmascara en pantalla,
  no se escribe nunca en el registro y **no viaja en el fichero de copia de seguridad**.
- **Descubrimiento de chats en la pantalla de reglas.** Un botón pregunta al bot quién le ha
  escrito y ofrece la lista para elegir el destino, porque nadie se sabe su `chat_id`. Si la lista
  vuelve vacía, la aplicación explica que un bot no puede escribir primero y que hace falta un
  `/start` del otro lado.
- **Aviso en el Panel cuando no se puede entregar nada**: sin token configurado, o con un token
  que Telegram ha rechazado. Es el único fallo que afecta a todos los reenvíos por igual, y en el
  registro serían cientos de líneas idénticas de las que solo la primera informa.
- **Restricción de red en la cola de envíos.** Un reenvío encolado sin conexión espera a que
  vuelva en lugar de gastar sus diez intentos contra una red que no está.
- **Se respeta el `retry_after` de Telegram.** Un `429` dice en la respuesta cuánto hay que
  esperar, y se espera.

### Cambiado

- **El destino de una regla es un chat de Telegram**, no un número de teléfono: un `chat_id`
  —negativo si es un grupo o un canal— o un `@usuario` de canal público.
- **La plantilla por defecto pasa de `{mensaje}` a `{telefono} · {fecha}` y el mensaje debajo.**
  Por Telegram todos los avisos llegan del mismo bot: sin el remitente en el texto, cuatro
  mensajes seguidos son indistinguibles. El contexto que el SMS daba gratis hay que ponerlo.
- **El límite de reenvíos por minuto sigue, por otro motivo.** Era un cortafuegos económico;
  entregar por Telegram es gratis, así que ahora protege de los límites del Bot API: un mensaje
  por segundo y chat, y unos 20 por minuto en un grupo.
- **Icono y color propios.** La aplicación tenía el icono de SMStoSMS, y como las dos pueden
  convivir en el mismo teléfono eran indistinguibles en el lanzador. El bocadillo se mantiene
  —la entrada sigue siendo un SMS— y lo que cambia es lo de dentro: donde SMStoSMS tenía una
  flecha de reenvío, aquí hay un avión de papel calado. El color primario pasa del verde azulado
  al azul, que es de donde sale el fondo del icono.
- **El aviso de privacidad se ha reescrito entero.** El contenido de los SMS sale a internet y
  llega a los servidores de Telegram. Es el cambio más importante de esta aplicación respecto a
  SMStoSMS para quien la instala.
- Un mensaje admite **4.096 caracteres** en lugar de 160, y no se fragmenta salvo que se pase de
  ahí. El contador de la pantalla de reglas cuenta caracteres, no SMS.

### Eliminado

- **El permiso `SEND_SMS`**, que era un permiso restringido, y **`READ_PHONE_STATE`**, que solo
  servía para elegir SIM. Esta aplicación recibe por la radio y entrega por internet.
- **El selector de SIM de envío**: ya no hay nada que enviar por una SIM.
- **Las dos protecciones antibucle.** Comparaban el destino de una regla con el remitente del SMS,
  y eso dejó de significar nada: un chat de Telegram y un número de teléfono son identidades sin
  relación, no pueden ser el mismo ni cerrar un círculo. Un mensaje de Telegram no vuelve nunca
  como SMS.
- **El marcador `{telefono}` como destino** y el botón «Contestar al remitente» que lo escribía.
  No hay forma de convertir el remitente de un SMS en un chat de Telegram. El marcador sigue en
  la plantilla, donde ahora importa más que antes.
- El recuento de partes de un SMS en la pantalla de reglas y en el historial.

### Importante al venir de SMStoSMS

Un fichero de copia de seguridad de **SMStoSMS no se puede importar aquí**, y la aplicación lo
rechaza explicándolo. El fichero tiene exactamente la misma forma, pero sus destinos son números
de teléfono: entraría sin un solo error y dejaría un juego de reglas cuyos destinos no existen,
fallando uno por uno cuando llegara un SMS. Las reglas hay que crearlas de nuevo con los chats.
