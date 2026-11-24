package io.github.spysyweeb.beamline.routing

import io.github.spysyweeb.beamline.routing.engine.EngineException
import io.github.spysyweeb.beamline.routing.engine.RoutingEngine
import io.github.spysyweeb.beamline.routing.valhalla.canonicalJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.zip.GZIPInputStream

/** Test data from android/routing/testdata (recorded with pyvalhalla 3.9.1 on the PA+OH device tiles). */
object Fixtures {
    fun text(name: String): String {
        val stream = Fixtures::class.java.getResourceAsStream("/$name") ?: error("test resource $name missing")
        val raw = if (name.endsWith(".gz")) GZIPInputStream(stream) else stream
        return raw.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun json(name: String): JsonObject = Json.parseToJsonElement(text(name)).jsonObject
}

/**
 * Replays recorded engine answers. A request that was not recorded fails the test, so a planner
 * that asks anything the Python reference did not ask is caught.
 */
class ReplayEngine(recordings: List<JsonObject>) : RoutingEngine {
    private val answers = recordings.associate { r -> canonicalJson(r["request"]!!) to r }
    val asked = mutableListOf<String>()

    override fun route(requestJson: String): String {
        val key = canonicalJson(Json.parseToJsonElement(requestJson))
        asked += key
        val r = answers[key] ?: throw AssertionError("request not recorded: $requestJson")
        r["error"]?.jsonObject?.let { throw EngineException(it["code"]!!.jsonPrimitive.int, it["message"]!!.jsonPrimitive.content) }
        return r["response"].toString()
    }

    override fun matrix(requestJson: String): String = throw AssertionError("matrix not recorded")

    /** The address street's probe (AddressTie), recorded by reference_routes.py as asked. */
    override fun traceAttributes(requestJson: String): String = route(requestJson)

    companion object {
        /** One engine answering from every recording of [fixtures] (the same request is the same answer). */
        fun from(vararg fixtures: String) = ReplayEngine(fixtures.flatMap { f -> Fixtures.json(f)["recordings"]!!.jsonArray.map { it.jsonObject } })
    }
}
