package io.github.spysyweeb.beamline.map.layers

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.acos

/**
 * The bitmaps the app's layers draw, each with its density so MapLibre's Style.addImage shows it
 * at its dp size (assets/markers/SPEC.md "Android export sizes").
 */
internal object MarkerImages {

    /** The eleven pieces of assets/markers/SPEC.md, exported in route candidate B and copied by sync_art.py. */
    private val PIECES: Map<String, Int> = mapOf(
        "arrow" to R.drawable.marker_arrow,
        "arrow_beam" to R.drawable.marker_arrow_beam,
        "arrow_brake_glow_day" to R.drawable.marker_arrow_brake_glow_day,
        "arrow_brake_glow_night" to R.drawable.marker_arrow_brake_glow_night,
        "arrow_brakes_lit" to R.drawable.marker_arrow_brakes_lit,
        "arrow_stale" to R.drawable.marker_arrow_stale,
        "puck_cone_day" to R.drawable.marker_puck_cone_day,
        "puck_cone_night" to R.drawable.marker_puck_cone_night,
        "puck_day" to R.drawable.marker_puck_day,
        "puck_night" to R.drawable.marker_puck_night,
        "puck_stale" to R.drawable.marker_puck_stale,
    )

    /** Image id ([Ids.marker]) to bitmap; Android picks the density bucket and scales to the screen's. */
    fun markers(res: Resources): Map<String, Bitmap> =
        PIECES.entries.associate { (piece, id) -> Ids.marker(piece) to BitmapFactory.decodeResource(res, id) }

    // ---- pins (decision 6.22: plain pins in blue B with white numerals, drawn here; drafts) ----

    /** Route casing B (#1F5BC4): white on it is 6.3:1 (docs/architecture-saved-places.md 8). */
    private val PIN_BLUE = Color.rgb(0x1F, 0x5B, 0xC4)
    private val PIN_REACHED = Color.rgb(0x8D, 0x96, 0xA0)   // the stale grey of the markers
    private const val PIN_W_DP = 28f
    private const val PIN_H_DP = 38f
    private const val RESULT_SCALE = 0.8f

    /** Every pin image: destination, destination reached, picked, and stops 1-9 plain and reached. */
    fun pins(res: Resources): Map<String, Bitmap> {
        val dm = res.displayMetrics
        val out = LinkedHashMap<String, Bitmap>()
        out[Ids.PIN_DEST] = pin(dm.density, dm.densityDpi, PIN_BLUE, Color.WHITE, Mark.Dot)
        out[Ids.PIN_DEST_REACHED] = pin(dm.density, dm.densityDpi, PIN_REACHED, Color.WHITE, Mark.Dot)
        out[Ids.PIN_PICKED] = pin(dm.density, dm.densityDpi, Color.WHITE, PIN_BLUE, Mark.Dot)
        // S11 (batch B): the results reuse the destination pin's art, smaller, so the selected one
        // (the white picked pin, full size, drawn on top) stands out; no new artwork
        out[Ids.PIN_RESULT] = pin(dm.density, dm.densityDpi, PIN_BLUE, Color.WHITE, Mark.Dot, RESULT_SCALE)
        for (n in 0..Ids.MAX_PIN_NUMBER) {
            out[Ids.pinStop(n, reached = false)] = pin(dm.density, dm.densityDpi, PIN_BLUE, Color.WHITE, Mark.Number(n))
            out[Ids.pinStop(n, reached = true)] = pin(dm.density, dm.densityDpi, PIN_REACHED, Color.WHITE, Mark.Number(n))
        }
        return out
    }

    // ---- the traffic light of the route ahead (docs/next-round.md item 22; approved by the owner on 2026-11-21) ----

