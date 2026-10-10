package net.mspanc.twinsenradio.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Collects the capabilities Android Auto / the media host actually exposes to
 * Twinsen Radio and writes a persistent TXT + JSON report into a user-selected
 * Storage Access Framework folder.
 *
 * Reports are rewritten after every newly observed piece of information, so a
 * useful partial report survives even if the process is killed before the
 * diagnostic is explicitly stopped.
 */
object VehicleDiagnosticManager {

    data class Status(
        val active: Boolean,
        val started: String? = null,
        val eventCount: Int = 0,
        val lastStopReason: String? = null
    )

    data class ReportEntry(
        val uri: Uri,
        val displayDate: String,
        val appVersion: String,
        val fileName: String
    )

    private data class Session(
        val context: Context,
        val startedAt: LocalDateTime,
        val appVersion: String,
        val txtUri: Uri,
        val jsonUri: Uri,
        val capabilities: LinkedHashMap<String, String> = linkedMapOf(),
        val events: JSONArray = JSONArray(),
        val eventFingerprints: MutableSet<String> = linkedSetOf(),
        val browserPackages: MutableSet<String> = linkedSetOf(),
        var lastNewInfoAtMs: Long = System.currentTimeMillis(),
        var stopReason: String? = null
    )

    private data class CachedConnection(
        val packageName: String,
        val uid: Int,
        val controllerVersion: Int,
        val interfaceVersion: Int,
        val hints: Bundle
    )

    private data class CachedRoot(
        val packageName: String,
        val hints: Bundle?
    )

    private data class CachedChildren(
        val packageName: String,
        val parentId: String,
        val page: Int,
        val pageSize: Int,
        val hints: Bundle?
    )

    private const val PREFS = "vehicle_diagnostics"
    private const val KEY_FOLDER_URI = "folder_uri"
    private const val TAG = "VehicleDiagnostic"
    private const val IDLE_TIMEOUT_MS = 15 * 60_000L

    private const val KEY_MEDIA_HOST_VERSION =
        "androidx.car.app.mediaextensions.KEY_ROOT_HINT_MEDIA_HOST_VERSION"
    private const val KEY_MEDIA_SESSION_API =
        "androidx.car.app.mediaextensions.KEY_ROOT_HINT_MEDIA_SESSION_API"
    private const val KEY_MAX_QUEUE_RESTRICTED =
        "androidx.car.app.mediaextensions.KEY_ROOT_HINT_MAX_QUEUE_ITEMS_WHILE_RESTRICTED"
    private const val KEY_MAX_ITEMS_RESTRICTED =
        "androidx.car.app.mediaextensions.KEY_HINT_VIEW_MAX_ITEMS_WHILE_RESTRICTED"
    private const val KEY_MAX_LIST_PER_ROW =
        "androidx.car.app.mediaextensions.KEY_HINT_VIEW_MAX_LIST_ITEMS_COUNT_PER_ROW"
    private const val KEY_MAX_CATEGORY_LIST_PER_ROW =
        "androidx.car.app.mediaextensions.KEY_HINT_VIEW_MAX_CATEGORY_LIST_ITEMS_COUNT_PER_ROW"
    private const val KEY_MAX_GRID_PER_ROW =
        "androidx.car.app.mediaextensions.KEY_HINT_VIEW_MAX_GRID_ITEMS_COUNT_PER_ROW"
    private const val KEY_MAX_CATEGORY_GRID_PER_ROW =
        "androidx.car.app.mediaextensions.KEY_HINT_VIEW_MAX_CATEGORY_GRID_ITEMS_COUNT_PER_ROW"

    private const val KEY_MEDIA_ART_SIZE =
        "android.media.extras.MEDIA_ART_SIZE_HINT_PIXELS"
    private const val KEY_CUSTOM_ACTION_LIMIT =
        "androidx.media.utils.MediaBrowserCompat.extras.CUSTOM_BROWSER_ACTION_LIMIT"
    private const val KEY_ROOT_CHILDREN_LIMIT =
        "androidx.media.MediaBrowserCompat.Extras.KEY_ROOT_CHILDREN_LIMIT"
    private const val KEY_ROOT_CHILDREN_FLAGS =
        "androidx.media.MediaBrowserCompat.Extras.KEY_ROOT_CHILDREN_SUPPORTED_FLAGS"

