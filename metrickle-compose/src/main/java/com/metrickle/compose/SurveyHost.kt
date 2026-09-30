package com.metrickle.compose

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrickle.ActiveSurvey
import com.metrickle.Contrast
import com.metrickle.Metrickle
import com.metrickle.MetrickleClient
import com.metrickle.Question
import com.metrickle.SurveyAnswer
import com.metrickle.SurveyRenderer
import kotlinx.coroutines.launch

/** Score range and default end labels per scored question type (same as the web UI). */
private val SCALES = mapOf(
    "nps" to Triple(0..10, "Not at all likely", "Extremely likely"),
    "csat" to Triple(1..5, "Very dissatisfied", "Very satisfied"),
    "ces" to Triple(1..7, "Very difficult", "Very easy"),
    "rating" to Triple(1..5, "Poor", "Excellent"),
)

/**
 * Shows active surveys in a Material 3 bottom sheet that meets WCAG 2.2 AA. Place it once at the
 * root of your UI, inside your `MaterialTheme` (its colours, dark theme included, are used):
 *
 * ```
 * MyTheme { Box { AppNavHost(); MetrickleSurveyHost() } }
 * ```
 *
 * Without a host (and without `surveys.onShow`), surveys never trigger. A custom `onShow`
 * renderer takes precedence over the host.
 *
 * @param colorScheme Overrides the ambient `MaterialTheme.colorScheme`.
 */
