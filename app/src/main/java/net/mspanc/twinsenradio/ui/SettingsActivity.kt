package net.mspanc.twinsenradio.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.ArtworkMode
import net.mspanc.twinsenradio.data.BufferProfile
import net.mspanc.twinsenradio.data.ClockColors
import net.mspanc.twinsenradio.data.ClockFace
import net.mspanc.twinsenradio.data.Line
import net.mspanc.twinsenradio.data.LineContent
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.databinding.ActivitySettingsBinding
import net.mspanc.twinsenradio.playback.DiagnosticFields
import net.mspanc.twinsenradio.playback.VehicleDiagnosticManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    /** The selected index of each list - MaterialAutoCompleteTextView holds text, not a position. */
    private val chosen = HashMap<Int, Int>()

    private enum class FolderAction {
        NONE,
        START_DIAGNOSTIC,
        SHOW_REPORTS
    }

    private var pendingFolderAction = FolderAction.NONE

    private val diagnosticListener: () -> Unit = {
        if (::b.isInitialized) {
            renderVehicleDiagnostic()
            if (b.diagReportsList.visibility == View.VISIBLE) {
                renderDiagnosticReports()
            }
        }
    }

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val action = pendingFolderAction
            pendingFolderAction = FolderAction.NONE

            if (uri == null) return@registerForActivityResult

            val flags =
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                contentResolver.takePersistableUriPermission(uri, flags)
            }

            VehicleDiagnosticManager.setReportDirectory(this, uri)

            when (action) {
                FolderAction.START_DIAGNOSTIC -> startVehicleDiagnostic()
                FolderAction.SHOW_REPORTS -> renderDiagnosticReports()
                FolderAction.NONE -> Unit
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            view.setPadding(
                view.paddingLeft,
                systemBars.top,
                view.paddingRight,
                maxOf(systemBars.bottom, ime.bottom)
            )

            insets
        }

        prefs = Prefs(this)

        b.toolbar.setNavigationOnClickListener { finish() }

        // --- dashboard display -------------------------------------------------
        // Labels and hints come from the Line enum, so the row descriptions
        // aren't duplicated in two places.
        b.swEnrich.isChecked = prefs.enrichWithYear
        bindLine(b.tilLineTop, b.ddLineTop, Line.TOP, prefs.lineTop)
        bindLine(b.tilLineMiddle, b.ddLineMiddle, Line.MIDDLE, prefs.lineMiddle)
        bindLine(b.tilLineBottom, b.ddLineBottom, Line.BOTTOM, prefs.lineBottom)
        // The "· album" options' labels spell out whether they'll carry a year -
        // keep that in sync with the switch without losing what's already picked.
        b.swEnrich.setOnCheckedChangeListener { _, checked ->
            listOf(b.ddLineTop, b.ddLineMiddle, b.ddLineBottom).forEach {
                bind(it, LineContent.labels(checked), pick(it))
            }
        }

        // --- artwork -------------------------------------------------------
        bind(b.ddClockFace, ClockFace.LABELS, prefs.clockFace) { updateClockOptionsEnabled(it) }
        b.swClockAlways.isChecked = prefs.clockCoverAlways
        b.swClockAlways.setOnCheckedChangeListener { _, _ ->
            updateClockOptionsEnabled(pick(b.ddClockFace))
        }
        bind(b.ddClockBg, ClockColors.BACKGROUND_LABELS, prefs.clockBackground)
        bind(b.ddClockFg, ClockColors.FOREGROUND_LABELS, prefs.clockForeground)
        bind(b.ddArtwork, ArtworkMode.LABELS, prefs.artworkMode)
        updateClockOptionsEnabled(prefs.clockFace)

        // --- metadata diagnostics ---------------------------------------------
        b.swDiag.isChecked = prefs.diagnosticMode
        b.swDiagApi.isChecked = prefs.diagnosticShowApiName
        b.swDiagApi.setOnCheckedChangeListener { _, _ -> renderLegend() }
        renderLegend()

        // --- Android Auto / vehicle capability diagnostics ---------------------
        b.btnVehicleDiagStart.setOnClickListener {
            if (VehicleDiagnosticManager.status().active) {
                VehicleDiagnosticManager.stopManual()
                renderVehicleDiagnostic()
                renderDiagnosticReports()
            } else {
                ensureDiagnosticFolder(FolderAction.START_DIAGNOSTIC)
            }
        }
        b.btnVehicleDiagReports.setOnClickListener {
            ensureDiagnosticFolder(FolderAction.SHOW_REPORTS)
        }
        renderVehicleDiagnostic()

        // --- the rest ----------------------------------------------------------
        bind(b.ddBuffer, BufferProfile.ALL.map { it.label }, prefs.bufferProfile)
        b.etM3u.setText(prefs.userM3uUrls.joinToString("\n"))
        renderHidden()

        b.btnSave.setOnClickListener { save() }
    }

    override fun onStart() {
        super.onStart()
        VehicleDiagnosticManager.addListener(diagnosticListener)
        renderVehicleDiagnostic()
    }

    override fun onStop() {
        VehicleDiagnosticManager.removeListener(diagnosticListener)
        super.onStop()
    }

    /**
     * Binds a dropdown list. Material holds text in it, but we need the
     * index - hence our own map of selected positions.
     */
    private fun bind(
        dropdown: MaterialAutoCompleteTextView,
        labels: List<String>,
        selected: Int,
        onPick: (Int) -> Unit = {}
    ) {
        val index = selected.coerceIn(labels.indices)
        dropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        )
        dropdown.setText(labels[index], false)
        chosen[dropdown.id] = index
        dropdown.setOnItemClickListener { _, _, position, _ ->
            chosen[dropdown.id] = position
            onPick(position)
        }
    }

    private fun bindLine(
        layout: TextInputLayout,
        dropdown: MaterialAutoCompleteTextView,
        line: Line,
        selected: Int
    ) {
        layout.hint = line.label
        layout.helperText = line.hint
        layout.isHelperTextEnabled = true
        bind(dropdown, LineContent.labels(b.swEnrich.isChecked), selected)
    }

    private fun pick(dropdown: MaterialAutoCompleteTextView): Int = chosen[dropdown.id] ?: 0

    /** Clock colors only make sense when the clock actually replaces the cover art. */
    private fun updateClockOptionsEnabled(clockFaceIndex: Int) {
        val usesClock = ClockFace.at(clockFaceIndex) != ClockFace.NONE
        listOf(b.swClockAlways, b.tilClockBg, b.tilClockFg).forEach {
            it.isEnabled = usesClock
            it.alpha = if (usesClock) 1f else 0.4f
        }
    }

    private fun ensureDiagnosticFolder(action: FolderAction) {
        if (VehicleDiagnosticManager.hasReportDirectory(this)) {
            when (action) {
                FolderAction.START_DIAGNOSTIC -> startVehicleDiagnostic()
                FolderAction.SHOW_REPORTS -> renderDiagnosticReports()
                FolderAction.NONE -> Unit
            }
            return
        }

        pendingFolderAction = action
        Toast.makeText(this, R.string.opt_vehicle_diag_choose_folder, Toast.LENGTH_LONG).show()
        folderPicker.launch(null)
    }

    private fun startVehicleDiagnostic() {
        if (!VehicleDiagnosticManager.start(this)) {
            pendingFolderAction = FolderAction.START_DIAGNOSTIC
            Toast.makeText(this, R.string.opt_vehicle_diag_folder_error, Toast.LENGTH_LONG).show()
            folderPicker.launch(null)
            return
        }

        b.diagReportsList.visibility = View.GONE
        renderVehicleDiagnostic()
        Toast.makeText(this, R.string.opt_vehicle_diag_started, Toast.LENGTH_SHORT).show()
    }

    private fun renderVehicleDiagnostic() {
        val status = VehicleDiagnosticManager.status()
        if (status.active) {
            b.tvVehicleDiagStatus.text = getString(
                R.string.opt_vehicle_diag_running,
                status.started.orEmpty(),
                status.eventCount
            )
            b.btnVehicleDiagStart.setText(R.string.opt_vehicle_diag_stop)
        } else {
            b.tvVehicleDiagStatus.text =
                status.lastStopReason?.let {
                    getString(R.string.opt_vehicle_diag_stopped, it)
                } ?: getString(R.string.opt_vehicle_diag_idle)
            b.btnVehicleDiagStart.setText(R.string.opt_vehicle_diag_start)
        }
    }

    private fun renderDiagnosticReports() {
        val reports = VehicleDiagnosticManager.listReports(this)
        b.diagReportsList.removeAllViews()
        b.diagReportsList.visibility = View.VISIBLE

        if (reports.isEmpty()) {
            b.diagReportsList.addView(
                TextView(this).apply {
                    text = getString(R.string.opt_vehicle_diag_no_reports)
                    setPadding(0, dp(8), 0, 0)
                }
            )
            return
        }

        reports.forEach { report ->
            b.diagReportsList.addView(
                MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = getString(
                        R.string.opt_vehicle_diag_report_entry,
                        report.displayDate,
                        report.appVersion
                    )
                    isAllCaps = false
                    setOnClickListener { showDiagnosticReport(report) }
                }
            )
        }
    }

    private fun showDiagnosticReport(report: VehicleDiagnosticManager.ReportEntry) {
        val textView = TextView(this).apply {
            text = VehicleDiagnosticManager.readReport(this@SettingsActivity, report.uri)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val scroll = ScrollView(this).apply {
            addView(textView)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(
                getString(
                    R.string.opt_vehicle_diag_report_entry,
                    report.displayDate,
                    report.appVersion
                )
            )
            .setView(scroll)
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /**
     * Stations removed from the list. Built-in ones can't be deleted, so they're
     * only hidden - and this is the only place they can be recovered from.
     */
    private fun renderHidden() {
        val repo = StationRepository.get(this)
        val hidden = prefs.hidden
        if (repo.builtIn.isEmpty() || hidden.isEmpty()) {
            b.hiddenBox.visibility = View.GONE
            return
        }
        val names = repo.allIncludingHidden()
            .filter { it.id in hidden }
            .joinToString(", ") { it.name }
        b.hiddenBox.visibility = View.VISIBLE
        b.hiddenLabel.text = getString(R.string.opt_hidden_label, names.ifBlank { "${hidden.size}" })
        b.btnRestoreHidden.setOnClickListener {
            prefs.restoreAllHidden()
            renderHidden()
            Toast.makeText(this, R.string.opt_restored_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderLegend() {
        b.tvLegend.text = DiagnosticFields.legend(b.swDiagApi.isChecked)
    }

    private fun save() {
        prefs.lineTop = pick(b.ddLineTop)
        prefs.lineMiddle = pick(b.ddLineMiddle)
        prefs.lineBottom = pick(b.ddLineBottom)
        prefs.enrichWithYear = b.swEnrich.isChecked

        prefs.clockFace = pick(b.ddClockFace)
        prefs.clockCoverAlways = b.swClockAlways.isChecked
        prefs.clockBackground = pick(b.ddClockBg)
        prefs.clockForeground = pick(b.ddClockFg)
        prefs.artworkMode = pick(b.ddArtwork)

        prefs.diagnosticMode = b.swDiag.isChecked
        prefs.diagnosticShowApiName = b.swDiagApi.isChecked

        prefs.bufferProfile = pick(b.ddBuffer)
        prefs.userM3uUrls = b.etM3u.text?.toString().orEmpty().lines()

        lifecycleScope.launch {
            StationRepository.get(this@SettingsActivity).refreshUserLists(force = true)
            Toast.makeText(this@SettingsActivity, R.string.opt_saved, Toast.LENGTH_SHORT).show()
        }
    }
}
