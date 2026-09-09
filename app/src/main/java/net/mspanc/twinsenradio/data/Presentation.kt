package net.mspanc.twinsenradio.data

/**
 * What should appear in each line of the description on the dashboard.
 */
enum class LineContent(val label: String) {

    TITLE("Titre du morceau"),
    ARTIST("Artiste"),
    ARTIST_ALBUM("Artiste · album"),
    TITLE_ALBUM("Titre · album"),
    TRACK_FULL("Artiste — titre"),
    STATION("Nom de la station"),
    CLOCK("Heure / Auto [heure/station]"),
    EMPTY("Vide");

    companion object {

        fun labels(includeYear: Boolean): List<String> = entries.map {
            when (it) {
                ARTIST_ALBUM, TITLE_ALBUM ->
                    it.label + if (includeYear) " (avec année)" else " (sans année)"

                else -> it.label
            }
        }

        fun at(index: Int) = entries.getOrElse(index) { TITLE }
    }
}

/**
 * Which line on the dashboard.
 */
enum class Line(val label: String, val hint: String) {

    TOP(
        "Ligne 1 — haut",
        "Sur l'écran conducteur : ligne supérieure. Sur l'écran central : petite ligne sous le titre."
    ),

    MIDDLE(
        "Ligne 2 — milieu",
        "Visible uniquement sur l'écran conducteur. C'est l'emplacement idéal pour afficher le nom de la station."
    ),

    BOTTOM(
        "Ligne 3 — bas",
        "Sur l'écran conducteur : ligne principale en gras. Sur l'écran central : grande ligne."
    )
}

/** Whether a clock replaces the artwork, and if so, in what form. */
enum class ClockFace(val label: String) {

    NONE("Pochette / logo de la station"),
    DIGITAL("Horloge numérique"),
    ANALOG("Horloge analogique");

    companion object {
        val LABELS get() = entries.map { it.label }

        fun at(index: Int) = entries.getOrElse(index) { NONE }
    }
}

/** Color scheme for the clock drawn in place of the artwork. */
object ClockColors {

    val BACKGROUNDS: List<Pair<String, Int>> = listOf(
        "Noir" to 0xFF000000.toInt(),
        "Bleu marine (thème de l'application)" to 0xFF0B3D91.toInt(),
        "Gris foncé" to 0xFF202124.toInt(),
        "Blanc" to 0xFFFFFFFF.toInt()
    )

    val FOREGROUNDS: List<Pair<String, Int?>> = listOf(
        "Automatique — contraste avec le fond" to null,
        "Blanc" to 0xFFFFFFFF.toInt(),
        "Noir" to 0xFF000000.toInt(),
        "Ambre" to 0xFFF2A900.toInt()
    )

    fun background(index: Int) =
        BACKGROUNDS.getOrElse(index) { BACKGROUNDS[0] }.second

    fun foreground(index: Int, backgroundColor: Int): Int {
        FOREGROUNDS.getOrElse(index) { FOREGROUNDS[0] }.second?.let {
            return it
        }

        val r = (backgroundColor shr 16 and 0xFF) / 255.0
        val g = (backgroundColor shr 8 and 0xFF) / 255.0
        val b = (backgroundColor and 0xFF) / 255.0

        val luminance =
            0.2126 * r +
            0.7152 * g +
            0.0722 * b

        return if (luminance > 0.5)
            0xFF000000.toInt()
        else
            0xFFFFFFFF.toInt()
    }

    val BACKGROUND_LABELS get() = BACKGROUNDS.map { it.first }
    val FOREGROUND_LABELS get() = FOREGROUNDS.map { it.first }
}

/**
 * Complete set of description settings.
 */
data class Presentation(
    val top: LineContent,
    val middle: LineContent,
    val bottom: LineContent,
    val clockFace: ClockFace
) {

    fun contentFor(line: Line): LineContent = when (line) {
        Line.TOP -> top
        Line.MIDDLE -> middle
        Line.BOTTOM -> bottom
    }

    val needsClock: Boolean
        get() =
            clockFace != ClockFace.NONE ||
            top == LineContent.CLOCK ||
            middle == LineContent.CLOCK ||
            bottom == LineContent.CLOCK

    companion object {

        val DEFAULT = Presentation(
            top = LineContent.ARTIST_ALBUM,
            middle = LineContent.STATION,
            bottom = LineContent.TITLE,
            clockFace = ClockFace.NONE
        )
    }
}
