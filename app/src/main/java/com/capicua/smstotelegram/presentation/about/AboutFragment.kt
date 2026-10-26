// SMStoTelegram para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstotelegram.presentation.about

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.capicua.smstotelegram.BuildConfig
import com.capicua.smstotelegram.R
import com.capicua.smstotelegram.data.update.ResultadoActualizacion
import com.capicua.smstotelegram.databinding.FragmentAboutBinding
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class AboutFragment : Fragment() {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    private val actualizaciones: ActualizacionViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.textVersion.text = "Versión ${BuildConfig.VERSION_NAME}"
        binding.buttonProyecto.setOnClickListener { abrirEnlace(URL_PROYECTO) }
        binding.buttonSmsrelay.setOnClickListener { abrirEnlace(URL_SMSRELAY) }
        binding.buttonDonativo.setOnClickListener { abrirEnlace(URL_DONATIVO) }
        configurarActualizaciones()
    }

    // ── Actualizaciones ───────────────────────────────────────────────────────

    private fun configurarActualizaciones() {
        binding.buttonBuscarActualizacion.setOnClickListener {
            actualizaciones.comprobar(forzar = true)
        }

        binding.switchActualizacionAvisos.setOnCheckedChangeListener { boton, marcado ->
            // Solo cuando lo mueve una persona: al pintar el estado se escribe `isChecked`, y eso
            // vuelve a llamar aquí — se guardaría lo que ya estaba guardado.
            if (boton.isPressed) actualizaciones.cambiarAvisos(marcado)
        }

        binding.buttonInstalarActualizacion.setOnClickListener { descargarOAutorizar() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                actualizaciones.ui.collect { pintar(it) }
            }
        }
    }

    private fun pintar(estado: ActualizacionViewModel.Estado) {
        val disponible = estado.resultado as? ResultadoActualizacion.Disponible

        binding.switchActualizacionAvisos.isChecked = estado.avisar
        binding.buttonBuscarActualizacion.isEnabled = !estado.comprobando
        binding.buttonInstalarActualizacion.isVisible = disponible?.apkUrl != null
        binding.buttonNotasActualizacion.isVisible = disponible != null
        binding.progressActualizacion.isVisible = estado.porcentaje != null

        estado.porcentaje?.let { binding.progressActualizacion.setProgressCompat(it, true) }

        if (disponible != null) {
            binding.buttonNotasActualizacion.setOnClickListener { abrirEnlace(disponible.pagina) }
        }

        binding.textActualizacionEstado.text = when {
            estado.error != null ->
                getString(R.string.about_actualizacion_fallo_descarga, estado.error)

            estado.porcentaje != null ->
                getString(R.string.about_actualizacion_descargando, estado.porcentaje)

            estado.comprobando -> getString(R.string.about_actualizacion_comprobando)

            disponible != null -> getString(
                if (disponible.apkUrl != null) {
                    R.string.about_actualizacion_disponible
                } else {
                    R.string.about_actualizacion_sin_apk
                },
                disponible.etiqueta
            )

            estado.resultado is ResultadoActualizacion.NoSePudo -> getString(
                R.string.about_actualizacion_fallo,
                (estado.resultado as ResultadoActualizacion.NoSePudo).motivo
            )

            estado.resultado is ResultadoActualizacion.AlDia ->
                getString(R.string.about_actualizacion_al_dia, BuildConfig.VERSION_NAME)

            !estado.avisar -> getString(R.string.about_actualizacion_apagada)

            else -> getString(R.string.about_actualizacion_sin_comprobar)
        }

        // El APK ya descargado se entrega una sola vez: se avisa al ViewModel antes de lanzar
        // el instalador, o al volver a esta pantalla se abriría otra vez.
        estado.apkListo?.let { uri ->
            actualizaciones.apkConsumido()
            instalar(uri)
        }
    }

    /**
     * Empieza la descarga, o manda primero a autorizar la instalación.
     *
     * Desde Android 8 el permiso del manifiesto no basta: hay que haber autorizado a *esta*
     * aplicación a instalar paquetes, y eso solo se concede en los ajustes del sistema. Se
     * comprueba antes de bajar tres megas para nada.
     */
    private fun descargarOAutorizar() {
        if (!actualizaciones.puedeInstalar()) {
            avisar(getString(R.string.about_actualizacion_autorizar))
            abrir(actualizaciones.intentDeAutorizacion())
            return
        }
        actualizaciones.descargar()
    }

    private fun instalar(uri: Uri) = abrir(actualizaciones.intentDeInstalacion(uri))

    // ── Enlaces y pantallas del sistema ───────────────────────────────────────

    /**
     * Abre [url] en el navegador del dispositivo.
     *
     * Se lanza y se captura [ActivityNotFoundException] en lugar de preguntar antes al
     * `PackageManager`: desde Android 11 la visibilidad de paquetes puede responder que no hay
     * navegador cuando sí lo hay, mientras que lanzar nunca se equivoca. Si de verdad no hay
     * ninguno se avisa, porque un botón que no hace nada parece la aplicación rota.
     */
    private fun abrirEnlace(url: String) = abrir(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun abrir(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No hay nada que abra $intent")
            avisar(getString(R.string.about_sin_navegador))
        }
    }

    private fun avisar(mensaje: String) {
        Snackbar.make(binding.root, mensaje, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /** Página del proyecto: el código, los cambios y los APK publicados. */
        const val URL_PROYECTO = "https://github.com/jbautistamartin/SMStoTelegram"

        /** Adónde sigue la aplicación: esta es su última versión. */
        const val URL_SMSRELAY = "https://github.com/jbautistamartin/SMSRelay/releases/latest"

        /** Enlace del donativo que se abre desde «Acerca de». */
        const val URL_DONATIVO = "https://paypal.me/jbautistamartin"
    }
}
