package net.mspanc.twinsenradio.playback

/**
 * Map of "metadata field -> Polish label". In diagnostic mode each field
 * gets exactly this value, so a glance at the AID / HU screen immediately
 * shows which field ends up where.
 *
 * The labels are deliberately short - the display between the gauges in the
 * Passat has little room, and longer strings get truncated with an ellipsis.
 *
 * They are also deliberately free of Polish diacritics. The Android Auto
 * projection itself renders diacritics correctly, but the "Media Playback
 * Status" window in DHU reads UTF-8 as if it were Latin-1, so instead of
 * "TYT.WYSW" it shows "TYT.WYAW". Since the label's job is to identify the
 * field, not to look pretty, ASCII is readable everywhere - including on the
 * dashboard screen, whose capabilities we know nothing for certain about.
 */
object DiagnosticFields {

    data class Field(val api: String, val label: String)

    val TEXT: List<Field> = listOf(
        Field("title", "TITRE"),
        Field("artist", "ARTISTE"),
        Field("albumTitle", "ALBUM"),
        Field("albumArtist", "ART.ALBUM"),
        Field("displayTitle", "TITRE.AFF"),
        Field("subtitle", "SOUS-TITRE"),
        Field("description", "DESCRIPTION"),
        Field("station", "STATION"),
        Field("genre", "GENRE"),
        Field("composer", "COMPOSITEUR"),
        Field("writer", "AUTEUR"),
        Field("conductor", "CHEF"),
        Field("compilation", "COMPILATION")
    )

    /** Numeric fields get recognizable, non-arbitrary values. */
    const val TRACK_NUMBER = 11
    const val TOTAL_TRACKS = 22
    const val DISC_NUMBER = 3
    const val TOTAL_DISCS = 4
    const val RECORDING_YEAR = 1979
    const val RELEASE_YEAR = 1983

    val NUMERIC_LEGEND: List<String> = listOf(
        "trackNumber = $TRACK_NUMBER",
        "totalTrackCount = $TOTAL_TRACKS",
        "discNumber = $DISC_NUMBER",
        "totalDiscCount = $TOTAL_DISCS",
        "recordingYear = $RECORDING_YEAR",
        "releaseYear = $RELEASE_YEAR"
    )

    fun value(field: Field, withApiName: Boolean): String =
        if (withApiName) "${field.label}<${field.api}>" else field.label

    fun legend(withApiName: Boolean): String = buildString {
        TEXT.forEach { appendLine("${it.api} = ${value(it, withApiName)}") }
        NUMERIC_LEGEND.forEach { appendLine(it) }
    }
}
