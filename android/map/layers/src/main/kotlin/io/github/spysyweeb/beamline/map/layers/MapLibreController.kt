// FILE: map/layers/src/main/kotlin/io/github/spysyweeb/beamline/map/layers/MapLibreController.kt
package io.github.spysyweeb.beamline.map.layers

import android.content.Context
import io.github.spysyweeb.beamline.api.map.CameraTarget
import io.github.spysyweeb.beamline.api.map.MapController
import io.github.spysyweeb.beamline.api.map.MapGesture
import io.github.spysyweeb.beamline.api.map.MapScene
import io.github.spysyweeb.beamline.api.map.MapTheme
import kotlinx.coroutines.flow.SharedFlow
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/**
 * MapController on one MapLibreMap (main thread only). Route colours from route_colors.json
 * ("selected": "B"), marker PNGs per assets/markers/SPEC.md, both copied in by sync_art.py.
 */
class MapLibreController(context: Context, map: MapLibreMap) : MapController {
    override val gestures: SharedFlow<MapGesture> get() = layers.gestures
    override fun show(scene: MapScene): Unit = layers.show(scene)
    override fun moveCamera(target: CameraTarget): Unit = layers.moveCamera(target)
    /** Re-adds sources and layers after every style (re)load; layers go before the style layer "roads_oneway". */
    fun onStyleLoaded(style: Style, theme: MapTheme): Unit = layers.onStyleLoaded(style, theme)
    fun release(): Unit = layers.release()

    private val layers = Layers(context, map)
}
