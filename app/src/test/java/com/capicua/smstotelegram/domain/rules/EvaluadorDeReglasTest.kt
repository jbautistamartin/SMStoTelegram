// SMStoTelegram para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstotelegram.domain.rules

import com.capicua.smstotelegram.domain.model.Regla
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del motor de reglas.
 *
 * [EvaluadorDeReglas] es Kotlin puro, así que se instancia directamente: no hace falta
 * Robolectric, ni Room en memoria, ni dispatchers de prueba.
 */
class EvaluadorDeReglasTest {

    private val evaluador = EvaluadorDeReglas()

    private val fecha = "07/10/2026 19:30:00"

    private fun regla(
        id: Long = 1,
        orden: Int = 0,
        nombre: String = "Regla $id",
        regexTelefono: String? = null,
        regexMensaje: String? = null,
        ignorarMayusculas: Boolean = false,
        destino: String = "+34600000001",
        plantilla: String = Regla.PLANTILLA_POR_DEFECTO,
        activa: Boolean = true,
        continuar: Boolean = false
    ) = Regla(
        id = id,
        orden = orden,
        nombre = nombre,
        regexTelefono = regexTelefono,
        regexMensaje = regexMensaje,
        ignorarMayusculas = ignorarMayusculas,
        destino = destino,
        plantilla = plantilla,
        activa = activa,
        continuar = continuar
    )

    private fun evaluar(
        reglas: List<Regla>,
        telefono: String = "+34611111111",
        mensaje: String = "mensaje de prueba"
    ) = evaluador.evaluar(telefono, mensaje, fecha, reglas)

    // ── Activación y orden ────────────────────────────────────────────────────

    @Test
    fun `una regla desactivada no se evalua`() {
        val resultado = evaluar(listOf(regla(activa = false)))

        assertFalse(resultado.hayCoincidencias)
        assertTrue(resultado.reglasInvalidas.isEmpty())
    }

    @Test
    fun `gana la primera regla por orden y la evaluacion se detiene`() {
        val resultado = evaluar(
            listOf(
                regla(id = 1, orden = 0, destino = "+34600000001"),
                regla(id = 2, orden = 1, destino = "+34600000002")
            )
        )

        assertEquals(1, resultado.coincidencias.size)
        assertEquals("+34600000001", resultado.coincidencias.first().destino)
        assertEquals(1L, resultado.coincidencias.first().reglaId)
    }

    @Test
    fun `el orden de entrada de la lista es irrelevante, manda el campo orden`() {
        val resultado = evaluar(
            listOf(
                regla(id = 2, orden = 5, destino = "+34600000002"),
                regla(id = 1, orden = 1, destino = "+34600000001")
            )
        )

        assertEquals("+34600000001", resultado.coincidencias.single().destino)
    }

    // ── continuar ─────────────────────────────────────────────────────────────

    @Test
    fun `continuar permite que un SMS se reenvie a varios destinos`() {
        val resultado = evaluar(
            listOf(
                regla(id = 1, orden = 0, destino = "+34600000001", continuar = true),
                regla(id = 2, orden = 1, destino = "+34600000002", continuar = true),
                regla(id = 3, orden = 2, destino = "+34600000003")
            )
        )

        assertEquals(
            listOf("+34600000001", "+34600000002", "+34600000003"),
            resultado.coincidencias.map { it.destino }
        )
    }

    @Test
    fun `continuar en la ultima regla no provoca error`() {
        val resultado = evaluar(listOf(regla(continuar = true)))

        assertEquals(1, resultado.coincidencias.size)
    }

    @Test
    fun `una regla con continuar que no casa no interrumpe la cadena`() {
        val resultado = evaluar(
            listOf(
                regla(id = 1, orden = 0, regexMensaje = "no-aparece", continuar = true),
                regla(id = 2, orden = 1, destino = "+34600000002")
            )
        )

        assertEquals("+34600000002", resultado.coincidencias.single().destino)
    }

