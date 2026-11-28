// FILE: feature/about/src/main/kotlin/io/github/spysyweeb/beamline/feature/about/AboutScreens.kt
package io.github.spysyweeb.beamline.feature.about

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The system splash (core-splashscreen) with the Night drive icon; held while [keepOnScreen] is true. */
object Splash {
    fun install(activity: ComponentActivity, keepOnScreen: () -> Boolean): Unit = SplashHandoff.install(activity, keepOnScreen)
}

/** The reveal over the map: the icon stays put while a circle opens to the scene, about 0.7 s, held until [mapReady] (at most 2 s). */
@Composable
fun SplashReveal(mapReady: Boolean, onFinished: () -> Unit, modifier: Modifier = Modifier): Unit = SplashRevealContent(mapReady, onFinished, modifier)

@Composable
fun FirstRunNotice(onAccept: () -> Unit, modifier: Modifier = Modifier): Unit = FirstRunNoticeContent(onAccept, modifier)

/** The driver notice read again from About: the same text under a top bar with Back and its title; nothing to accept. */
@Composable
fun DriverNoticeScreen(onBack: () -> Unit, modifier: Modifier = Modifier): Unit = DriverNoticeScreenContent(onBack, modifier)

@Composable
fun AboutScreen(ui: AboutUi, onEvent: (AboutEvent) -> Unit, modifier: Modifier = Modifier): Unit = AboutScreenContent(ui, onEvent, modifier)

@Composable
fun LicencesScreen(notices: Notices, onBack: () -> Unit, modifier: Modifier = Modifier): Unit = LicencesScreenContent(notices, onBack, modifier)