    private val stampFile = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    private val stampHuman = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var session: Session? = null
    private var lastStopReason: String? = null
    private val listeners = linkedSetOf<() -> Unit>()
    private var cachedConnection: CachedConnection? = null
    private var cachedRoot: CachedRoot? = null
    private val cachedChildren = linkedMapOf<String, CachedChildren>()

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun status(): Status {
        val s = session
        return if (s == null) {
            Status(active = false, lastStopReason = lastStopReason)
        } else {
            Status(
                active = true,
                started = s.startedAt.format(stampHuman),
                eventCount = s.eventFingerprints.size,
                lastStopReason = null
            )
        }
    }

    fun hasReportDirectory(context: Context): Boolean =
        reportDirectory(context) != null

    fun setReportDirectory(context: Context, uri: Uri) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FOLDER_URI, uri.toString())
            .apply()
        notifyChanged()
    }

    fun start(context: Context): Boolean {
        if (session != null) return true

        val app = context.applicationContext
        val folder = reportDirectory(app) ?: return false
        val started = LocalDateTime.now()
        val version = appVersion(app)
        val safeVersion = version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val base = "TwinsenRadio_Diagnostic_${started.format(stampFile)}_v$safeVersion"

        val txt = createDocument(app, folder, "text/plain", "$base.txt") ?: return false
        val json = createDocument(app, folder, "application/json", "$base.json") ?: return false

        val newSession = Session(
            context = app,
            startedAt = started,
            appVersion = version,
            txtUri = txt,
            jsonUri = json
        )
        session = newSession
        lastStopReason = null

        addCapability(newSession, "application.version", version)
        addCapability(newSession, "android.version", android.os.Build.VERSION.RELEASE ?: "")
        addCapability(newSession, "android.sdk", android.os.Build.VERSION.SDK_INT.toString())
        addCapability(newSession, "device.manufacturer", android.os.Build.MANUFACTURER ?: "")
        addCapability(newSession, "device.model", android.os.Build.MODEL ?: "")
        androidAutoVersion(app)?.let {
            addCapability(newSession, "android_auto.version", it)
        }

        addEvent(
            newSession,
            source = "diagnostic_start",
            packageName = app.packageName,
            extras = null,
            details = linkedMapOf("started" to started.format(stampHuman))
        )

        // Android Auto may already have sent its root hints before the user opened
        // Settings and pressed "Create diagnostic". Replay the latest observations
        // so those capabilities are not lost.
        cachedConnection?.let {
            recordConnection(
                app,
                it.packageName,
                it.uid,
                it.controllerVersion,
                it.interfaceVersion,
                Bundle(it.hints)
            )
        }
        cachedRoot?.let {
            recordRoot(app, it.packageName, it.hints?.let(::Bundle))
        }
        cachedChildren.values.forEach {
            recordChildren(
                app,
                it.packageName,
                it.parentId,
                it.page,
                it.pageSize,
                it.hints?.let(::Bundle)
            )
        }

        flush(newSession)
        scheduleTimeout(newSession)
        notifyChanged()
        return true
    }

    fun stopManual() {
        stop("Arrêt manuel")
    }

    fun recordConnection(
        context: Context,
        packageName: String,
        uid: Int,
        controllerVersion: Int,
        interfaceVersion: Int,
        hints: Bundle
    ) {
        cachedConnection = CachedConnection(
            packageName,
            uid,
            controllerVersion,
            interfaceVersion,
            Bundle(hints)
        )
        val s = session ?: return
        var changed = false
        changed = addCapability(s, "controller.$packageName.uid", uid.toString()) || changed
        changed = addCapability(
            s,
            "controller.$packageName.controller_version",
            controllerVersion.toString()
        ) || changed
        changed = addCapability(
            s,
            "controller.$packageName.interface_version",
            interfaceVersion.toString()
        ) || changed
        changed = extractKnownCapabilities(s, hints) || changed
        changed = addEvent(
            s,
            "connection",
            packageName,
            hints,
            linkedMapOf(
                "uid" to uid.toString(),
                "controllerVersion" to controllerVersion.toString(),
                "interfaceVersion" to interfaceVersion.toString()
            )
        ) || changed
        if (changed) onNewInformation(s)
    }

    fun recordRoot(
        context: Context,
        packageName: String,
        hints: Bundle?
    ) {
        cachedRoot = CachedRoot(packageName, hints?.let(::Bundle))
        val s = session ?: return
        s.browserPackages += packageName
        var changed = extractKnownCapabilities(s, hints)
        changed = addEvent(s, "library_root", packageName, hints) || changed
        if (changed) onNewInformation(s)
    }

    fun recordChildren(
        context: Context,
        packageName: String,
        parentId: String,
        page: Int,
        pageSize: Int,
        hints: Bundle?
    ) {
        cachedChildren[parentId] = CachedChildren(
            packageName,
            parentId,
            page,
            pageSize,
            hints?.let(::Bundle)
        )
        val s = session ?: return
        s.browserPackages += packageName
        var changed = false
        changed = addCapability(s, "browse.$parentId.page_size", pageSize.toString()) || changed
        changed = extractKnownCapabilities(s, hints) || changed
        changed = addEvent(
            s,
            "children",
            packageName,
            hints,
            linkedMapOf(
                "parentId" to parentId,
                "page" to page.toString(),
                "pageSize" to pageSize.toString()
            )
        ) || changed
        if (changed) onNewInformation(s)
    }

    fun recordSearch(
        context: Context,
        packageName: String,
        query: String,
        page: Int?,
        pageSize: Int?,
        hints: Bundle?
    ) {
        val s = session ?: return
        s.browserPackages += packageName
        var changed = extractKnownCapabilities(s, hints)
        val details = linkedMapOf("query" to query)
        page?.let { details["page"] = it.toString() }
        pageSize?.let {
            details["pageSize"] = it.toString()
            changed = addCapability(s, "search.page_size", it.toString()) || changed
        }
        changed = addEvent(s, "search", packageName, hints, details) || changed
        if (changed) onNewInformation(s)
    }

    fun controllerDisconnected(packageName: String) {
        if (cachedConnection?.packageName == packageName) cachedConnection = null
        if (cachedRoot?.packageName == packageName) cachedRoot = null
        cachedChildren.entries.removeAll { it.value.packageName == packageName }

        val s = session ?: return
        if (packageName in s.browserPackages) {
            addEvent(
                s,
                "controller_disconnected",
                packageName,
                null,
                linkedMapOf("package" to packageName)
            )
            stop("Déconnexion Android Auto / hôte média")
        }
    }

    fun listReports(context: Context): List<ReportEntry> {
        val folder = reportDirectory(context) ?: return emptyList()
        val resolver = context.contentResolver
        val docId = runCatching { DocumentsContract.getTreeDocumentId(folder) }.getOrNull()
            ?: return emptyList()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        val result = mutableListOf<ReportEntry>()

        runCatching {
            resolver.query(children, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    if (!name.endsWith(".txt", ignoreCase = true)) continue
                    val match = REPORT_NAME.matchEntire(name) ?: continue
                    val id = cursor.getString(idCol)
                    val uri = DocumentsContract.buildDocumentUriUsingTree(folder, id)
                    val date = runCatching {
                        LocalDateTime.parse(match.groupValues[1], stampFile).format(stampHuman)
                    }.getOrDefault(match.groupValues[1])
                    result += ReportEntry(
                        uri = uri,
                        displayDate = date,
                        appVersion = match.groupValues[2],
                        fileName = name
                    )
                }
            }
        }.onFailure {
            Log.w(TAG, "Cannot list diagnostic reports: ${it.message}")
        }

        return result.sortedByDescending { it.fileName }
    }

    fun readReport(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: ""
        }.getOrElse { "Impossible de lire ce rapport : ${it.message}" }

    private fun reportDirectory(context: Context): Uri? {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FOLDER_URI, null)
            ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    private fun createDocument(
        context: Context,
        treeUri: Uri,
        mimeType: String,
        displayName: String
    ): Uri? = runCatching {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        DocumentsContract.createDocument(
            context.contentResolver,
            parent,
            mimeType,
            displayName
        )
    }.onFailure {
        Log.w(TAG, "Cannot create $displayName: ${it.message}")
    }.getOrNull()

    private fun extractKnownCapabilities(s: Session, bundle: Bundle?): Boolean {
        if (bundle == null) return false
        var changed = false

        fun readString(key: String, target: String) {
            if (!bundle.containsKey(key)) return
            @Suppress("DEPRECATION")
            val value = bundle.get(key)?.toString() ?: return
            changed = addCapability(s, target, value) || changed
        }

        readString(KEY_MEDIA_HOST_VERSION, "host.media_host_version")
        readString(KEY_MEDIA_SESSION_API, "host.media_session_api")
        readString(KEY_MEDIA_ART_SIZE, "display.media_art_size_px")
        readString(KEY_CUSTOM_ACTION_LIMIT, "browse.custom_actions_per_item_max")
        readString(KEY_ROOT_CHILDREN_LIMIT, "browse.root_children_max")
        readString(KEY_ROOT_CHILDREN_FLAGS, "browse.root_children_supported_flags")
        readString(KEY_MAX_QUEUE_RESTRICTED, "driving.max_queue_items_restricted")
        readString(KEY_MAX_ITEMS_RESTRICTED, "driving.max_browse_items_restricted")
        readString(KEY_MAX_LIST_PER_ROW, "display.list_items_per_row_max")
        readString(KEY_MAX_CATEGORY_LIST_PER_ROW, "display.category_list_items_per_row_max")
        readString(KEY_MAX_GRID_PER_ROW, "display.grid_columns_max")
        readString(KEY_MAX_CATEGORY_GRID_PER_ROW, "display.category_grid_columns_max")

        return changed
    }

    private fun addCapability(s: Session, key: String, value: String): Boolean {
        val normalized = value.ifBlank { "(vide)" }
        if (s.capabilities[key] == normalized) return false
        s.capabilities[key] = normalized
        return true
    }

    private fun addEvent(
        s: Session,
        source: String,
        packageName: String,
        extras: Bundle?,
        details: Map<String, String> = emptyMap()
    ): Boolean {
        val extrasMap = bundleToMap(extras)
        val fingerprint = buildString {
            append(source)
            append('|')
            append(packageName)
            details.toSortedMap().forEach { (k, v) -> append("|$k=$v") }
            extrasMap.toSortedMap().forEach { (k, v) -> append("|$k=$v") }
        }
        if (!s.eventFingerprints.add(fingerprint)) return false

        val event = JSONObject()
            .put("time", LocalDateTime.now().format(stampHuman))
            .put("source", source)
            .put("package", packageName)

        if (details.isNotEmpty()) {
            val detailObject = JSONObject()
            details.forEach { (k, v) -> detailObject.put(k, v) }
            event.put("details", detailObject)
        }

        if (extrasMap.isNotEmpty()) {
            val extrasObject = JSONObject()
            extrasMap.forEach { (k, v) -> extrasObject.put(k, v) }
            event.put("extras", extrasObject)
        }

        s.events.put(event)
        return true
    }

    private fun bundleToMap(bundle: Bundle?): Map<String, String> {
        if (bundle == null || bundle.isEmpty) return emptyMap()
        val map = linkedMapOf<String, String>()
        bundle.keySet().sorted().forEach { key ->
            @Suppress("DEPRECATION")
            map[key] = runCatching { bundle.get(key)?.toString() ?: "null" }
                .getOrElse { "<illisible: ${it.javaClass.simpleName}>" }
        }
        return map
    }

    private fun onNewInformation(s: Session) {
        s.lastNewInfoAtMs = System.currentTimeMillis()
        flush(s)
        scheduleTimeout(s)
        notifyChanged()
    }

    private fun scheduleTimeout(s: Session) {
        timeoutRunnable?.let(handler::removeCallbacks)
        val runnable = Runnable {
            val current = session
            if (current !== s) return@Runnable
            val elapsed = System.currentTimeMillis() - current.lastNewInfoAtMs
            if (elapsed >= IDLE_TIMEOUT_MS) {
                stop("15 minutes sans nouvelle information")
            } else {
                handler.postDelayed(timeoutRunnable ?: return@Runnable, IDLE_TIMEOUT_MS - elapsed)
            }
        }
        timeoutRunnable = runnable
        handler.postDelayed(runnable, IDLE_TIMEOUT_MS)
    }

    private fun stop(reason: String) {
        val s = session ?: return
        timeoutRunnable?.let(handler::removeCallbacks)
        timeoutRunnable = null
        s.stopReason = reason
        lastStopReason = reason
        flush(s)
        session = null
        notifyChanged()
    }

    private fun flush(s: Session) {
        writeText(s.context, s.txtUri, buildTextReport(s))
        writeText(s.context, s.jsonUri, buildJsonReport(s).toString(2))
    }

    private fun writeText(context: Context, uri: Uri, content: String) {
        runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                it.write(content)
            }
        }.onFailure {
            Log.w(TAG, "Cannot write diagnostic report: ${it.message}")
        }
    }

    private fun buildTextReport(s: Session): String = buildString {
        appendLine("TWINSEN RADIO - DIAGNOSTIC ANDROID AUTO / VEHICULE")
        appendLine("==================================================")
        appendLine("Début : ${s.startedAt.format(stampHuman)}")
        appendLine("Version Twinsen Radio : ${s.appVersion}")
        appendLine(
            "État : " + if (s.stopReason == null) {
                "diagnostic en cours"
            } else {
                "terminé - ${s.stopReason}"
            }
        )
        appendLine()

        appendLine("CAPACITÉS / INFORMATIONS INTERPRÉTÉES")
        appendLine("-------------------------------------")
        if (s.capabilities.isEmpty()) {
            appendLine("(aucune capacité reçue)")
        } else {
            s.capabilities.toSortedMap().forEach { (key, value) ->
                appendLine("$key = $value")
            }
        }
        appendLine()
        appendLine(
            "Écran secondaire / combiné : aucune capacité média publique explicite " +
                "n'est interprétée comme preuve de présence. Les extras bruts sont conservés ci-dessous."
        )
        appendLine()

        appendLine("INFORMATIONS BRUTES REÇUES")
        appendLine("--------------------------")
        for (i in 0 until s.events.length()) {
            val event = s.events.getJSONObject(i)
            appendLine("[${event.optString("time")}] ${event.optString("source")} - ${event.optString("package")}")
            val details = event.optJSONObject("details")
            details?.keys()?.asSequence()?.toList()?.sorted()?.forEach { key ->
                appendLine("  $key = ${details.optString(key)}")
            }
            val extras = event.optJSONObject("extras")
            extras?.keys()?.asSequence()?.toList()?.sorted()?.forEach { key ->
                appendLine("  $key = ${extras.optString(key)}")
            }
        }
    }

    private fun buildJsonReport(s: Session): JSONObject {
        val caps = JSONObject()
        s.capabilities.forEach { (key, value) -> caps.put(key, value) }

        return JSONObject()
            .put("format_version", 1)
            .put("started", s.startedAt.format(stampHuman))
            .put("app_version", s.appVersion)
            .put("active", s.stopReason == null)
            .put("stop_reason", s.stopReason ?: JSONObject.NULL)
            .put("capabilities", caps)
            .put("events", s.events)
            .put(
                "notes",
                JSONArray().put(
                    "La présence d'un écran secondaire/combiné n'est pas déduite sans hint explicite."
                )
            )
    }

    private fun appVersion(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")

    private fun androidAutoVersion(context: Context): String? =
        runCatching {
            context.packageManager
                .getPackageInfo("com.google.android.projection.gearhead", 0)
                .versionName
        }.getOrNull()

    private fun notifyChanged() {
        handler.post {
            listeners.toList().forEach { listener ->
                runCatching { listener() }
            }
        }
    }

    private val REPORT_NAME =
        Regex("""TwinsenRadio_Diagnostic_(\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2})_v(.+)\.txt""")
}
