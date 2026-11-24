package io.github.spysyweeb.beamline.routing.packs

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Small synthetic packs for the rule tests: "tiles" of a few hundred bytes whose header carries a
 * Valhalla version at byte 16 (the only part of a tile the pack manager reads), written with the
 * same tar writer and a manifest in packbuilder's format. They run without the real packs.
 */
object MiniPacks {
    fun tile(path: String, version: String = "3.9.1", salt: Int = 0): ByteArray {
        val b = ByteArray(300 + path.length * 7 + salt)
        path.toByteArray().copyInto(b, 40)
        version.toByteArray().copyInto(b, 16)
        b[b.size - 1] = salt.toByte()
        return b
    }

    /** Writes <dir>/<state>/manifest.json + tar; [tiles] maps path -> bytes; [manifestSha] overrides hashes. */
    fun write(
        dir: File, state: String, build: String, tiles: Map<String, ByteArray>, version: String = "3.9.1",
        manifestSha: Map<String, String> = emptyMap(),
    ): PackSource {
        val d = File(dir, state).apply { mkdirs() }
        val tar = File(d, "routing-$state.tar")
        val names = tiles.keys.sorted()
        TileTar.write(tar, names, 1_700_000_000L) { tiles.getValue(it) }
        val manifest = buildJsonObject {
            put("format", RoutingPackManifest.FORMAT)
            put("state", state)
            put("graph_build", build)
            put("valhalla_version", version)
            put("tar", tar.name)
            put("tar_bytes", tar.length())
            put("tar_sha256", RoutingPackManager.sha256(tar))
            put("tiles", JsonArray(names.map { p ->
                buildJsonObject {
                    put("path", p)
                    put("level", p.substringBefore('/').toInt())
                    put("bytes", tiles.getValue(p).size)
                    put("sha256", manifestSha[p] ?: RoutingPackManager.sha256(tiles.getValue(p)))
                }
            }))
        }
        File(d, "manifest.json").writeText(manifest.toString())
        return PackSource.fromDir(d)
    }
}
