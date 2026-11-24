package io.github.spysyweeb.beamline.search.bench

import io.github.spysyweeb.beamline.search.AddressInfo
import io.github.spysyweeb.beamline.search.SearchEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Answers every query of conformance/search_queries.json in the form of the Python reference
 * (reference-search.json), so conformance/compare.py can check answers made on a device
 * (Android's ICU, ART and bionic) and not only on the JVM.
 */
object ConformanceDump {
    fun run(engine: SearchEngine, queriesJson: String): JsonArray {
        val queries = Json.parseToJsonElement(queriesJson).jsonObject["queries"]!!.jsonArray.map { it.jsonObject }
        return buildJsonArray {
            for (q in queries) {
                var lat: Double? = null
                var lon: Double? = null
                q["at"]?.let { SearchBench.POINTS.getValue(it.jsonPrimitive.content).let { p -> lat = p.lat; lon = p.lon } }
                q["near"]?.let { a ->
                    fun v(e: kotlinx.serialization.json.JsonElement): Double? = when {
                        e is JsonNull -> null
                        e.jsonPrimitive.isString -> when (e.jsonPrimitive.content) {
                            "nan" -> Double.NaN; "inf" -> Double.POSITIVE_INFINITY; else -> Double.NEGATIVE_INFINITY
                        }
                        else -> e.jsonPrimitive.content.toDouble()
                    }
                    lat = v(a.jsonArray[0]); lon = v(a.jsonArray[1])
                }
                val r = engine.search(q["q"]!!.jsonPrimitive.content, lat, lon, 10)
                add(buildJsonObject {
                    put("id", q["id"]!!.jsonPrimitive.content)
                    put("results", buildJsonArray {
                        for (x in r.results) add(buildJsonObject {
                            put("id", x.id); put("kind", x.kind.code); put("title", x.title); put("subtitle", x.subtitle)
                            put("category", x.categoryLabel); put("type", x.categoryId); put("lat", x.lat); put("lon", x.lon)
                            put("distance_km", x.distanceKm); put("brand_id", x.brandId); put("score", x.score)
                            put("how", x.how); put("tier", x.tier); put("house_number", x.address?.houseNumber)
                            put("street_name", x.address?.streetName)
                            put("house_located", x.address?.let { JsonPrimitive(it.accuracy == AddressInfo.Accuracy.POINT) } ?: JsonNull)
                        })
                    })
                })
            }
        }
    }
}
