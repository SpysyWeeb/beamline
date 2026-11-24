# MapLibre Native and kotlinx.serialization ship their own consumer rules in their AARs/JARs.

# valhalla-mobile ships consumer rules for its JNI callbacks (the HTTP tile client, never used:
# the engine configuration sets no tile_url). It also reads its error envelope
# {"code": n, "message": ...} with Moshi's Kotlin reflection, which needs the class and Kotlin's
# metadata kept (the same rules as :routing:demo's release build; docs/architecture.md 5.4 item 13).
-keep class com.valhalla.valhalla.ErrorResponse { *; }
-keep class kotlin.Metadata { *; }
