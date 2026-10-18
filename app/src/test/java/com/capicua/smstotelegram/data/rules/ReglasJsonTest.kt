// SMStoTelegram para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstotelegram.data.rules

import com.capicua.smstotelegram.data.config.AppConfig
import com.capicua.smstotelegram.domain.model.Regla
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del formato de intercambio de reglas.
 *
 * Lo que se verifica no es que kotlinx.serialization funcione, sino las decisiones propias del
 * formato: que los ids locales no viajen, que el token del bot tampoco, que el orden relativo
 * se conserve y que un fichero escrito por otra versión no reviente el parseo.
 */
class ReglasJsonTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        // Sin esto, kotlinx.serialization omite los campos cuyo valor coincide con el
        // predeterminado — incluida la versión del formato, que es justo lo que el fichero
        // tiene que declarar para que una importación futura sepa qué está leyendo.
        encodeDefaults = true
    }

    private fun regla(
        id: Long = 42,
        orden: Int = 3,
        nombre: String = "Banco",
        regexTelefono: String? = "^BANCO$",
        regexMensaje: String? = "codigo",
        ignorarMayusculas: Boolean = false,
        destino: String = "+34600112233",
        plantilla: String = "De {telefono}: {mensaje}",
        activa: Boolean = true,
        continuar: Boolean = false
    ) = Regla(
        // Argumentos nombrados a propósito: con posicionales, añadir un campo al modelo
        // compila mal o, peor, compila y cambia de sitio dos valores del mismo tipo.
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

    // ── Ida y vuelta ──────────────────────────────────────────────────────────

    @Test
    fun `una regla sobrevive al viaje de ida y vuelta`() {
        val original = regla()

        val recuperada = original.aJson().aDominio(orden = 0)

        assertEquals(original.nombre, recuperada.nombre)
        assertEquals(original.regexTelefono, recuperada.regexTelefono)
        assertEquals(original.regexMensaje, recuperada.regexMensaje)
        assertEquals(original.destino, recuperada.destino)
        assertEquals(original.plantilla, recuperada.plantilla)
        assertEquals(original.activa, recuperada.activa)
        assertEquals(original.continuar, recuperada.continuar)
    }

    @Test
    fun `el id local no viaja en el fichero`() {
        val serializado = json.encodeToString(ReglaJson.serializer(), regla(id = 42).aJson())

        assertFalse("el id no debe aparecer en el JSON", serializado.contains("\"id\""))
        assertFalse("el orden tampoco: lo decide quien importa", serializado.contains("\"orden\""))
    }

    @Test
    fun `al importar el id queda a cero para que Room asigne uno nuevo`() {
        val importada = regla(id = 42).aJson().aDominio(orden = 7)

        assertEquals(0L, importada.id)
        assertEquals(7, importada.orden)
    }

    // ── Conjunto completo ─────────────────────────────────────────────────────

    @Test
    fun `el orden relativo del fichero se conserva al importar`() {
        val reglas = listOf(
            regla(nombre = "Primera", destino = "+34600000001"),
            regla(nombre = "Segunda", destino = "+34600000002"),
            regla(nombre = "Tercera", destino = "+34600000003")
        )

        val fichero = ReglasExportadas(
            exportado = "2026-10-07T19:30:00Z",
            reglas = reglas.map { it.aJson() }
        )
        val recuperado = json.decodeFromString(
            ReglasExportadas.serializer(),
            json.encodeToString(ReglasExportadas.serializer(), fichero)
        )

        val importadas = recuperado.reglas.mapIndexed { indice, r -> r.aDominio(indice) }

        assertEquals(listOf("Primera", "Segunda", "Tercera"), importadas.map { it.nombre })
        assertEquals(listOf(0, 1, 2), importadas.map { it.orden })
    }

    @Test
    fun `el fichero declara la version del formato`() {
        val serializado = json.encodeToString(
            ReglasExportadas.serializer(),
            ReglasExportadas(exportado = "2026-10-07T19:30:00Z", reglas = emptyList())
        )

        assertTrue(serializado.contains("\"version\""))
        assertEquals(4, ReglasExportadas.VERSION_ACTUAL)
    }

    // ── La configuración viaja con las reglas ─────────────────────────────────

    @Test
    fun `los ajustes sobreviven a la ida y vuelta`() {
        val original = AppConfig(
            tokenBot = "123:no-deberia-viajar",
            maxReintentos = 7,
            intervaloReintentoSegundos = 45,
            timeoutEnvioSegundos = 90,
            maxReenviosPorMinuto = 3,
            recepcionActiva = false
        )

        val recuperado = json.decodeFromString(
            ReglasExportadas.serializer(),
            json.encodeToString(
                ReglasExportadas.serializer(),
                ReglasExportadas(
                    exportado = "2026-10-10T22:00:00Z",
                    configuracion = original.aJson(),
                    reglas = emptyList()
                )
            )
        )

        // El token es una credencial: no viaja en el fichero y al importar se conserva el
        // que tenga el dispositivo de destino.
        val importado = recuperado.configuracion!!.aDominio(tokenActual = "999:conservado")

        assertEquals(original.copy(tokenBot = "999:conservado"), importado)
    }

    /**
     * El token es la única credencial de la aplicación, y este fichero es JSON en claro que se
     * guarda donde el operador diga y se lleva a otro teléfono. Que no viaje no es una
     * preferencia: es lo que impide que una copia de seguridad reparta permiso para escribir en
     * nombre del bot.
     */
    private val conToken =
        AppConfig(tokenBot = "123456789:AAEhBOweik6ad9r_QXZ2Ql3n1xWcCOPvj8s")

    private fun exportar(config: AppConfig, incluirCredenciales: Boolean = false) =
        json.encodeToString(
            ReglasExportadas.serializer(),
            ReglasExportadas(
                exportado = "2026-10-12T20:00:00Z",
                configuracion = config.aJson(incluirCredenciales),
                reglas = listOf(regla().aJson())
            )
        )

    @Test
    fun `por defecto el token del bot no aparece en el fichero exportado`() {
        val serializado = exportar(conToken)

        assertFalse("el token no debe aparecer", serializado.contains("AAEhBOweik"))
        // El campo si sale, con valor null: el fichero dice explicitamente que no lleva
        // secretos en vez de callarselo, que es mas facil de auditar de un vistazo.
        assertTrue("el campo debe estar, a null", serializado.contains("\"token_bot\": null"))
    }

    /**
     * El caso contrario, que es la razon de que exista el parametro: quien lo pide a sabiendas
     * se lleva el token dentro, porque si no una copia no sirve para restaurar un telefono.
     */
    @Test
    fun `si se pide, el token viaja en el fichero`() {
        val serializado = exportar(conToken, incluirCredenciales = true)

        assertTrue("el token debe aparecer", serializado.contains("AAEhBOweik"))
    }

    /**
     * Un fichero sin token no puede dejar mudo al telefono que lo importa.
     *
     * Es el modo de fallo que justifica el parametro de [aDominio]: los ajustes se *reemplazan*
     * al importar, asi que sin respaldo una copia normal borraria el token.
     */
    @Test
    fun `importar sin token conserva el del telefono`() {
        val recuperado = json.decodeFromString(
            ReglasExportadas.serializer(),
            exportar(conToken)
        )

        val importado = recuperado.configuracion!!.aDominio("999:YA-CONFIGURADO")

        assertEquals("999:YA-CONFIGURADO", importado.tokenBot)
    }

    /** Y si el fichero lo trae, gana: es exactamente lo que se pidio al exportarlo. */
    @Test
    fun `importar con token sustituye el del telefono`() {
        val recuperado = json.decodeFromString(
            ReglasExportadas.serializer(),
            exportar(conToken, incluirCredenciales = true)
        )

        val importado = recuperado.configuracion!!.aDominio("999:YA-CONFIGURADO")

        assertEquals(conToken.tokenBot, importado.tokenBot)
    }

    /**
     * Un token en blanco en el fichero cuenta como ausente.
     *
     * Sustituir un token que funciona por una cadena vacia es el peor de los dos resultados
     * posibles: deja la aplicacion muda y sin rastro de por que. Pasa con un fichero exportado
     * desde un telefono a medio configurar, que es un caso corriente.
     */
    @Test
    fun `un token vacio en el fichero no pisa el que ya habia`() {
        val recuperado = json.decodeFromString(
            ReglasExportadas.serializer(),
            exportar(AppConfig(tokenBot = ""), incluirCredenciales = true)
        )

        val importado = recuperado.configuracion!!.aDominio("999:YA-CONFIGURADO")

        assertEquals("999:YA-CONFIGURADO", importado.tokenBot)
    }

    /**
     * Los ficheros de los formatos 1 y 2 solo llevaban reglas. Tienen que seguir entrando, y
     * dejar los ajustes del dispositivo como estén en lugar de pisarlos con valores por
     * defecto.
     */
    @Test
    fun `un fichero sin bloque de configuracion se sigue importando`() {
        val soloReglas = """
            {
              "version": 2,
              "exportado": "2026-10-07T19:30:00Z",
              "reglas": [ { "nombre": "Simple", "destino": "+34600112233" } ]
            }
        """.trimIndent()

        val fichero = json.decodeFromString(ReglasExportadas.serializer(), soloReglas)

        assertNull(fichero.configuracion)
        assertEquals(1, fichero.reglas.size)
    }

    // ── Tolerancia ────────────────────────────────────────────────────────────

    @Test
    fun `un fichero minimo se acepta y los campos opcionales toman su valor por defecto`() {
        val minimo = """
            {
              "exportado": "2026-10-07T19:30:00Z",
              "reglas": [ { "nombre": "Simple", "destino": "+34600112233" } ]
            }
        """.trimIndent()

        val fichero = json.decodeFromString(ReglasExportadas.serializer(), minimo)
        val regla = fichero.reglas.single().aDominio(orden = 0)

        assertEquals(ReglasExportadas.VERSION_ACTUAL, fichero.version)
        assertEquals("Simple", regla.nombre)
        assertEquals(Regla.PLANTILLA_POR_DEFECTO, regla.plantilla)
        assertTrue(regla.activa)
        assertFalse(regla.continuar)
        assertNull(regla.regexTelefono)
        assertNull(regla.regexMensaje)
        assertFalse(regla.ignorarMayusculas)
    }

    @Test
    fun `un fichero del formato 1 se importa y la regla distingue mayusculas`() {
        // Compatibilidad hacia atrás: los ficheros exportados antes de que existiera la
        // opción no la traen, y la regla tiene que entrar con el comportamiento que tenía.
        val formato1 = """
            {
              "version": 1,
              "exportado": "2026-10-07T19:30:00Z",
              "reglas": [
                { "nombre": "Banco", "regex_mensaje": "codigo", "destino": "+34600112233" }
              ]
            }
        """.trimIndent()

        val regla = json.decodeFromString(ReglasExportadas.serializer(), formato1)
            .reglas.single().aDominio(orden = 0)

        assertFalse(regla.ignorarMayusculas)
    }

    @Test
    fun `ignorar_mayusculas sobrevive a exportar e importar`() {
        val original = Regla(
            orden = 0,
            nombre = "Sin cajas",
            regexMensaje = "hola",
            ignorarMayusculas = true,
            destino = "+34600112233"
        )

        val recuperada = json.decodeFromString(
            ReglasExportadas.serializer(),
            json.encodeToString(
                ReglasExportadas.serializer(),
                ReglasExportadas(exportado = "2026-10-08T20:00:00Z", reglas = listOf(original.aJson()))
            )
        ).reglas.single().aDominio(orden = 0)

        assertTrue(recuperada.ignorarMayusculas)
    }

    @Test
    fun `se ignoran los campos desconocidos de una version futura`() {
        val conExtras = """
            {
              "version": 1,
              "exportado": "2026-10-07T19:30:00Z",
              "inventado": "algo",
              "reglas": [
                { "nombre": "Simple", "destino": "+34600112233", "otro_campo": 123 }
              ]
            }
        """.trimIndent()

        val fichero = json.decodeFromString(ReglasExportadas.serializer(), conExtras)

        assertEquals(1, fichero.reglas.size)
    }

    @Test
    fun `los patrones en blanco se normalizan a null al importar`() {
        val conBlancos = ReglaJson(
            nombre = "Blancos",
            regexTelefono = "   ",
            regexMensaje = "",
            destino = "+34600112233",
            plantilla = "  "
        )

        val regla = conBlancos.aDominio(orden = 0)

        assertNull(regla.regexTelefono)
        assertNull(regla.regexMensaje)
        assertEquals(Regla.PLANTILLA_POR_DEFECTO, regla.plantilla)
    }
}