@Composable
public fun MetrickleSurveyHost(client: MetrickleClient? = Metrickle.client, colorScheme: ColorScheme? = null) {
    val c = client ?: return
    var active by remember { mutableStateOf<ActiveSurvey?>(null) }
    DisposableEffect(c) {
        val sub = c.surveys.setBuiltInRenderer(
            SurveyRenderer { s ->
                // One at a time; release the engine for a second one (only possible via show()).
                if (active == null) active = s else s.complete()
            },
        )
        onDispose { sub.cancel() }
    }
    val s = active ?: return
    MaterialTheme(colorScheme = colorScheme ?: MaterialTheme.colorScheme) {
        SurveySheet(s, c.config?.feedback?.branding?.accent, onClosed = { if (active === s) active = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SurveySheet(survey: ActiveSurvey, accentHex: String?, onClosed: () -> Unit) {
    val context = LocalContext.current
    // Remove animations / animator scale 0: no slide, the sheet just appears and disappears.
    val reducedMotion = remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val questions = survey.campaign.questions
    var index by remember { mutableIntStateOf(0) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val answers = remember { mutableStateMapOf<String, SurveyAnswer>() }
    val heading = remember { FocusRequester() }

    LaunchedEffect(survey) { survey.shown() }
    // Focus moves to the heading only when the sheet opens or the user advances.
    LaunchedEffect(index, done) { runCatching { heading.requestFocus() } }

    var closed by remember { mutableStateOf(false) }
    val close: () -> Unit = close@{
        if (closed) return@close
        closed = true
        if (!done) survey.dismiss(index)
        if (reducedMotion) onClosed() else scope.launch { sheetState.hide() }.invokeOnCompletion { onClosed() }
    }

    // Brand accent only when it reaches 4.5:1 against the sheet; otherwise the theme's primary.
    val container = MaterialTheme.colorScheme.surfaceContainerLow
    val accent = accentHex?.takeIf { it.matches(Regex("^#[0-9a-fA-F]{6}$")) && Contrast.ratio(it.lowercase(), Contrast.hex(container.toArgb())) >= 4.5 }
    val fill = accent?.let { hexColor(it) } ?: MaterialTheme.colorScheme.primary
    val onFill = accent?.let { hexColor(Contrast.textOn(it.lowercase())) } ?: MaterialTheme.colorScheme.onPrimary

    fun advance(a: SurveyAnswer?) {
        val q = questions[index]
        if (a != null) survey.answer(q, a)
        error = null
        if (index < questions.size - 1) {
            index++
        } else {
            done = true
            survey.complete()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            // Back, scrim tap or swipe down: the sheet has already animated away.
            if (!closed) {
                closed = true
                if (!done) survey.dismiss(index)
            }
            onClosed()
        },
        // Announced once as "Survey" when it opens (outer semantics win over the sheet's own "Bottom Sheet").
        modifier = Modifier.semantics { paneTitle = "Survey" },
        sheetState = sheetState,
        containerColor = container,
    ) {
        Column(
            Modifier
                .onPreviewKeyEvent { e ->
                    if (e.key == Key.Escape && e.type == KeyEventType.KeyUp) {
                        close()
                        true
                    } else {
                        false
                    }
                }
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val q = questions[index]
            Row(verticalAlignment = Alignment.Top) {
                Column(
                    Modifier
                        .weight(1f)
                        .padding(top = 12.dp, end = 8.dp)
                        .focusRequester(heading)
                        .focusable()
                        .semantics(mergeDescendants = true) {
                            heading()
                            liveRegion = LiveRegionMode.Polite
                        },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        if (questions.size > 1 && !done) "Quick survey · ${index + 1} of ${questions.size}" else "Quick survey",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (done) survey.campaign.thankYou?.takeIf { it.isNotBlank() } ?: "Thanks for your feedback"
                        else q.prompt + if (q.type == "choice" && q.multiple == true) " (choose all that apply)" else "",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                TextButton(
                    onClick = close,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = if (done) "Close" else "Close survey" },
                ) { Text("Close") }
            }

            if (done) {
                Button(
                    onClick = close,
                    modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = onFill),
                ) { Text("Done") }
                return@Column
            }

            val answer = answers[q.id]
            when (q.type) {
                "text" -> OutlinedTextField(
                    value = answer?.text ?: "",
                    onValueChange = { answers[q.id] = SurveyAnswer(text = it.take(1000)); error = null },
                    label = { Text(if (q.required) "Your answer (required)" else "Your answer (optional)") },
                    placeholder = q.placeholder?.let { { Text(it) } },
                    minLines = 3,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                )
                "choice" -> ChoiceQuestion(q, answer?.values.orEmpty(), fill) { answers[q.id] = SurveyAnswer(values = it); error = null }
                else -> ScaleQuestion(q, answer?.score, fill, onFill) { answers[q.id] = SurveyAnswer(score = it); error = null }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            }

            Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (!q.required) {
                    TextButton(onClick = { advance(null) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Skip") }
                }
                Button(
                    onClick = {
                        val a = normalize(answers[q.id])
                        if (a == null && q.required) {
                            error = if (q.type == "text") "Please write an answer, or close the survey." else "Please choose an answer, or close the survey."
                        } else {
                            advance(a)
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = onFill),
                ) { Text(if (index == questions.size - 1) "Submit" else "Next") }
            }
        }
    }
}

/** Null when nothing was answered. */
private fun normalize(a: SurveyAnswer?): SurveyAnswer? = when {
    a == null -> null
    a.score != null -> a
    !a.values.isNullOrEmpty() -> a
    !a.text.isNullOrBlank() -> a
    else -> null
}

/** A single-select group of buttons (≥ 48dp), each labelled with the end labels, exposed as radio buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScaleQuestion(q: Question, selected: Int?, fill: Color, onFill: Color, onSelect: (Int?) -> Unit) {
    val (range, lowDefault, highDefault) = SCALES[q.type] ?: SCALES.getValue("rating")
    val low = q.lowLabel?.takeIf { it.isNotBlank() } ?: lowDefault
    val high = q.highLabel?.takeIf { it.isNotBlank() } ?: highDefault
    val shape = RoundedCornerShape(8.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (n in range) {
                val isSelected = selected == n
                val face = if (q.type == "rating") "★ $n" else "$n"
                val base = if (q.type == "rating") "$n out of ${range.last} stars" else "$n"
                val label = when (n) {
                    range.first -> "$base, $low"
                    range.last -> "$base, $high"
                    else -> base
                }
                Box(
                    Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .background(if (isSelected) fill else Color.Transparent, shape)
                        .border(if (isSelected) 2.dp else 1.dp, if (isSelected) fill else MaterialTheme.colorScheme.outline, shape)
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(if (isSelected) null else n) })
                        .semantics { contentDescription = label }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        face,
                        color = if (isSelected) onFill else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
        }
        // Already part of each option's label; hidden from screen readers to avoid repetition.
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
            Text("${range.first} = $low", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f).width(8.dp))
            Text("${range.last} = $high", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Native radio buttons (single) or checkboxes (multiple), each row ≥ 48dp. */
@Composable
private fun ChoiceQuestion(q: Question, selected: List<String>, fill: Color, onChange: (List<String>) -> Unit) {
    val choices = q.choices.orEmpty()
    val multiple = q.multiple == true
    Column(if (multiple) Modifier else Modifier.selectableGroup()) {
        for (choice in choices) {
            val checked = choice in selected
            val rowModifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .let {
                    if (multiple) {
                        it.toggleable(value = checked, role = Role.Checkbox, onValueChange = { on -> onChange(if (on) selected + choice else selected - choice) })
                    } else {
                        it.selectable(selected = checked, role = Role.RadioButton, onClick = { onChange(if (checked) emptyList() else listOf(choice)) })
                    }
                }
            Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
                if (multiple) {
                    Checkbox(checked = checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = fill))
                } else {
                    RadioButton(selected = checked, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = fill))
                }
                Text(choice, modifier = Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

private fun hexColor(hex: String): Color = Color(0xff000000 or hex.removePrefix("#").toLong(16))