    // ── Sin coincidencias ─────────────────────────────────────────────────────

    @Test
    fun `sin reglas el resultado esta vacio`() {
        val resultado = evaluar(emptyList())

        assertFalse(resultado.hayCoincidencias)
    }

    @Test
    fun `si ninguna regla casa el resultado esta vacio`() {
        val resultado = evaluar(listOf(regla(regexMensaje = "factura")), mensaje = "tu codigo es 4821")

        assertFalse(resultado.hayCoincidencias)
    }

    // ── Expresiones inválidas ─────────────────────────────────────────────────

    @Test
    fun `una regex invalida descarta solo esa regla y las demas siguen evaluandose`() {
        val resultado = evaluar(
            listOf(
                regla(id = 1, orden = 0, regexMensaje = "[a-", destino = "+34600000001"),
                regla(id = 2, orden = 1, destino = "+34600000002")
            )
        )

        assertEquals("+34600000002", resultado.coincidencias.single().destino)
        assertEquals(1, resultado.reglasInvalidas.size)
    }

    @Test
    fun `una regex invalida se reporta con su campo, su patron y un motivo`() {
        val resultado = evaluar(listOf(regla(id = 7, nombre = "Banco", regexTelefono = "(sin cerrar")))

        val invalida = resultado.reglasInvalidas.single()
        assertEquals(7L, invalida.reglaId)
        assertEquals("Banco", invalida.nombreRegla)
        assertEquals(CampoRegla.TELEFONO, invalida.campo)
        assertEquals("(sin cerrar", invalida.patron)
        assertTrue(invalida.motivo.isNotBlank())
    }

    @Test
    fun `si los dos patrones son invalidos se reportan los dos`() {
        val resultado = evaluar(listOf(regla(regexTelefono = "[a-", regexMensaje = "(b")))

        assertEquals(2, resultado.reglasInvalidas.size)
        assertEquals(
            setOf(CampoRegla.TELEFONO, CampoRegla.MENSAJE),
            resultado.reglasInvalidas.map { it.campo }.toSet()
        )
    }

    @Test
    fun `una regex invalida nunca produce una coincidencia`() {
        val resultado = evaluar(listOf(regla(regexMensaje = "[a-")))

        assertFalse(resultado.hayCoincidencias)
    }

    // ── Semántica de coincidencia parcial ─────────────────────────────────────

    @Test
    fun `la coincidencia es parcial, no hace falta describir el mensaje entero`() {
        val resultado = evaluar(
            listOf(regla(regexMensaje = "codigo")),
            mensaje = "Tu codigo es 4821"
        )

        assertTrue(resultado.hayCoincidencias)
    }

    @Test
    fun `un patron anclado si exige el texto completo`() {
        val reglas = listOf(regla(regexMensaje = "^codigo$"))

        assertFalse(evaluar(reglas, mensaje = "Tu codigo es 4821").hayCoincidencias)
        assertTrue(evaluar(reglas, mensaje = "codigo").hayCoincidencias)
    }

    @Test
    fun `con el ajuste apagado se distinguen mayusculas y minusculas`() {
        val resultado = evaluar(listOf(regla(regexMensaje = "CODIGO")), mensaje = "tu codigo es 4821")

        assertFalse(resultado.hayCoincidencias)
    }

    @Test
    fun `el modificador i ignora mayusculas y minusculas`() {
        val resultado = evaluar(
            listOf(regla(regexMensaje = "(?i)CODIGO")),
            mensaje = "tu codigo es 4821"
        )

        assertTrue(resultado.hayCoincidencias)
    }

    // ── Criterios ausentes ────────────────────────────────────────────────────

    @Test
    fun `una regla sin patrones casa con cualquier SMS`() {
        val resultado = evaluar(
            listOf(regla()),
            telefono = "cualquiera",
            mensaje = "lo que sea"
        )

        assertTrue(resultado.hayCoincidencias)
    }

