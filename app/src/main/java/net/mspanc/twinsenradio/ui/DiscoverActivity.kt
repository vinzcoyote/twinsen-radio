package net.mspanc.twinsenradio.ui

import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import android.widget.ArrayAdapter
import android.widget.ImageView
import com.google.android.material.chip.Chip
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.DiscoverSort
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.RadioBrowser
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.databinding.ActivityDiscoverBinding
import net.mspanc.twinsenradio.playback.MetadataFactory

/**
 * Search for stations that aren't on the built-in list.
 *
 * The catalog and the reasons behind choosing it are described in [RadioBrowser]. This
 * class is only the view layer: the entered text, results, the add button and the
 * transition to details. An added station lands in [Prefs] and from then on behaves
 * like any other - it can be favorited, played and found in the car.
 */
@UnstableApi
class DiscoverActivity : AppCompatActivity() {

    private lateinit var b: ActivityDiscoverBinding
    private lateinit var prefs: Prefs
    private lateinit var metadata: MetadataFactory
    private lateinit var adapter: StationAdapter

    /** The latest in-flight query - we cancel it on every new character. */
    private var searchJob: Job? = null

    /**
     * Return from details. When a station was added or removed, we clear the
     * search field - the user should then see their list with the new entry,
     * not the same results they were just browsing through.
     */
    private val details = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val changed = result.data?.getBooleanExtra(StationInfoActivity.RESULT_CHANGED, false)
        if (changed == true) {
            b.query.setText("")
            b.query.clearFocus()
        }
    }

    /**
     * Results of the latest search, so that tapping a row has something to
     * populate the details screen with. [Station] doesn't carry country or codec.
     */
    private var lastResults: List<RadioBrowser.Found> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDiscoverBinding.inflate(layoutInflater)
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
        metadata = MetadataFactory(this, prefs)

        b.toolbar.setNavigationOnClickListener { goBack() }
        b.toolbar.inflateMenu(R.menu.discover)
        b.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_sort) {
                showSortDialog()
                true
            } else {
                false
            }
        }

        // Going back while results are on screen first returns to the initial
        // state - the search bar, recent queries and your own list - and only
        // the second back press exits to the station list. Jumping straight out
        // after adding a station took away the chance to confirm it actually landed.
        onBackPressedDispatcher.addCallback(this) { goBack() }

        adapter = StationAdapter(
            subtitleFor = { it.genre },
            actionIconFor = {
                if (prefs.isDiscovered(it.id)) R.drawable.ic_check else R.drawable.ic_add
            },
            loadLogo = ::showLogo,
            onClick = ::openDetails,
            onAction = ::toggle
        )
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter

        b.query.doAfterTextChanged { text -> scheduleSearch(text?.toString().orEmpty()) }
        b.query.setOnClickListener { showHistory() }
        b.query.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) showHistory() }
        refreshHistory()

        // Adding or removing a station should immediately be reflected in the
        // row icons - even when the change came from the details screen.
        lifecycleScope.launch {
            Prefs.discoveredFlow.collect {
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
                if (queryText().length < 2) showAdded()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from details - button state may have changed there.
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    /**
     * Ordering of results. The catalog returns them by vote count and that stays
     * the default - it filters out dead and random entries best.
     */
    private fun sortResults(found: List<RadioBrowser.Found>): List<RadioBrowser.Found> =
        when (prefs.discoverSort) {
            DiscoverSort.POPULARITY -> found
            DiscoverSort.NAME -> found.sortedBy { it.name.lowercase() }
            DiscoverSort.BITRATE -> found.sortedByDescending { it.bitrate }
            DiscoverSort.COUNTRY ->
                found.sortedWith(compareBy({ it.country.orEmpty() }, { it.name.lowercase() }))
        }

    private fun showSortDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sort_title)
            .setSingleChoiceItems(
                DiscoverSort.LABELS.toTypedArray(),
                prefs.discoverSort.ordinal
            ) { dialog, which ->
                prefs.discoverSort = DiscoverSort.at(which)
                dialog.dismiss()
                // We sort what we already have - without re-querying the catalog
                if (lastResults.isNotEmpty()) {
                    lastResults = sortResults(lastResults)
                    adapter.submitList(lastResults.map { it.toStation() }) {
                        b.list.scrollToPosition(0)
                    }
                }
            }
            .show()
    }

    private fun goBack() {
        if (queryText().isNotEmpty()) {
            b.query.setText("")
            b.query.clearFocus()
            hideKeyboard()
        } else {
            finish()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(b.query.windowToken, 0)
    }

    private fun queryText() = b.query.text?.toString().orEmpty().trim()

    /** Suggestions from history; we show them while the field is still empty. */
    private fun refreshHistory() {
        val history = prefs.webSearchHistory
        b.query.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, history)
        )
    }

    private fun showHistory() {
        if (queryText().isEmpty() && prefs.webSearchHistory.isNotEmpty()) {
            b.query.showDropDown()
        }
    }

    /**
     * State of the empty field.
     *
     * This used to simply show the list of added stations, looking exactly
     * the same as search results - there was no way to tell whether it was
     * leftovers from a previous search or something of your own. Now there
     * are two clearly labeled things: recent queries to repeat with a single
     * tap, and your own stations with a counter in the header.
     */
    private fun showAdded() {
        val added = prefs.discovered
        // Without this the list stays scrolled to where it was for the results,
        // and the first entries hide behind the header.
        adapter.submitList(added) { b.list.scrollToPosition(0) }
        renderHistoryChips()

        if (added.isEmpty()) {
            b.hint.setText(R.string.discover_empty)
            b.subhint.setText(R.string.discover_intro)
        } else {
            b.hint.text = getString(R.string.discover_added_header, added.size)
            b.subhint.setText(R.string.discover_added_hint)
        }
    }

    /** Chips with history - a single tap repeats the query. */
    private fun renderHistoryChips() {
        val history = prefs.webSearchHistory
        b.historyChips.removeAllViews()
        b.historyBox.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
        history.forEach { query ->
            val chip = Chip(this).apply {
                text = query
                isCheckable = false
                setOnClickListener {
                    b.query.setText(query)
                    b.query.setSelection(query.length)
                }
            }
            b.historyChips.addView(chip)
        }
    }

    /**
     * The catalog is public and shared by everyone, so it's not appropriate to
     * hit it on every keystroke. We wait until typing settles down.
     */
    private fun scheduleSearch(query: String) {
        searchJob?.cancel()
        if (query.trim().length < 2) {
            lastResults = emptyList()
            showAdded()
            return
        }
        // Searching - history and the added-count give way to the results
        b.historyBox.visibility = View.GONE
        b.subhint.text = ""

        searchJob = lifecycleScope.launch {
            delay(400)
            b.hint.setText(R.string.discover_searching)
            val found = sortResults(RadioBrowser.search(query))
            lastResults = found
            adapter.submitList(found.map { it.toStation() }) { b.list.scrollToPosition(0) }
            b.hint.text = if (found.isEmpty()) {
                getString(R.string.discover_nothing, query.trim())
            } else {
                prefs.pushWebSearch(query)
                refreshHistory()
                resources.getQuantityString(R.plurals.discover_results, found.size, found.size)
            }
        }
    }

    /**
     * Tapping a row opens details. It used to do the same thing as the button
     * next to it - the station would silently land on the list, with no trace on
     * screen, which made the first tap of "+" remove it and look ineffective.
     */
    private fun openDetails(station: Station) {
        val found = lastResults.firstOrNull { it.toStation().id == station.id }
        details.launch(
            if (found != null) {
                StationInfoActivity.intent(this, found)
            } else {
                StationInfoActivity.intent(this, station)
            }
        )
    }

    private fun toggle(station: Station) {
        if (prefs.isDiscovered(station.id)) {
            prefs.removeDiscovered(station.id)
        } else {
            prefs.addDiscovered(station)
        }
    }

    private fun showLogo(station: Station, view: ImageView) {
        ArtworkLoader.into(
            lifecycleScope,
            null,
            station.logoUrl?.let { android.net.Uri.parse(it) },
            metadata.logoResId(station),
            view
        )
    }
}
