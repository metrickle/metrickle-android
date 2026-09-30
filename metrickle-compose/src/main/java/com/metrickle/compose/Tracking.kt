package com.metrickle.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import com.metrickle.Metrickle
import com.metrickle.TapTargets

/**
 * Sends `$screen` when this destination enters the composition (and again if [name] changes).
 * Call it at the top of each navigation destination; with one Activity, also set
 * `MetrickleOptions(automaticScreenTracking = false)` so the Activity isn't counted as a screen.
 */
@Composable
public fun TrackScreen(name: String, properties: Map<String, Any?>? = null) {
    LaunchedEffect(name) { Metrickle.screen(name, properties) }
}

/**
 * Names this element for friction reports: `$rage_click` uses [id] as its `selector` (Compose has
 * no view ids). Also sets `testTag`. Observes touches without consuming them.
 */
public fun Modifier.metrickleTag(id: String): Modifier = this
    .testTag(id)
    .pointerInput(id) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            TapTargets.note(id)
        }
    }
