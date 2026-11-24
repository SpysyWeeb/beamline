package io.github.spysyweeb.beamline.routing.engine

/**
 * The routing engine as the planner sees it: Valhalla actions with JSON in and JSON out. On the
 * phone this is valhalla-mobile (:routing:valhalla), in tests a fake that replays recorded answers.
 * Calls block; run them off the main thread. One engine is not used from two threads at once.
 */
interface RoutingEngine {
    /** Valhalla `route`. Returns the response body, or throws [EngineException]. */
    fun route(requestJson: String): String

    /** Valhalla `sources_to_targets`. Returns the response body, or throws [EngineException]. */
    fun matrix(requestJson: String): String

    /**
     * Valhalla `trace_attributes` (batch C1: the lane counts of the road before a lane that ends in
     * a turn). Returns the response body, or throws [EngineException]; an engine without it throws.
     */
    fun traceAttributes(requestJson: String): String = throw EngineException(null, "trace_attributes is not available")
}

/** The engine refused a request. [code] is Valhalla's error code (170, 171, 442, ...) when known. */
class EngineException(val code: Int?, message: String, cause: Throwable? = null) : Exception(message, cause)