    @Test
    fun `un patron en blanco equivale a no filtrar`() {
        val resultado = evaluar(listOf(regla(regexTelefono = "   ", regexMensaje = "")))

        assertTrue(resultado.hayCoincidencias)
        assertTrue(resultado.reglasInvalidas.isEmpty())
    }

    @Test
    fun `filtrar solo por telefono deja el mensaje libre`() {
        val reglas = listOf(regla(regexTelefono = "^\\+34600"))

        assertTrue(evaluar(reglas, telefono = "+34600123456", mensaje = "cualquier cosa").hayCoincidencias)
        assertFalse(evaluar(reglas, telefono = "+34700123456", mensaje = "cualquier cosa").hayCoincidencias)
    }

    @Test
    fun `filtrar solo por mensaje deja el telefono libre`() {
        val reglas = listOf(regla(regexMensaje = "factura"))

        assertTrue(evaluar(reglas, telefono = "+34999999999", mensaje = "tu factura ya esta").hayCoincidencias)
        assertFalse(evaluar(reglas, telefono = "+34999999999", mensaje = "hola").hayCoincidencias)
    }

    @Test
    fun `los dos criterios deben cumplirse a la vez`() {
        val reglas = listOf(regla(regexTelefono = "BANCO", regexMensaje = "codigo"))

        assertTrue(evaluar(reglas, telefono = "BANCO", mensaje = "tu codigo es 1234").hayCoincidencias)
        assertFalse(evaluar(reglas, telefono = "BANCO", mensaje = "tu factura").hayCoincidencias)
        assertFalse(evaluar(reglas, telefono = "+34600000000", mensaje = "tu codigo es 1234").hayCoincidencias)
    }

    @Test
    fun `un remitente alfanumerico se trata como texto, sin normalizar`() {
        val resultado = evaluar(
            listOf(regla(regexTelefono = "^BANCO$")),
            telefono = "BANCO"
        )

        assertTrue(resultado.hayCoincidencias)
    }

    // ── Plantillas ────────────────────────────────────────────────────────────

    /**
     * La plantilla por defecto dejó de ser `{mensaje}` al cambiar el medio.
     *
     * Con el SMS bastaba el cuerpo, porque el reenvío llegaba como un SMS más y el teléfono ya
     * enseñaba de quién era. Por Telegram todo llega del mismo bot: cuatro avisos seguidos
     * serían cuatro mensajes indistinguibles. El remitente y la fecha delante son el contexto
     * que el SMS daba gratis y que ahora hay que poner a mano.
     */
    @Test
    fun `la plantilla por defecto lleva remitente y fecha delante del mensaje`() {
        val resultado = evaluar(
            listOf(regla()),
            telefono = "BANCO",
            mensaje = "Tu codigo es 4821"
        )

        assertEquals(
            "BANCO · 07/10/2026 19:30:00\nTu codigo es 4821",
            resultado.coincidencias.single().textoFinal
        )
    }

    @Test
    fun `la plantilla sustituye los tres marcadores`() {
        val resultado = evaluar(
            listOf(regla(plantilla = "De {telefono} el {fecha}: {mensaje}")),
            telefono = "+34611111111",
            mensaje = "Tu codigo es 4821"
        )

        assertEquals(
            "De +34611111111 el 07/10/2026 19:30:00: Tu codigo es 4821",
            resultado.coincidencias.single().textoFinal
        )
    }

    @Test
    fun `un marcador repetido se sustituye en todas sus apariciones`() {
        val resultado = evaluar(
            listOf(regla(plantilla = "{telefono} / {telefono}")),
            telefono = "+34611111111"
        )

        assertEquals("+34611111111 / +34611111111", resultado.coincidencias.single().textoFinal)
    }