    private const val LIGHT_W_DP = 13f
    private const val LIGHT_H_DP = 30f
    private val LIGHT_HOUSING = Color.rgb(0x23, 0x27, 0x2E)
    private val LIGHT_RED = Color.rgb(0xE5, 0x39, 0x35)
    private val LIGHT_AMBER = Color.rgb(0xF9, 0xA8, 0x25)
    private val LIGHT_GREEN = Color.rgb(0x2E, 0xA0, 0x4F)

    /**
     * A traffic light [LIGHT_W_DP] x [LIGHT_H_DP] dp: a dark housing with round corners and a white
     * edge (so it reads on the day and on the night map and on the route's blue), the three lamps
     * lit in their colours. It says "a light is here", never what the light shows.
     */
    fun light(res: Resources): Bitmap {
        val dm = res.displayMetrics
        val d = dm.density
        val w = (LIGHT_W_DP * d).toInt()
        val h = (LIGHT_H_DP * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.density = dm.densityDpi
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val edge = 1.25f * d
        val box = RectF(edge / 2, edge / 2, w - edge / 2, h - edge / 2)
        val corner = 3.5f * d
        p.style = Paint.Style.FILL
        p.color = LIGHT_HOUSING
        c.drawRoundRect(box, corner, corner, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = edge
        p.color = Color.WHITE
        c.drawRoundRect(box, corner, corner, p)
        p.style = Paint.Style.FILL
        val r = 3.1f * d
        val cx = w / 2f
        val step = (h - 2 * edge) / 3f
        for ((i, colour) in listOf(LIGHT_RED, LIGHT_AMBER, LIGHT_GREEN).withIndex()) {
            p.color = colour
            c.drawCircle(cx, edge + step * (i + 0.5f), r, p)
        }
        return bmp
    }

    private sealed interface Mark {
        data object Dot : Mark
        data class Number(val n: Int) : Mark
    }

    /**
     * A map pin [PIN_W_DP] x [PIN_H_DP] dp: a round head with a point at the bottom centre (the
     * layer anchors the image at its bottom), [body] filled, a 2 dp edge and the mark in [ink].
     */
    private fun pin(density: Float, densityDpi: Int, body: Int, ink: Int, mark: Mark, scale: Float = 1f): Bitmap {
        val w = (PIN_W_DP * scale * density).toInt()
        val h = (PIN_H_DP * scale * density).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.density = densityDpi
        val c = Canvas(bmp)
        val edge = 2f * scale * density
        val r = w / 2f - edge
        val cx = w / 2f
        val cy = r + edge
        // One outline: from the tip along the right tangent, round the head over the top, back
        // along the left tangent (two sub-paths would show the head's edge inside the pin).
        val tipY = h - edge * 0.6f
        val alpha = Math.toDegrees(acos((r / (tipY - cy)).toDouble())).toFloat()
        val shape = Path().apply {
            moveTo(cx, tipY)
            arcTo(RectF(cx - r, cy - r, cx + r, cy + r), 90f - alpha, -(360f - 2f * alpha), false)
            close()
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // soft shadow under the pin
        p.color = Color.argb(70, 0, 0, 0)
        c.drawOval(RectF(cx - r * 0.45f, h - edge * 1.6f, cx + r * 0.45f, h.toFloat()), p)
        // edge (the ink colour; white on the blue pins, blue on the white picked pin)
        p.style = Paint.Style.STROKE
        p.strokeWidth = edge * 2
        p.strokeJoin = Paint.Join.ROUND
        p.color = if (body == Color.WHITE) ink else Color.WHITE
        c.drawPath(shape, p)
        p.style = Paint.Style.FILL
        p.color = body
        c.drawPath(shape, p)
        p.color = ink
        when (mark) {
            Mark.Dot -> c.drawCircle(cx, cy, r * 0.38f, p)
            is Mark.Number -> {
                p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                p.textAlign = Paint.Align.CENTER
                p.textSize = r * 1.25f
                val fm = p.fontMetrics
                c.drawText(mark.n.toString(), cx, cy - (fm.ascent + fm.descent) / 2f, p)
            }
        }
        return bmp
    }
}
