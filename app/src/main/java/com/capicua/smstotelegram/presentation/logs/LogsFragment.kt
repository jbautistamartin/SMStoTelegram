// SMStoTelegram para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.smstotelegram.presentation.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.capicua.smstotelegram.R
import com.capicua.smstotelegram.databinding.FragmentLogsBinding
import com.capicua.smstotelegram.domain.model.LogEntry
import com.capicua.smstotelegram.domain.model.LogTipo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LogsFragment : Fragment() {

    private var _binding: FragmentLogsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LogsViewModel by viewModels()
    private lateinit var adapter: LogsAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = LogsAdapter(alMantenerPulsado = ::copiarAlPortapapeles)
        binding.recyclerViewLogs.adapter = adapter

        configurarChips()
        configurarExportar()
        configurarLimpiar()
        observarLogs()
    }

    /**
     * Copia una entrada del registro al portapapeles. La engancha la pulsación larga sobre la
     * fila, que es donde la busca quien quiere pegar un error en un correo o en un parte de
     * incidencia: lo que se copia es **la misma línea** que escribe el fichero exportado.
     *
     * El aviso solo se enseña hasta Android 12. Desde Android 13 el sistema saca el suyo al
     * escribir en el portapapeles —con una vista previa de lo copiado— y añadir un Snackbar
     * encima deja dos avisos de lo mismo tapándose el uno al otro.
     */
    private fun copiarAlPortapapeles(log: LogEntry) {
        val portapapeles = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        portapapeles.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.logs_copiado_etiqueta), viewModel.comoLinea(log))
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Snackbar.make(binding.root, R.string.logs_copiado, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun configurarChips() {
        binding.chipTodos.isChecked = true

        binding.chipTodos.setOnClickListener         { viewModel.aplicarFiltro(null) }
        binding.chipRecibidos.setOnClickListener     { viewModel.aplicarFiltro(LogTipo.SMS_RECIBIDO) }
        binding.chipReenviados.setOnClickListener    { viewModel.aplicarFiltro(LogTipo.SMS_REENVIADO) }
        binding.chipReglas.setOnClickListener        { viewModel.aplicarFiltro(LogTipo.REGLA_APLICADA) }
        binding.chipSinRegla.setOnClickListener      { viewModel.aplicarFiltro(LogTipo.SIN_REGLA) }
        binding.chipLimite.setOnClickListener        { viewModel.aplicarFiltro(LogTipo.LIMITE_ALCANZADO) }
        binding.chipErrores.setOnClickListener       { viewModel.aplicarFiltro(LogTipo.ERROR) }
        binding.chipSistema.setOnClickListener       { viewModel.aplicarFiltro(LogTipo.SISTEMA) }
    }

    private fun configurarLimpiar() {
        binding.fabLimpiarLogs.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Limpiar logs")
                .setMessage("Se eliminarán todas las entradas de log. Esta acción no se puede deshacer.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Limpiar") { _, _ -> viewModel.limpiarTodos() }
                .show()
        }
    }

    private fun configurarExportar() {
        binding.fabExportar.setOnClickListener {
            viewModel.exportarLogs { uri ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.logs_exportar)))
            }
        }
    }

    private fun observarLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.logs.collect { logs ->
                        adapter.submitList(logs)
                        binding.textViewSinLogs.visibility =
                            if (logs.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.totalLogs.collect { total ->
                        binding.textViewTotal.text =
                            resources.getQuantityString(R.plurals.logs_total_entradas, total, total)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}