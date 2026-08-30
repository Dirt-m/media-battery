package app.mediabattery.ui.friction

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mediabattery.R
import app.mediabattery.ui.theme.MediaBatteryTheme
import app.mediabattery.ui.theme.SbColors
import site.dirt23.battery.core.friction.FrictionTask
import site.dirt23.battery.core.friction.FrictionTaskKind
import site.dirt23.battery.core.friction.FrictionTaskPool
import kotlin.random.Random

// The deliberate friction gate, a port of the extension's friction.js. The questions
// come from the core pool; this file is the rendering, the submit check, and the why box.
//
// Two rules. Answers are checked only when confirm is pressed: live marking would be an
// oracle, since the counting tasks become "increment until it turns green". And the cost
// is flat, always `count` questions however many attempts it takes, because an
// unpredictable toll invites turning the gate off altogether.

private val FieldBg = Color(0xFF0F1318)
private val FocusLine = Color(0xFF8B98A5)
private val ConfirmInk = Color(0xFF0F1115)

/** Type scale. Fixed sizes when the gate rides an overlay, a roomier set on a screen. */
private class GateSizes(
    val title: TextUnit,
    val label: TextUnit,
    val target: TextUnit,
    val input: TextUnit,
    val button: TextUnit,
    val status: TextUnit,
)

private val DefaultSizes = GateSizes(22.sp, 17.sp, 17.sp, 16.sp, 15.sp, 14.sp)
private val LargeSizes = GateSizes(18.sp, 15.sp, 15.sp, 15.sp, 14.sp, 13.sp)

private fun wordsIn(s: String): Int = s.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

/** No text menu, so long pressing a field can never offer Paste. */
private object NoTextToolbar : TextToolbar {
    override val status: TextToolbarStatus get() = TextToolbarStatus.Hidden

    override fun hide() {}

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {}
}

/** Reads as permanently empty, so every other paste route hands back nothing. */
private class EmptyClipboard(private val delegate: Clipboard) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = null

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {}

    override val nativeClipboard: android.content.ClipboardManager
        get() = delegate.nativeClipboard
}

/**
 * A change that has to be paid for: what lands once the gate is passed, and what to put
 * back if it is not. The settings page holds one at a time, so every section can ask for
 * the gate without drawing one of its own.
 */
data class GateRequest(val onCancel: () -> Unit = {}, val onPaid: () -> Unit)

