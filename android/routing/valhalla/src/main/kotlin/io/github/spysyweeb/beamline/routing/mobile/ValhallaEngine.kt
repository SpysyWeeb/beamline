package io.github.spysyweeb.beamline.routing.mobile

import com.valhalla.valhalla.Valhalla
import com.valhalla.valhalla.ValhallaException
import io.github.spysyweeb.beamline.routing.config.EngineConfig
import io.github.spysyweeb.beamline.routing.engine.EngineException
import io.github.spysyweeb.beamline.routing.engine.RoutingEngine
import java.io.Closeable
import java.io.File

/**
 * valhalla-mobile 0.6.4 (Valhalla 3.9.1) as a [RoutingEngine].
 *
 * Opened from a config file on disk through valhalla-mobile's `Valhalla(configPath)` constructor,
 * and called through its raw JSON methods (`routeRaw`, `matrixRaw`). Neither the config nor a
 * request passes through the wrapper's generated Kotlin models, so no key can be dropped on the
 * way (the device needs service_limits.allow_hard_exclusions, max_alternates and the CostMatrix
 * settings, and requests carry costing options the models may not know).
 *
 * Building the engine opens and memory-maps the tile tar; keep one instance and close it when done.
 * Calls are serialised by valhalla-mobile; run them off the main thread.
 */
class ValhallaEngine private constructor(private val valhalla: Valhalla, val configFile: File) : RoutingEngine, Closeable {

    override fun route(requestJson: String): String = call { valhalla.routeRaw(requestJson) }

    override fun matrix(requestJson: String): String = call { valhalla.matrixRaw(requestJson) }

    override fun traceAttributes(requestJson: String): String = call { valhalla.traceAttributesRaw(requestJson) }

    private inline fun call(block: () -> String): String = try {
        envelopeCheck(block())
    } catch (e: ValhallaException.Internal) {
        throw EngineException(errorCode(e.message), e.message ?: "Valhalla error", e)
    } catch (e: ValhallaException) {
        throw EngineException(null, e.message ?: "Valhalla error", e)
    }

    override fun close() = valhalla.close()

    companion object {
        /** The Valhalla release inside valhalla-mobile 0.6.4 (its src/valhalla submodule = tag 3.9.1). */
        const val VALHALLA_VERSION = "3.9.1"

        /** Opens the engine with an existing config file (e.g. the one RoutingPackManager writes). */
        fun open(configFile: File): ValhallaEngine {
            require(configFile.isFile) { "no engine config at $configFile" }
            return ValhallaEngine(Valhalla(configFile.absolutePath), configFile)
        }

        /**
         * Writes the engine config for [tileTar] (EngineConfig: the bundled base config, the device
         * settings, the absolute tar path) to [configFile], then opens the engine.
         */
        fun openTar(tileTar: File, configFile: File): ValhallaEngine {
            require(tileTar.isFile) { "no tile tar at $tileTar" }
            configFile.parentFile?.mkdirs()
            configFile.writeText(EngineConfig.build(tileTar.absolutePath).toString(), Charsets.UTF_8)
            return open(configFile)
        }

        private val envelope = Regex("""^\{\s*"code"\s*:\s*(-?\d+)\s*,\s*"message"\s*:\s*"(.*)"\s*\}$""", RegexOption.DOT_MATCHES_ALL) // ICU (Android) needs the \} escaped

        /**
         * valhalla-mobile recognises its error envelope `{"code": n, "message": "..."}` with Moshi
         * reflection; if R8 or a Moshi change ever broke that, the envelope would come back as an
         * answer. Recognise it here as well, so an error never reaches the response parser.
         */
        fun envelopeCheck(body: String): String {
            if (body.length < 400) envelope.find(body.trim())?.let { m ->
                throw EngineException(m.groupValues[1].toIntOrNull(), "ValhallaError(code=${m.groupValues[1]}, ${m.groupValues[2]})")
            }
            return body
        }

        /**
         * Valhalla's error code from valhalla-mobile's message. The wrapper keeps the code only in the
         * text of ValhallaException.Internal: ErrorResponse.toString() = "ValhallaError(code=171, ...)".
         */
        fun errorCode(message: String?): Int? =
            message?.let { Regex("""code=(-?\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
    }
}
