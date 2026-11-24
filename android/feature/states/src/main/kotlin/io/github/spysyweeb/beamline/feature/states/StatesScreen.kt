// FILE: feature/states/src/main/kotlin/io/github/spysyweeb/beamline/feature/states/StatesScreen.kt
package io.github.spysyweeb.beamline.feature.states

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

enum class StatesMode { FIRST_RUN, MANAGE }

/** "Choose your state" (FIRST_RUN) or "States" (MANAGE). [onClose]: Not now, "Look at the map while you wait", back, or the first state installed. */
@Composable
fun StatesScreen(getPacks: GetPacks, mode: StatesMode, onClose: () -> Unit, modifier: Modifier = Modifier): Unit = StatesScreenImpl(getPacks, mode, onClose, modifier)