@Composable
fun FrictionGate(
    title: String,
    confirmLabel: String,
    count: Int,
    askWhy: Boolean,
    onCancel: (() -> Unit)?,
    onSuccess: () -> Unit,
    modifier: Modifier = Modifier,
    whyMinWords: Int = 5,
    large: Boolean = false,
) {
    val questionCount = count.coerceAtLeast(1)
    val minWords = if (askWhy) whyMinWords.coerceAtLeast(1) else 0
    val sizes = if (large) LargeSizes else DefaultSizes

    // A wrong answer bumps the round, which redraws every question and clears every
    // answer. The why box survives it: only the questions are rebuilt, same as the JS.
    var round by remember { mutableIntStateOf(0) }
    val questions = remember(round, questionCount) {
        FrictionTaskPool.buildQuestions(questionCount, Random.Default)
    }
    val answers = remember(round, questionCount) {
        mutableStateListOf<String>().apply { repeat(questionCount) { add("") } }
    }
    var why by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    val whyWords = wordsIn(why)
    val whyMet = !askWhy || whyWords >= minWords

    val whyShortMessage = stringResource(R.string.friction_status_why_short, minWords)
    val wrongMessage = stringResource(R.string.friction_status_wrong)

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(round) { firstFocus.requestFocus() }

    val submit = submit@{
        val answersOk = questions.indices.all { questions[it].check(answers[it]) }
        if (answersOk && whyMet) {
            onSuccess()
            return@submit
        }
        if (answersOk) {
            // The answers were right; only the why box is short, so keep them.
            status = whyShortMessage
            firstFocus.requestFocus()
            return@submit
        }
        status = wrongMessage
        round++
    }

    val clipboard = LocalClipboard.current
    val emptyClipboard = remember(clipboard) { EmptyClipboard(clipboard) }

    CompositionLocalProvider(
        LocalTextToolbar provides NoTextToolbar,
        LocalClipboard provides emptyClipboard,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 22.dp),
        ) {
            Text(
                text = title,
                color = SbColors.Ink,
                fontSize = sizes.title,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.height(20.dp))

            if (askWhy) {
                WhyField(
                    value = why,
                    onValueChange = { why = it },
                    words = whyWords,
                    minWords = minWords,
                    met = whyMet,
                    sizes = sizes,
                    focusRequester = firstFocus,
                )
                Spacer(Modifier.height(18.dp))
            }

            questions.forEachIndexed { index, task ->
                QuestionField(
                    task = task,
                    value = answers[index],
                    onValueChange = { answers[index] = it },
                    sizes = sizes,
                    fieldModifier = if (!askWhy && index == 0) {
                        Modifier.focusRequester(firstFocus)
                    } else {
                        Modifier
                    },
                )
                Spacer(Modifier.height(18.dp))
            }

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Dark ink on the green: white only reached 3.2:1 against it.
                Text(
                    text = confirmLabel,
                    color = ConfirmInk,
                    fontSize = sizes.button,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(SbColors.Accent)
                        .clickable(onClick = submit)
                        .padding(horizontal = 22.dp, vertical = 13.dp),
                )
                if (onCancel != null) {
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = stringResource(R.string.friction_cancel),
                        color = SbColors.Ink,
                        fontSize = sizes.button,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .border(2.dp, SbColors.Line, RoundedCornerShape(10.dp))
                            .clickable(onClick = onCancel)
                            .padding(horizontal = 18.dp, vertical = 11.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                // The only status this gate shows is a refusal, so it is always red.
                Text(
                    text = status.orEmpty(),
                    color = SbColors.Red,
                    fontSize = sizes.status,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun QuestionField(
    task: FrictionTask,
    value: String,
    onValueChange: (String) -> Unit,
    sizes: GateSizes,
    fieldModifier: Modifier,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = task.label,
            color = SbColors.Ink,
            fontSize = sizes.label,
            fontWeight = FontWeight.Bold,
            lineHeight = sizes.label * 1.4f,
        )
        Spacer(Modifier.height(8.dp))
        val target = task.target
        if (target != null) {
            // Read only and never selectable, so there is nothing to copy out of it.
            Text(
                text = target,
                color = SbColors.Ink,
                fontSize = sizes.target,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                lineHeight = sizes.target * 1.5f,
                modifier = Modifier
                    .background(FieldBg, RoundedCornerShape(10.dp))
                    .border(2.dp, SbColors.Line, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
        val keyboard = if (task.kind == FrictionTaskKind.NUMERIC) {
            KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrectEnabled = false)
        } else {
            KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            )
        }
        GateTextField(
            value = value,
            onValueChange = onValueChange,
            sizes = sizes,
            keyboardOptions = keyboard,
            singleLine = true,
            borderColor = SbColors.Line,
            modifier = fieldModifier,
        )
    }
}

@Composable
private fun WhyField(
    value: String,
    onValueChange: (String) -> Unit,
    words: Int,
    minWords: Int,
    met: Boolean,
    sizes: GateSizes,
    focusRequester: FocusRequester,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.friction_why_label),
            color = SbColors.Ink,
            fontSize = sizes.label,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        GateTextField(
            value = value,
            onValueChange = onValueChange,
            sizes = sizes,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            singleLine = false,
            borderColor = if (met) SbColors.Green else SbColors.Line,
            placeholder = stringResource(R.string.friction_why_placeholder, minWords),
            modifier = Modifier
                .heightIn(min = 84.dp)
                .focusRequester(focusRequester),
        )
        Spacer(Modifier.height(6.dp))
        // Counting words live gives nothing away, so this one does mark as you type.
        Text(
            text = if (met) {
                stringResource(R.string.friction_why_count_met, words)
            } else {
                stringResource(R.string.friction_why_count_short, words, minWords)
            },
            color = if (met) SbColors.Green else SbColors.Muted,
            fontSize = sizes.status,
        )
    }
}

@Composable
private fun GateTextField(
    value: String,
    onValueChange: (String) -> Unit,
    sizes: GateSizes,
    keyboardOptions: KeyboardOptions,
    singleLine: Boolean,
    borderColor: Color,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    // Focus is neutral, never green: green is reserved for the why box's met word count,
    // and an answer field carries no mark before submit.
    val line = if (focused && borderColor == SbColors.Line) FocusLine else borderColor
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = TextStyle(
            color = SbColors.Ink,
            fontSize = sizes.input,
            lineHeight = sizes.input * 1.5f,
        ),
        keyboardOptions = keyboardOptions,
        cursorBrush = SolidColor(SbColors.Ink),
        modifier = modifier
            .fillMaxWidth()
            .background(FieldBg, RoundedCornerShape(10.dp))
            .border(2.dp, line, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box {
                if (value.isEmpty() && placeholder != null) {
                    Text(text = placeholder, color = SbColors.Muted, fontSize = sizes.input)
                }
                inner()
            }
        },
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF14181E, widthDp = 380, heightDp = 760)
@Composable
private fun FrictionGatePreview() {
    MediaBatteryTheme {
        Box(modifier = Modifier.fillMaxWidth().background(SbColors.Bg)) {
            FrictionGate(
                title = "Unblock this app",
                confirmLabel = "Unblock",
                count = 1,
                askWhy = true,
                onCancel = {},
                onSuccess = {},
                large = true,
            )
        }
    }
}
