// SMStoTelegram para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstotelegram.presentation.settings

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.capicua.smstotelegram.R
import com.capicua.smstotelegram.data.config.AppConfig
import com.capicua.smstotelegram.data.config.ResultadoCopia
import com.capicua.smstotelegram.data.telegram.ResultadoDelBot
import com.capicua.smstotelegram.data.telegram.TokenDeBot
import com.capicua.smstotelegram.databinding.FragmentSettingsBinding
import com.capicua.smstotelegram.util.InicioAutomatico
import com.capicua.smstotelegram.util.OptimizacionBateria
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Ajustes generales: exención de batería, protecciones y política de reintentos.
 *
 * Las reglas de reenvío no se editan aquí: son una lista ordenada y tienen su propia pantalla.
 */
@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    private var cargaInicial = true

    // ── Selectores de fichero del sistema ─────────────────────────────────────
    //
    // La copia se escribe y se lee sobre el fichero que elige el operador, así que no hace
    // falta ningún permiso de almacenamiento.

    private val crearFichero = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        // La casilla se lee aqui y no cuando se pulso el boton: entre una cosa y otra esta el
        // selector de ficheros del sistema, y lo que vale es lo que este marcado al confirmar.
        uri?.let { viewModel.exportar(it, binding.switchIncluirCredenciales.isChecked) }
    }

    private val abrirFichero = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.importar(it) } }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        configurarBateria()
        configurarAutoinicio()
        configurarCopia()
        configurarComprobacionDelBot()
        observarConfiguracion()
        observarComprobacionDelBot()
        observarCopias()
        configurarBotonGuardar()
    }

    /**
     * El estado de la exención se refresca al volver a la pantalla porque se concede **fuera**
     * de la aplicación: el usuario sale a Ajustes del sistema y vuelve, y lo que tiene que ver
     * al volver es el estado nuevo, no el que había cuando se fue.
     */
    override fun onResume() {
        super.onResume()
        pintarEstadoBateria()
    }

    // ── Comprobación del bot ──────────────────────────────────────────────────

    private fun configurarComprobacionDelBot() {
        binding.buttonValidarBot.setOnClickListener {
            // Se comprueba lo que hay escrito, no lo guardado: la gracia es saberlo antes de
            // guardar un token que no sirve.
            viewModel.comprobarBot(binding.editTextTokenBot.text?.toString().orEmpty())
        }
    }

    private fun observarComprobacionDelBot() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.comprobacion.collect { estado ->
                    val vista = binding.textViewResultadoBot
                    binding.buttonValidarBot.isEnabled =
                        estado != SettingsViewModel.EstadoComprobacion.Comprobando

                    when (estado) {
                        SettingsViewModel.EstadoComprobacion.Inactivo ->
                            vista.visibility = View.GONE

                        SettingsViewModel.EstadoComprobacion.Comprobando -> {
                            vista.visibility = View.VISIBLE
                            vista.setText(R.string.settings_bot_comprobando)
                            vista.setTextColor(colorDe(com.google.android.material.R.attr.colorOnSurfaceVariant))
                        }

                        is SettingsViewModel.EstadoComprobacion.Terminada -> {
                            vista.visibility = View.VISIBLE
                            pintarResultadoDelBot(vista, estado.resultado)
                        }
                    }
                }
            }
        }
    }

    private fun pintarResultadoDelBot(vista: android.widget.TextView, resultado: ResultadoDelBot) {
        when (resultado) {
            is ResultadoDelBot.Correcto -> {
                // Enseñar de qué bot se trata es lo que hace la respuesta comprobable: quien
                // la pidió puede reconocer su bot, o ver que ha pegado el de otro.
                vista.text = getString(
                    R.string.settings_bot_correcto, resultado.nombre, resultado.usuario
                )
                vista.setTextColor(resources.getColor(R.color.status_delivered, null))
            }

            is ResultadoDelBot.Rechazado -> {
                vista.text = getString(R.string.settings_bot_rechazado, resultado.motivo)
                vista.setTextColor(resources.getColor(R.color.status_failed, null))
            }

            is ResultadoDelBot.NoSePudo -> {
                // Sin red no se sabe si el token vale: decir que está mal sería mentir.
                vista.text = getString(R.string.settings_bot_sin_red, resultado.motivo)
                vista.setTextColor(resources.getColor(R.color.status_pending, null))
            }

            ResultadoDelBot.SinToken -> {
                vista.setText(R.string.settings_bot_sin_token)
                vista.setTextColor(colorDe(com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
        }
    }

    private fun colorDe(atributo: Int): Int {
        val valor = android.util.TypedValue()
        requireContext().theme.resolveAttribute(atributo, valor, true)
        return valor.data
    }

    // ── Exención de batería ───────────────────────────────────────────────────

    private fun configurarBateria() {
        binding.buttonBateria.setOnClickListener {
            if (!OptimizacionBateria.abrirAjuste(requireContext())) {
                Snackbar.make(
                    binding.root,
                    R.string.settings_bateria_sin_pantalla,
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Pinta el estado actual de la exención.
     *
     * Cuando ya está concedida el botón se queda, pero como acción secundaria: sirve para
     * revisarlo, y algunos fabricantes revocan la exención por su cuenta tras una actualización.
     */
    private fun pintarEstadoBateria() {
        val exenta = OptimizacionBateria.estaExenta(requireContext())

        binding.textViewBateriaEstado.setText(
            if (exenta) R.string.settings_bateria_ok else R.string.settings_bateria_pendiente
        )
        binding.textViewBateriaEstado.setTextColor(
            resources.getColor(
                if (exenta) R.color.status_delivered else R.color.status_pending,
                null
            )
        )
        binding.textViewBateriaDetalle.setText(
            if (exenta) {
                R.string.settings_bateria_ok_detalle
            } else {
                R.string.settings_bateria_pendiente_detalle
            }
        )
        binding.buttonBateria.setText(
            if (exenta) R.string.settings_bateria_revisar else R.string.settings_bateria_activar
        )
    }

    // ── Inicio automático ─────────────────────────────────────────────────────

    /**
     * No hay estado que pintar: el permiso de inicio automático de los fabricantes no se puede
     * consultar desde la aplicación. Solo queda llevar al operador a la pantalla correcta.
     */
    private fun configurarAutoinicio() {
        binding.buttonAutoinicio.setOnClickListener {
            if (!InicioAutomatico.abrirAjuste(requireContext())) {
                Snackbar.make(
                    binding.root,
                    R.string.settings_autoinicio_sin_pantalla,
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    // ── Copia de seguridad ────────────────────────────────────────────────────

    private fun configurarCopia() {
        // El aviso solo se ensena con la casilla encendida: un texto en rojo permanente
        // sobre algo que no esta ocurriendo se deja de leer, y entonces tampoco se lee cuando si.
        binding.switchIncluirCredenciales.setOnCheckedChangeListener { _, marcado ->
            binding.textCredencialesAviso.isVisible = marcado
        }

        binding.buttonExportar.setOnClickListener {
            crearFichero.launch(viewModel.nombreFicheroCopia())
        }
        // Importar cambia los ajustes y añade reglas: se avisa antes de qué va a pasar, porque
        // desde fuera «importar» puede entenderse como «sustituir».
        binding.buttonImportar.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_importar)
                .setMessage(R.string.settings_importar_confirmar)
                .setNegativeButton(R.string.cancelar, null)
                .setPositiveButton(R.string.settings_importar) { _, _ ->
                    abrirFichero.launch(arrayOf("application/json", "text/plain"))
                }
                .show()
        }
    }

    private fun observarCopias() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.copias.collect { resultado ->
                    val mensaje = when (resultado) {
                        is ResultadoCopia.Exportada -> when {
                            // Que el fichero lleva el token se dice tambien al terminar, no
                            // solo en el aviso de antes: entre marcar la casilla y elegir
                            // donde guardar se pasa por el selector del sistema, y es ahi
                            // donde se decide la carpeta que importa.
                            resultado.conCredenciales -> getString(
                                R.string.settings_copia_exportada_credenciales, resultado.reglas
                            )
                            else -> getString(
                                R.string.settings_copia_exportada, resultado.reglas
                            )
                        }

                        is ResultadoCopia.Importada -> when {
                            resultado.rechazadas > 0 -> getString(
                                R.string.settings_copia_importada_con_rechazos,
                                resultado.aceptadas, resultado.rechazadas
                            )
                            resultado.conCredenciales -> getString(
                                R.string.settings_copia_importada_credenciales,
                                resultado.aceptadas
                            )
                            else -> getString(
                                R.string.settings_copia_importada, resultado.aceptadas
                            )
                        }

                        is ResultadoCopia.Fallo ->
                            getString(R.string.settings_copia_fallo, resultado.motivo)
                    }
                    Snackbar.make(binding.root, mensaje, Snackbar.LENGTH_LONG).show()

                    // Los campos en pantalla son los de antes de importar: hay que repintarlos
                    // con lo que acaba de entrar, o el siguiente «Guardar» desharía la copia.
                    //
                    // No se leen aquí: DataStore emite su valor nuevo cuando termina de
                    // escribir, y en este instante `config` puede seguir siendo el de antes.
                    // Rearmando la carga inicial, el repintado lo hace la emisión que llegue.
                    if (resultado is ResultadoCopia.Importada && resultado.conAjustes) {
                        cargaInicial = true
                    }
                }
            }
        }
    }

    private fun observarConfiguracion() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.config.collect { config ->
                    // config es null mientras DataStore no ha emitido su primer valor real;
                    // esperamos a ese valor antes de rellenar los campos.
                    if (config != null && cargaInicial) {
                        rellenarCampos(config)
                        cargaInicial = false
                    }
                }
            }
        }
    }

    private fun rellenarCampos(config: AppConfig) {
        binding.editTextTokenBot.setText(config.tokenBot)
        binding.switchRecepcion.isChecked = config.recepcionActiva
        binding.editTextMaxReenviosMinuto.setText(config.maxReenviosPorMinuto.toString())
        binding.editTextTimeoutEnvio.setText(config.timeoutEnvioSegundos.toString())
        binding.editTextMaxReintentos.setText(config.maxReintentos.toString())
        binding.editTextIntervalo.setText(config.intervaloReintentoSegundos.toString())
    }

    private fun configurarBotonGuardar() {
        binding.buttonGuardar.setOnClickListener {
            val token = TokenDeBot.normalizar(binding.editTextTokenBot.text?.toString().orEmpty())
            val maxPorMinuto = binding.editTextMaxReenviosMinuto.entero(VALORES.maxReenviosPorMinuto)
            val timeoutEnvio = binding.editTextTimeoutEnvio.entero(VALORES.timeoutEnvioSegundos)
            val maxReintentos = binding.editTextMaxReintentos.entero(VALORES.maxReintentos)
            val intervalo = binding.editTextIntervalo.entero(VALORES.intervaloReintentoSegundos)

            if (!validar(token, maxPorMinuto, timeoutEnvio, maxReintentos, intervalo)) {
                return@setOnClickListener
            }

            viewModel.guardar(
                AppConfig(
                    tokenBot = token,
                    maxReintentos = maxReintentos,
                    intervaloReintentoSegundos = intervalo,
                    timeoutEnvioSegundos = timeoutEnvio,
                    maxReenviosPorMinuto = maxPorMinuto,
                    recepcionActiva = binding.switchRecepcion.isChecked
                )
            )
            Snackbar.make(
                binding.root,
                if (binding.switchRecepcion.isChecked) {
                    R.string.settings_guardado
                } else {
                    // Apagar la recepción no es un ajuste más: la aplicación deja de hacer su
                    // trabajo y eso tiene que quedar dicho, no deducido.
                    R.string.settings_guardado_sin_recepcion
                },
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun validar(
        token: String,
        maxPorMinuto: Int,
        timeoutEnvio: Int,
        maxReintentos: Int,
        intervalo: Int
    ): Boolean {
        var valido = true

        // Solo la forma. Si el token sirve de verdad únicamente lo sabe Telegram, y eso es lo
        // que comprueba el botón de validación; esto atrapa el fallo más común, que es pegar
        // algo que no es un token.
        if (TokenDeBot.pareceValido(token)) {
            binding.tilTokenBot.error = null
        } else {
            binding.tilTokenBot.error = getString(R.string.settings_token_bot_invalido)
            valido = false
        }

        valido = comprobarRango(
            binding.tilMaxReenviosMinuto, maxPorMinuto, 1, 100,
            getString(R.string.settings_error_rango, 1, 100)
        ) && valido

        valido = comprobarRango(
            binding.tilTimeoutEnvio, timeoutEnvio, 10, 300,
            getString(R.string.settings_error_rango_segundos, 10, 300)
        ) && valido

        valido = comprobarRango(
            binding.tilMaxReintentos, maxReintentos, 1, 100,
            getString(R.string.settings_error_rango, 1, 100)
        ) && valido

        valido = comprobarRango(
            binding.tilIntervalo, intervalo, 5, 3600,
            getString(R.string.settings_error_rango_segundos, 5, 3600)
        ) && valido

        return valido
    }

    private fun comprobarRango(
        campo: com.google.android.material.textfield.TextInputLayout,
        valor: Int,
        minimo: Int,
        maximo: Int,
        mensaje: String
    ): Boolean = if (valor < minimo || valor > maximo) {
        campo.error = mensaje
        false
    } else {
        campo.error = null
        true
    }

    private fun com.google.android.material.textfield.TextInputEditText.entero(porDefecto: Int): Int =
        text?.toString()?.trim()?.toIntOrNull() ?: porDefecto

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /** Valores por defecto, para no repetirlos cuando un campo se queda vacío. */
        val VALORES = AppConfig()
    }
}