    @Test
    fun `el cuerpo del SMS no puede inyectar marcadores en la plantilla`() {
        // {mensaje} se sustituye en ultimo lugar justo para esto: el contenido ajeno
        // no se vuelve a escanear y los marcadores que traiga quedan literales.
        val resultado = evaluar(
            listOf(regla(plantilla = "De {telefono}: {mensaje}")),
            telefono = "+34611111111",
            mensaje = "hoy es {fecha} y mi numero es {telefono}"
        )

        assertEquals(
            "De +34611111111: hoy es {fecha} y mi numero es {telefono}",
            resultado.coincidencias.single().textoFinal
        )
    }

    @Test
    fun `una plantilla sin marcadores envia un texto fijo`() {
        val resultado = evaluar(listOf(regla(plantilla = "Aviso recibido")))

        assertEquals("Aviso recibido", resultado.coincidencias.single().textoFinal)
    }

    @Test
    fun `cada coincidencia congela su propio destino y texto`() {
        val resultado = evaluar(
            listOf(
                regla(id = 1, orden = 0, destino = "+34600000001", plantilla = "A: {mensaje}", continuar = true),
                regla(id = 2, orden = 1, destino = "+34600000002", plantilla = "B: {mensaje}")
            ),
            mensaje = "hola"
        )

        assertEquals(
            listOf("A: hola", "B: hola"),
            resultado.coincidencias.map { it.textoFinal }
        )
        assertEquals("Regla 1", resultado.coincidencias[0].nombreRegla)
    }

    // ── Validación de patrones para la pantalla de reglas ─────────────────────

    @Test
    fun `validarPatron devuelve null cuando el patron compila`() {
        assertNull(evaluador.validarPatron("^\\+34[0-9]{9}$"))
    }

    @Test
    fun `validarPatron devuelve null cuando el patron esta vacio`() {
        assertNull(evaluador.validarPatron(null))
        assertNull(evaluador.validarPatron(""))
        assertNull(evaluador.validarPatron("   "))
    }

    @Test
    fun `validarPatron describe el error cuando el patron no compila`() {
        val motivo = evaluador.validarPatron("[a-")

        assertNotNull(motivo)
        assertTrue(motivo!!.isNotBlank())
    }

    // ── Ignorar mayúsculas ───────────────────────────────────────────────────

    @Test
    fun `una regla nueva ignora mayusculas sin que nadie lo pida`() {
        // El valor por defecto del modelo, que es lo que estrena una regla recien creada: el
        // mismo remitente escribe «CODIGO» o «codigo» segun el dia, y una regla que distinga
        // deja de casar sin dar ningun error. Este test existe para que ese valor no se
        // cambie por descuido al tocar el data class.
        val reglaRecienCreada = Regla(
            id = 0,
            orden = 0,
            nombre = "Nueva",
            regexMensaje = "hola",
            destino = "+34600000001"
        )

        assertTrue(reglaRecienCreada.ignorarMayusculas)
        assertTrue(
            evaluar(listOf(reglaRecienCreada), mensaje = "HOLA que tal").hayCoincidencias
        )
    }

    @Test
    fun `con el ajuste apagado las expresiones distinguen mayusculas`() {
        val resultado = evaluar(
            listOf(regla(regexMensaje = "hola")),
            mensaje = "HOLA que tal"
        )

        assertFalse(resultado.hayCoincidencias)
    }

    @Test
    fun `con ignorarMayusculas el patron del mensaje casa en cualquier caja`() {
        val resultado = evaluar(
            listOf(regla(regexMensaje = "hola", ignorarMayusculas = true)),
            mensaje = "HOLA que tal"
        )

        assertTrue(resultado.hayCoincidencias)
    }

    @Test
    fun `ignorarMayusculas se aplica tambien al patron del remitente`() {
        val resultado = evaluar(
            listOf(regla(regexTelefono = "banco", ignorarMayusculas = true)),
            telefono = "BANCO"
        )

        assertTrue(resultado.hayCoincidencias)
    }

