package io.github.spysyweeb.beamline.map.layers

import android.app.Activity
import android.os.Bundle
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import java.util.concurrent.CountDownLatch

/** A full-screen MapView for [MapLibreControllerDeviceTest] (test code only, offline). */
class TestMapActivity : Activity() {
    lateinit var mapView: MapView
        private set

    /** Set on the main thread when the map is ready; [ready] opens then. */
    @Volatile
    var map: MapLibreMap? = null
        private set
    val ready = CountDownLatch(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        MapLibre.setConnected(false)
        val options = MapLibreMapOptions.createFromAttributes(this)
            .textureMode(false)
            .attributionEnabled(false)
            .logoEnabled(false)
            .compassEnabled(false)
        mapView = MapView(this, options)
        mapView.onCreate(savedInstanceState)
        setContentView(mapView)
        mapView.getMapAsync { m ->
            map = m
            ready.countDown()
        }
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        mapView.onDestroy()
        super.onDestroy()
    }
}
