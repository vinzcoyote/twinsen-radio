package net.mspanc.twinsenradio.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.launch
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.data.StationSort
import net.mspanc.twinsenradio.databinding.ActivityMainBinding
import net.mspanc.twinsenradio.playback.MetadataFactory
import net.mspanc.twinsenradio.playback.PlaybackStatusBus
import net.mspanc.twinsenradio.playback.RadioService

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var repo: StationRepository
    private lateinit var metadata: MetadataFactory
    private lateinit var adapter: StationAdapter

    private var controller: MediaController? = null

    /** Station to switch on as soon as the controller attaches (see [EXTRA_PLAY_STATION]). */
    private var pendingStationId: String? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(b.toolbar) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            view.setPadding(
                view.paddingLeft,
                systemBars.top,
                view.paddingRight,
                view.paddingBottom
            )

                insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(b.miniPlayer) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                maxOf(systemBars.bottom, ime.bottom)
            )

            insets
        }
        
        prefs = Prefs(this)
        repo = StationRepository.get(this)
        metadata = MetadataFactory(this, prefs)

        adapter = StationAdapter(
            subtitleFor = { it.genre },
            actionIconFor = {
                if (it.id in prefs.favourites) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            },
            loadLogo = ::showLogo,
            onClick = ::play,
            onAction = { prefs.toggleFavourite(it.id) },
            // The logo leads to details. Tapping the row itself keeps playing - that's
            // the most common action and there's no reason to make it harder.
            onLogoClick = { startActivity(StationInfoActivity.intent(this, it)) }
        )
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        // Two tabs instead of one long list - favourites are what's reached for
        // most often, so they should be one tap away.
        b.tabs.addTab(b.tabs.newTab().setText(R.string.tab_all))
        b.tabs.addTab(b.tabs.newTab().setText(R.string.tab_favourites))
        b.tabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) = refreshList()
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
        })

        b.search.doAfterTextChanged { refreshList() }
        b.search.setOnClickListener { showHistory() }
        b.search.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) showHistory() }
        // We only remember the confirmed query, not every keystroke along the way
        b.search.setOnEditorActionListener { _, _, _ ->
            prefs.pushLocalSearch(b.search.text?.toString().orEmpty())
            refreshHistory()
            hideKeyboard()
            true
        }
        refreshHistory()

        b.playPause.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.isPlaying) c.pause() else c.play()
        }
        // Tapping the bar expands the full-screen player
        b.miniPlayer.setOnClickListener {
            if (PlaybackStatusBus.stationId.value != null) {
                startActivity(Intent(this, NowPlayingActivity::class.java))
            }
        }

        askForNotificationPermission()
        observeStatus()
        handleIntent(intent)

        lifecycleScope.launch {
            repo.refreshUserLists()
            adapter.submitList(repo.search(b.search.text?.toString().orEmpty()))
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, RadioService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            controller = future.get().also { c ->
                c.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) = renderMiniPlayer()
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = renderMiniPlayer()
                    override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) =
                        renderMiniPlayer()
                })
            }
            pendingStationId?.let { id ->
                pendingStationId = null
                repo.byId(id)?.let(::play)
            }
            renderMiniPlayer()
        }, MoreExecutors.directExecutor())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Allows switching on a station from the outside, without touching the screen:
     *   adb shell am start -n net.mspanc.twinsenradio/.ui.MainActivity --es play_station rns
     * Useful for testing and as a hook point for shortcuts.
     */
    private fun handleIntent(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_PLAY_STATION) ?: return
        val station = repo.byId(id) ?: return
        val c = controller
        if (c != null) play(station) else pendingStationId = id
    }

    override fun onStop() {
        controller?.release()
        controller = null
        super.onStop()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean = when (item.itemId) {
        R.id.action_search -> {
            toggleSearch()
            true
        }
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        R.id.action_sort -> {
            showSortDialog()
            true
        }
        R.id.action_discover -> {
            startActivity(Intent(this, DiscoverActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /**
     * Choice of list order. It's remembered, so it only needs to be set once.
     */
    private fun showSortDialog() {
        val options = StationSort.LABELS.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sort_title)
            .setSingleChoiceItems(options, prefs.stationSort.ordinal) { dialog, which ->
                prefs.stationSort = StationSort.at(which)
                refreshList()
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Shows or hides the search field. Hiding it clears the query - the list
     * should go back to full, not stay filtered by invisible text.
     */
    private fun toggleSearch() {
        val visible = b.searchLayout.visibility == android.view.View.VISIBLE
        if (visible) {
            b.search.setText("")
            b.searchLayout.visibility = android.view.View.GONE
            hideKeyboard()
        } else {
            b.searchLayout.visibility = android.view.View.VISIBLE
            b.search.requestFocus()
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm?.showSoftInput(b.search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            showHistory()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(b.search.windowToken, 0)
    }

    private fun refreshHistory() {
        b.search.setAdapter(
            android.widget.ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                prefs.localSearchHistory
            )
        )
    }

    private fun showHistory() {
        if (b.search.text.isNullOrBlank() && prefs.localSearchHistory.isNotEmpty()) {
            b.search.showDropDown()
        }
    }

    /**
     * Station logo. Built-in ones live in the APK, but stations pulled in from
     * the directory have theirs at a network address - hence the two paths.
     */
    private fun showLogo(station: Station, view: android.widget.ImageView) {
        ArtworkLoader.into(
            lifecycleScope,
            null,
            metadata.logoDisplayUri(station),
            metadata.logoResId(station),
            view
        )
    }

    private fun play(station: Station) {
        val c = controller ?: return

        // The same station that's already playing - we don't touch the stream,
        // just open the playback screen. Resetting the position again used to
        // drop the connection and you'd hear a gap.
        if (PlaybackStatusBus.stationId.value == station.id && c.isPlaying) {
            startActivity(Intent(this, NowPlayingActivity::class.java))
            return
        }

        c.setMediaItem(MediaItem.Builder().setMediaId(station.mediaId).build())
        c.prepare()
        c.play()
    }

    private fun observeStatus() {
        lifecycleScope.launch {
            PlaybackStatusBus.status.collect { renderMiniPlayer() }
        }
        lifecycleScope.launch {
            PlaybackStatusBus.nowPlaying.collect { renderMiniPlayer() }
        }
        lifecycleScope.launch {
            PlaybackStatusBus.stationId.collect { renderMiniPlayer() }
        }
        lifecycleScope.launch {
            PlaybackStatusBus.coverArtUrl.collect { renderMiniPlayer() }
        }
        // Favourites can change on the playback screen or in the car. The state
        // isn't part of the station model, so DiffUtil won't refresh anything on its own.
        lifecycleScope.launch {
            Prefs.favouritesFlow.collect {
                // On the favourites tab, toggling the star changes the list's
                // contents, not just the icon - hence the full rebuild.
                if (b.tabs.selectedTabPosition == 1) refreshList()
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }
        // A station added or removed in the discovery search should appear here
        // immediately, without leaving the screen.
        lifecycleScope.launch {
            Prefs.discoveredFlow.collect { refreshList() }
        }
        // Hiding or restoring a station must also rebuild the list immediately
        lifecycleScope.launch {
            Prefs.hiddenFlow.collect { refreshList() }
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from details - the station might have disappeared there or its favourite status changed
        refreshList()
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    /**
     * Rebuilds the list after its contents change.
     *
     * Scrolling to the top isn't cosmetic: RecyclerView anchors to the
     * position that's visible, so a station restored to the beginning would
     * load above the visible area, making it look like the restore hadn't worked.
     */
    private fun refreshList() {
        val query = b.search.text?.toString().orEmpty()
        val found = repo.search(query)
        val list = if (b.tabs.selectedTabPosition == 1) {
            found.filter { it.id in prefs.favourites }
        } else {
            found
        }
        adapter.submitList(list) { b.list.scrollToPosition(0) }
    }

    private fun renderMiniPlayer() {
        val station = PlaybackStatusBus.stationId.value?.let { repo.byId(it) }
        val now = PlaybackStatusBus.nowPlaying.value

        b.miniTitle.text = station?.name ?: getString(R.string.nothing_playing)
        // On the phone, metadata should just be metadata - diagnostic mode concerns
        // what we send to the car, and we only signal it on the playback screen.
        b.miniSubtitle.text = when {
            now?.isRealSong == true -> {
                val info = PlaybackStatusBus.trackInfo.value
                listOf(
                    MetadataFactory.displayArtist(now, info),
                    MetadataFactory.displayTitle(now, info)
                ).filter { it.isNotBlank() }.joinToString(" — ")
            }
            now?.slogan != null -> now.slogan
            now?.isAd == true -> getString(R.string.ad)
            else -> station?.genre.orEmpty()
        }
        b.miniStatus.text = getString(
            when (PlaybackStatusBus.status.value) {
                PlaybackStatusBus.Status.CONNECTING -> R.string.status_connecting
                PlaybackStatusBus.Status.BUFFERING -> R.string.status_buffering
                PlaybackStatusBus.Status.PLAYING -> R.string.status_playing
                PlaybackStatusBus.Status.RECONNECTING -> R.string.status_reconnecting
                PlaybackStatusBus.Status.WAITING_FOR_NETWORK -> R.string.status_waiting_network
                PlaybackStatusBus.Status.STATION_UNREACHABLE -> R.string.status_unreachable
                PlaybackStatusBus.Status.IDLE -> R.string.status_idle
            }
        )
        ArtworkLoader.into(
            lifecycleScope,
            PlaybackStatusBus.coverArtUrl.value?.let { android.net.Uri.parse(it) },
            station?.let { metadata.logoDisplayUri(it) },
            station?.let { metadata.logoResId(it) } ?: R.drawable.logo_placeholder,
            b.miniLogo
        )
        b.playPause.setImageResource(
            if (controller?.isPlaying == true) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )

        val variants = station?.variants().orEmpty()
        val chosen = station?.let { prefs.selectedStream(it.id) } ?: variants.maxByOrNull { it.kbps }?.url
        val current = variants.firstOrNull { it.url == chosen } ?: variants.firstOrNull()
        val label = current?.kbpsLabel(PlaybackStatusBus.qualityKbps.value)
        if (station == null || label == null) {
            b.bitrate.visibility = android.view.View.GONE
        } else {
            b.bitrate.visibility = android.view.View.VISIBLE
            b.bitrate.text = label
            b.bitrate.setOnClickListener {
                StreamPicker.show(this, station, prefs) { renderMiniPlayer() }
            }
        }
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_PLAY_STATION = "play_station"
    }
}
