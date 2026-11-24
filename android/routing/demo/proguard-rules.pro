# valhalla-mobile ships consumer rules for its JNI callbacks (the HTTP tile client). It also finds
# its error envelope {"code": n, "message": ...} with Moshi's Kotlin reflection, which needs the
# class and Kotlin's metadata kept. (ValhallaEngine checks the envelope itself as well.)
-keep class com.valhalla.valhalla.ErrorResponse { *; }
-keep class kotlin.Metadata { *; }
