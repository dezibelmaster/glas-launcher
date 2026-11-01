package de.glas.launcher.videos

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Background extension for the Glas Launcher: calm nature videos from Wikimedia Commons,
 * all CC0 (public domain), as 1080p WebM. Real time and a fixed camera only, no time-lapse. The launcher reads
 * content://de.glas.launcher.videos/wallpapers and downloads each video once.
 */
class VideoProvider : ContentProvider() {

    private class Video(val title: String, val subtitle: String, val uri: String)

    private val videos = listOf(
        Video("Wellen am Nordseestrand", "Strandkörbe, Nordsee", "https://upload.wikimedia.org/wikipedia/commons/transcoded/e/e2/Strandwellenfrequenz_%28etwa_0%2C3_Hertz%29.webm/Strandwellenfrequenz_%28etwa_0%2C3_Hertz%29.webm.1080p.vp9.webm"),
        Video("Guide Falls", "Wasserfall, Australien", "https://upload.wikimedia.org/wikipedia/commons/transcoded/8/86/Guide_Falls.webm/Guide_Falls.webm.1080p.vp9.webm"),
        Video("Delaneys Falls", "Wasserfall, Australien", "https://upload.wikimedia.org/wikipedia/commons/transcoded/8/8b/Delaneys_Falls.webm/Delaneys_Falls.webm.1080p.vp9.webm"),
        Video("Athirapally-Wasserfall", "Kerala, Indien", "https://upload.wikimedia.org/wikipedia/commons/transcoded/a/a0/Athirapally_falls.webm/Athirapally_falls.webm.1080p.vp9.webm"),
        Video("Bach im Feuchtgebiet", "Benvoulin, Kanada", "https://upload.wikimedia.org/wikipedia/commons/transcoded/c/c3/Creek_babbling_through_Benvoulin_wetlands.webm/Creek_babbling_through_Benvoulin_wetlands.webm.1080p.vp9.webm"),
        Video("Bach bei Schneefall", "Kanada", "https://upload.wikimedia.org/wikipedia/commons/transcoded/c/c7/KLO_Creek_gurgles_during_an_early_winter_snowfall.webm/KLO_Creek_gurgles_during_an_early_winter_snowfall.webm.1080p.vp9.webm"),
        Video("Glitzernde Schneekristalle am Bach", "Kanada", "https://upload.wikimedia.org/wikipedia/commons/transcoded/f/fa/Snow_crystals_scintillate_over_Bear_Creek.webm/Snow_crystals_scintillate_over_Bear_Creek.webm.1080p.vp9.webm"),
        Video("Schneefall am Zaun", "Winter", "https://upload.wikimedia.org/wikipedia/commons/transcoded/b/ba/FluffySnowfallByFence.webm/FluffySnowfallByFence.webm.1080p.vp9.webm"),
        Video("Winterlicher See im Gegenlicht", "Okanagan Lake, Kanada", "https://upload.wikimedia.org/wikipedia/commons/transcoded/9/97/Lake_Okanagan_in_Winter_near_Maude_Roxby_Wetlands.webm/Lake_Okanagan_in_Winter_near_Maude_Roxby_Wetlands.webm.1080p.vp9.webm"),
        Video("Schnee im Nadelwald", "Kanada", "https://upload.wikimedia.org/wikipedia/commons/transcoded/e/e4/Snow_falls_on_evergreens_protected_by_a_rocky_outcrop.webm/Snow_falls_on_evergreens_protected_by_a_rocky_outcrop.webm.1080p.vp9.webm"),
        Video("Stille Seemündung", "Mission Creek, Kanada", "https://upload.wikimedia.org/wikipedia/commons/transcoded/3/32/Mouth_of_Mission_Creek.webm/Mouth_of_Mission_Creek.webm.1080p.vp9.webm"),
        Video("Möwen auf der Sandbank", "Spiegelglattes Wasser", "https://upload.wikimedia.org/wikipedia/commons/transcoded/c/c8/Gulls_on_Sandbar.webm/Gulls_on_Sandbar.webm.1080p.vp9.webm"),
    )

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val cursor = MatrixCursor(arrayOf("title", "subtitle", "type", "uri"))
        if (uri.lastPathSegment == "wallpapers") {
            for (v in videos) cursor.addRow(arrayOf(v.title, v.subtitle, "video", v.uri))
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
