package net.mspanc.twinsenradio.data

/**
 * Ordering of the station list.
 *
 * [DEFAULT] is the order from the file - arranged thematically, so similar
 * stations sit next to each other. [MOST_PLAYED] counts actual plays and,
 * after a few weeks of driving, is usually the most convenient: what you
 * listen to naturally ends up at the top.
 */
enum class StationSort(val label: String) {
    DEFAULT("Ordre intégré"),
    MOST_PLAYED("Les plus écoutées"),
    NAME("Alphabétique"),
    GENRE("Par genre");

    companion object {
        val LABELS get() = entries.map { it.label }
        fun at(index: Int) = entries.getOrElse(index) { DEFAULT }
    }
}

/**
 * Ordering of results from the catalog.
 *
 * [POPULARITY] is the order in which the catalog returns them - by number of
 * votes, which does a decent job of filtering out dead and random entries.
 */
enum class DiscoverSort(val label: String) {
    POPULARITY("Popularité"),
    NAME("Alphabétique"),
    BITRATE("Qualité du flux"),
    COUNTRY("Pays");

    companion object {
        val LABELS get() = entries.map { it.label }
        fun at(index: Int) = entries.getOrElse(index) { POPULARITY }
    }
}
