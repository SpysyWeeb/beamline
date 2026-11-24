package io.github.spysyweeb.beamline.feature.search

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Icons cut from the style's sprite sheet (decision 6.20): `style/sprites/light@2x` by day and
 * `dark@2x` by night, from the APK's assets (P syncs them into :app's assets; :feature:search's own
 * test APK adds :app's assets folder, docs/architecture.md 5.1 S). Each sheet is looked for once per
 * process, found or not, and each icon is cut once, found or not: a missing sheet or name is
 * remembered as missing, so it is never read again (a plain rounded square is drawn instead, never
 * a crash).
 */
internal class SpriteCache {
    private class Sheet(val png: Bitmap, val frames: Map<String, IntArray>)

    // Kotlin's getOrPut would run its lambda again for a stored null: containsKey keeps a miss.
    private val sheets = HashMap<Boolean, Sheet?>()
    private val icons = HashMap<Pair<Boolean, String>, ImageBitmap?>()

    /** How many times a sheet was looked for in the assets (tests). */
    var sheetReads = 0
        private set

    /** The icon [name] of the day or night sheet; null when the sheet or the name is missing. [open] opens an asset. */
    @Synchronized
    fun icon(open: (String) -> InputStream, night: Boolean, name: String): ImageBitmap? {
        val key = night to name
        if (icons.containsKey(key)) return icons[key]
        val s = sheet(open, night)
        val f = s?.frames?.get(name)
        val img = if (s == null || f == null) null else try {
            Bitmap.createBitmap(s.png, f[0], f[1], f[2], f[3]).asImageBitmap()
        } catch (_: IllegalArgumentException) {
            null   // a frame outside the sheet: the plain square
        }
        icons[key] = img
        return img
    }

    private fun sheet(open: (String) -> InputStream, night: Boolean): Sheet? {
        if (sheets.containsKey(night)) return sheets[night]
        sheetReads++
        val base = if (night) "style/sprites/dark@2x" else "style/sprites/light@2x"
        val sheet = try {
            val json = open("$base.json").use { it.readBytes().toString(Charsets.UTF_8) }
            val frames = Json.parseToJsonElement(json).jsonObject.mapValues { (_, v) ->
                val o = v.jsonObject
                intArrayOf(o["x"]!!.jsonPrimitive.int, o["y"]!!.jsonPrimitive.int, o["width"]!!.jsonPrimitive.int, o["height"]!!.jsonPrimitive.int)
            }
            val png = open("$base.png").use { BitmapFactory.decodeStream(it) }
            if (png == null) null else Sheet(png, frames)
        } catch (_: Exception) {
            null   // no sprite sheet in this APK: icons fall back to plain squares
        }
        sheets[night] = sheet
        return sheet
    }
}

/** The one cache of the process. */
internal val SpriteSheets = SpriteCache()

/** One sprite icon at [size] (decorative: rows and sheets carry the words for screen readers). */
@Composable
internal fun SpriteIcon(name: String, night: Boolean, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    val assets = LocalContext.current.assets
    val img = remember(name, night) { SpriteSheets.icon({ assets.open(it) }, night, name) }
    if (img != null) {
        Image(bitmap = img, contentDescription = null, modifier = modifier.size(size))
    } else {
        Box(modifier.size(size).background(palette(night).subtle, RoundedCornerShape(5.dp)))
    }
}