    @Test
    fun `ignorarMayusculas afecta a los dos criterios a la vez`() {
        val resultado = evaluar(
            listOf(
                regla(regexTelefono = "banco", regexMensaje = "codigo", ignorarMayusculas = true)
            ),
            telefono = "BANCO",
            mensaje = "Tu CODIGO es 4821"
        )

        assertTrue(resultado.hayCoincidencias)
    }

    @Test
    fun `ignorarMayusculas no afecta a una regla que no lo activa`() {
        // Dos reglas con el mismo patrón: solo casa la que lo pide. Confirma que la opción
        // es por regla y no un ajuste global del evaluador.
        val resultado = evaluar(
            listOf(
                regla(id = 1, orden = 0, regexMensaje = "hola", ignorarMayusculas = false),
                regla(id = 2, orden = 1, regexMensaje = "hola", ignorarMayusculas = true)
            ),
            mensaje = "HOLA"
        )

        assertEquals("Regla 2", resultado.coincidencias.single().nombreRegla)
    }

    @Test
    fun `ignorarMayusculas convive con el modificador (?i) escrito a mano`() {
        val resultado = evaluar(
            listOf(regla(regexMensaje = "(?i)hola", ignorarMayusculas = true)),
            mensaje = "HoLa"
        )

        assertTrue(resultado.hayCoincidencias)
    }

    @Test
    fun `ignorarMayusculas no convierte un patron roto en valido`() {
        val resultado = evaluar(listOf(regla(regexMensaje = "[a-", ignorarMayusculas = true)))

        assertFalse(resultado.hayCoincidencias)
        assertEquals(1, resultado.reglasInvalidas.size)
    }

    // ── Destino: se usa tal cual ─────────────────────────────────────────────

    /**
     * Esta sección sustituye a la que probaba `{telefono}` como destino.
     *
     * Con el SMS, escribir `{telefono}` en el destino significaba «contesta a quien me
     * escribió». Con Telegram no hay forma de convertir el remitente de un SMS en un chat, y la
     * consecuencia directa es que **en el destino no se resuelve ningún marcador**. Lo que estos
     * tests fijan es justamente eso, porque es una propiedad de seguridad y no solo una
     * simplificación: si algún día se resolviera `{mensaje}` ahí, el cuerpo de un SMS ajeno
     * decidiría a dónde se entrega.
     */
    @Test
    fun `el destino se usa tal cual, sin resolver marcadores`() {
        val coincidencia = evaluar(
            listOf(regla(destino = "-1001234567890")),
            telefono = "+34611111111"
        ).coincidencias.single()

        assertEquals("-1001234567890", coincidencia.destino)
    }

    @Test
    fun `el marcador de telefono en el destino no se sustituye`() {
        val coincidencia = evaluar(
            listOf(regla(destino = "{telefono}")),
            telefono = "+34611111111"
        ).coincidencias.single()

        assertEquals("{telefono}", coincidencia.destino)
    }

    @Test
    fun `el cuerpo del SMS no puede influir en el destino`() {
        // Si {mensaje} se resolviera aquí, un tercero decidiria a donde se entrega su propio
        // mensaje escribiendo un chat_id en el texto del SMS.
        val coincidencia = evaluar(
            listOf(regla(destino = "{mensaje}")),
            mensaje = "-1009999999999"
        ).coincidencias.single()

        assertEquals("{mensaje}", coincidencia.destino)
    }

    @Test
    fun `el destino se recorta`() {
        val coincidencia = evaluar(listOf(regla(destino = "  123456789  ")))
            .coincidencias.single()

        assertEquals("123456789", coincidencia.destino)
    }

    // ── resolverPlantilla como API pública ────────────────────────────────────

    @Test
    fun `resolverPlantilla es utilizable sin evaluar reglas`() {
        val texto = evaluador.resolverPlantilla(
            plantilla = "{telefono} dice {mensaje}",
            telefono = "+34600000000",
            mensaje = "hola",
            fecha = fecha
        )

        assertEquals("+34600000000 dice hola", texto)
    }
}
