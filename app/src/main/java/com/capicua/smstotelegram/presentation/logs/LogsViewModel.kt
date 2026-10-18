// SMStoTelegram para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstotelegram.presentation.logs

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capicua.smstotelegram.data.repository.LogRepository
import com.capicua.smstotelegram.domain.model.LogEntry
import com.capicua.smstotelegram.domain.model.LogTipo
import com.capicua.smstotelegram.util.Constants
import com.capicua.smstotelegram.util.toDisplayString
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class LogsViewModel @Inject constructor(
    application: Application,
    private val logRepository: LogRepository
) : AndroidViewModel(application) {

    /** Filtro activo: null = todos los tipos */
    private val _filtroActivo = MutableStateFlow<LogTipo?>(null)
    val filtroActivo: StateFlow<LogTipo?> = _filtroActivo

    /** Lista de logs según el filtro activo */
    val logs: Flow<List<LogEntry>> = _filtroActivo.flatMapLatest { tipo ->
        if (tipo == null) {
            logRepository.observarRecientes(500)
        } else {
            logRepository.observarPorTipo(tipo, 500)
        }
    }

    val totalLogs: Flow<Int> = logRepository.observarTotal()

    fun aplicarFiltro(tipo: LogTipo?) {
        _filtroActivo.value = tipo
    }

    fun limpiarTodos() {
        viewModelScope.launch {
            logRepository.limpiarTodo()
        }
    }

    /**
     * Exporta todos los logs a un archivo de texto en la caché de la app.
     * Devuelve un [Uri] compatible con FileProvider para compartir.
     * Usa Application context para evitar referencias a Activities o Fragments destruidos.
     */
    fun exportarLogs(onUri: (Uri) -> Unit) {
        viewModelScope.launch {
            try {
                val logs = logRepository.obtenerTodosParaExportar()
                val uri = generarArchivo(logs)
                onUri(uri)
            } catch (e: Exception) {
                Timber.e(e, "Error exportando logs")
            }
        }
    }

    /**
     * Una entrada como una sola línea de texto: la que se escribe en el fichero exportado y la
     * que se copia al portapapeles con una pulsación larga.
     *
     * Es la **misma** función para los dos a propósito. Cuando eran dos trozos de código
     * parecidos, lo copiado y lo exportado se parecían sin llegar a coincidir, y eso convierte
     * en inútil el gesto de pegar una línea suelta en el mismo sitio donde está el fichero.
     */
    fun comoLinea(log: LogEntry): String = buildString {
        append("[${FORMATO_FECHA.format(log.timestamp)}]")
        append(" [${log.tipo.name.padEnd(14)}]")
        log.smsId?.let { append(" [SMS: ${it.take(8)}…]") }
        log.destino?.let { append(" [→ $it]") }
        append(" ${log.detalle}")
    }

    private fun generarArchivo(logs: List<LogEntry>): Uri {
        val context = getApplication<Application>()
        val contenido = buildString {
            appendLine("=== SMStoTelegram — Registro exportado ===")
            appendLine("Fecha exportación: ${FORMATO_FECHA.format(Instant.now())}")
            appendLine("Total entradas: ${logs.size}")
            appendLine("=".repeat(50))
            appendLine()
            logs.forEach { log -> appendLine(comoLinea(log)) }
        }

        val nombreArchivo = "smstotelegram_logs_${System.currentTimeMillis()}.txt"
        val archivo = File(context.cacheDir, nombreArchivo)
        archivo.writeText(contenido, Charsets.UTF_8)

        return FileProvider.getUriForFile(
            context,
            Constants.FILE_PROVIDER_AUTHORITY,
            archivo
        )
    }

    private companion object {
        /** Fecha legible y ordenable, en la zona del teléfono: quien lee el registro está aquí. */
        val FORMATO_FECHA: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
    }
}
