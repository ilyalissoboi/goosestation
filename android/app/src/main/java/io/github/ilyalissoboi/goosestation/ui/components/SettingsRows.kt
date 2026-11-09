// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes

/** One of a [CyclerRow]'s choices: [value] as settings.ini has it, [label] as the row shows it. */
data class Choice<T>(
    val value: T,
    val label: String,
    val note: String? = null,
)

/**
 * A settings page: [title], then [content] in a scrolling column. B or Back go back ([onBack]). It has one
 * [ChoiceListHost] for its rows: a tap outside an open list closes it, and still does what it would have.
 */
@Composable
fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Before any handler a page adds itself, which takes Back first while it's enabled.
    BackHandler(onBack = onBack)
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val lists = remember { ChoiceListHost() }
    var pageCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    CompositionLocalProvider(LocalChoiceListHost provides lists) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .safeDrawingPadding()
                    .onGloballyPositioned { pageCoordinates = it }
                    // Before the rows see it, and without taking it: a tap closes an open list it ends outside of. A
                    // drag (a scroll by touch) doesn't.
                    .pointerInput(lists) {
                        awaitPointerEventScope {
                            var down: Offset? = null
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull() ?: continue
                                if (event.type == PointerEventType.Press) down = change.position
                                if (event.type != PointerEventType.Release) continue
                                val start = down ?: continue
                                down = null
                                if ((change.position - start).getDistance() > viewConfiguration.touchSlop) continue
                                val page = pageCoordinates?.takeIf { it.isAttached } ?: continue
                                lists.tappedAt(page.localToRoot(change.position))
                            }
                        }
                    }
                    // B, and a Back key reaching Compose, go to the back handlers, the innermost enabled one first.
                    .onPreviewKeyEvent { event ->
                        if (event.key != Key.Back && event.key != Key.ButtonB) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyUp) dispatcher?.onBackPressed() ?: onBack()
                        true
                    }.verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // A game's own pages carry its title over theirs.
            LocalGameContext.current?.let { Eyebrow(it.title) }
            Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}

/**
 * A [SettingsPage] whose rows are composed as they scroll into view, for a list that can run to thousands (the shader
 * browser): [title], [subtitle] if any, then [content]'s items. B or Back go back ([onBack]). Rows keep their focus
 * ring's margin inside the list. It has no choice lists.
 */
@Composable
fun LazySettingsPage(
    title: String,
    onBack: () -> Unit,
    state: LazyListState = rememberLazyListState(),
    subtitle: String? = null,
    content: LazyListScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
                // B, and a Back key reaching Compose, go to the back handlers, the innermost enabled one first.
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.Back && event.key != Key.ButtonB) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyUp) dispatcher?.onBackPressed() ?: onBack()
                    true
                }.padding(horizontal = 28.dp, vertical = 16.dp),
    ) {
        val game = LocalGameContext.current
        // The title scrolls with the rows, as on every settings page, so a short screen keeps its height for them.
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = LAZY_PAGE_HEADER) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    game?.let { Eyebrow(it.title) }
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = GooseStationTheme.colors.inkMuted)
                    }
                }
            }
            content()
        }
    }
}

/** The key of a [LazySettingsPage]'s first item, its title: the page's own items come after it. */
const val LAZY_PAGE_HEADER = "page-header"

/**
 * A [SettingsPage] for the moment its settings take to read: [title] and a line saying so. The line holds the focus,
 * with nothing to activate, so B and Back reach the page as on any other ([onBack]).
 */
@Composable
fun SettingsLoadingPage(
    title: String,
    onBack: () -> Unit,
) {
    val holder = remember { FocusRequester() }
    LaunchedEffect(Unit) { holder.requestFocus() }
    SettingsPage(title, onBack = onBack) {
        Text(
            "Reading the settings…",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).focusRequester(holder).focusable(),
        )
    }
}

/**
 * A setting with a few [choices]: the [label], and the [selected] choice's label between two arrows. Left and right
 * step through the choices, stopping at the ends; a tap on an arrow steps its way. Each step calls [onSelect]. Under
 * the row: [note], then the selected choice's own note. A value that isn't among the choices shows as itself, and
 * either step goes to the first choice.
 *
 * A, Enter or a tap on the row open its [ChoiceList] under the label line, one per page ([LocalChoiceListHost]).
 * Nothing changes until A on a choice, which selects it (if it isn't already) and closes the list. B, Back or a tap
 * on the row close it without a change; the focus goes back to the row. With no choices left, it closes. Disabled
 * ([enabled] false), it's dimmed and can't take the focus; its note still shows.
 */
@Composable
fun <T> CyclerRow(
    label: String,
    choices: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    enabled: Boolean = true,
    mark: String? = null,
) {
    val index = choices.indexOfFirst { it.value == selected }
    val current = choices.getOrNull(index)
    val select by rememberUpdatedState(onSelect)
    SteppedRow(
        label = label,
        value = current?.label ?: selected?.toString() ?: "",
        canStep = { by ->
            choices.isNotEmpty() && if (by < 0) index != 0 else index != choices.lastIndex
        },
        onStep = { by ->
            val next = if (index < 0) 0 else (index + by).coerceIn(0, choices.lastIndex)
            if (choices.isNotEmpty() && next != index) select(choices[next].value)
        },
        choices = choices,
        selected = selected,
        onPick = { if (it != selected) select(it) },
        modifier = modifier,
        note = note,
        currentNote = current?.note,
        enabled = enabled,
        mark = mark,
    )
}

/**
 * A number setting, or one with a long ladder of values: the [label], and [value] as [scale] labels it, between two
 * arrows. Left and right step by the scale ([NumberScale.next]), stopping at its ends, on each key-down, so a key held
 * repeats; a tap on an arrow steps its way. Each step calls [onChange]. A, Enter or a tap on the row open the list of
 * the scale's common values ([NumberScale.presets]), with [value] among them when it isn't one, as a [CyclerRow]'s
 * list. A value off the scale shows as itself. [note] goes under the row, after its [mark]. Disabled ([enabled]
 * false), it's dimmed and can't take the focus.
 *
 * On a game's page, with the [global] value and [onUseGlobal]: its list starts with "Use global (<global>)", which calls
 * it, and Left from the scale's lowest value does too. While the row follows the global value ([followsGlobal]), it
 * shows as that: Right makes the global value the game's own ([onChange] with it), and Left does nothing.
 */
@Composable
fun NumberRow(
    label: String,
    value: Int,
    scale: NumberScale,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    enabled: Boolean = true,
    mark: String? = null,
    global: Int? = null,
    followsGlobal: Boolean = false,
    onUseGlobal: (() -> Unit)? = null,
) {
    val change by rememberUpdatedState(onChange)
    val useGlobal by rememberUpdatedState(onUseGlobal)
    // The value last asked for, so a held key's repeats that come before the page shows it step on from it; null while
    // the row follows the global value.
    val latest = remember { arrayOf<Int?>(value) }
    val shownValue: Int? = value.takeUnless { followsGlobal }
    SideEffect { latest[0] = shownValue }
    val gameRow = global != null && onUseGlobal != null
    val globalLabel = useGlobalLabel(scale.label(global ?: value))

    // The step from current (null: the global value) going by: a value, USE_GLOBAL, or null at an end.
    fun next(
        current: Int?,
        by: Int,
    ): Int? =
        when {
            current == null -> if (by > 0) global ?: value else null
            else -> scale.next(current, by) ?: if (by < 0 && gameRow) USE_GLOBAL else null
        }

    val choices: List<Choice<Int?>> =
        (if (gameRow) listOf(Choice<Int?>(null, globalLabel)) else emptyList()) +
            (scale.presets + listOfNotNull(shownValue)).distinct().sorted().map { Choice<Int?>(it, scale.label(it)) }
    SteppedRow(
        label = label,
        value = if (followsGlobal) globalLabel else scale.label(value),
        canStep = { by -> next(latest[0], by) != null },
        // The arrows as the value shown has them: a row that hasn't recomposed since a change still draws them right.
        canStepShown = { by -> next(shownValue, by) != null },
        onStep = { by ->
            when (val to = next(latest[0], by)) {
                null -> {}
                USE_GLOBAL -> {
                    latest[0] = null
                    useGlobal?.invoke()
                }
                else -> {
                    latest[0] = to
                    change(to)
                }
            }
        },
        choices = choices,
        selected = shownValue,
        onPick = {
            when {
                it == null -> if (!followsGlobal) useGlobal?.invoke()
                it != shownValue -> change(it)
            }
        },
        modifier = modifier,
        note = note,
        currentNote = null,
        enabled = enabled,
        mark = mark,
    )
}

/** What a row on a game's page shows while it follows the global setting, whose value is [label]. */
fun useGlobalLabel(label: String) = "Use global ($label)"

// NumberRow's step back to the global value: no scale has it.
private const val USE_GLOBAL = Int.MIN_VALUE

/**
 * A page's section heading: [title] in the eyebrow style, as desktop names the section. Never a focus stop; read as
 * a heading.
 */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
) {
    Eyebrow(
        title,
        modifier =
            modifier
                .padding(start = 16.dp, top = 16.dp, end = 16.dp)
                .semantics { heading() },
    )
}

/**
 * [CyclerRow]'s and [NumberRow]'s row: the [label], the [value] between arrows that step by -1 or 1 ([onStep]) where
 * [canStep] says, drawn lit where [canStepShown] says, and the list of [choices], which [onPick] picks from.
 * [currentNote] is the current choice's note, shown under [note] while the list is closed.
 */
@Composable
private fun <T> SteppedRow(
    label: String,
    value: String,
    canStep: (Int) -> Boolean,
    canStepShown: (Int) -> Boolean = canStep,
    onStep: (Int) -> Unit,
    choices: List<Choice<T>>,
    selected: T,
    onPick: (T) -> Unit,
    modifier: Modifier,
    note: String?,
    currentNote: String?,
    enabled: Boolean,
    mark: String? = null,
) {
    val colors = GooseStationTheme.colors
    var focused by remember { mutableStateOf(false) }
    val row = remember { FocusRequester() }
    // The page's, or the row's own outside a page.
    val host = LocalChoiceListHost.current ?: remember { ChoiceListHost() }
    val key = remember { Any() }
    val open = host.open === key

    fun close() {
        host.close()
        row.requestFocus()
    }

    BackHandler(enabled = open, onBack = ::close)
    LaunchedEffect(open, choices.isEmpty()) { if (open && choices.isEmpty()) close() }

    fun step(by: Int) {
        if (!enabled || open || !canStep(by)) return
        onStep(by)
    }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .focusRequester(row)
                .onGloballyPositioned { if (open) host.openCoordinates = it }
                // Before the focus ring, so the keys bubble up to it from the focused row. Both halves of the press
                // are taken, so neither moves the focus sideways. Each key-down steps, a held key's repeats too.
                .onKeyEvent { event ->
                    val by =
                        when (event.key) {
                            Key.DirectionLeft -> -1
                            Key.DirectionRight -> 1
                            else -> return@onKeyEvent false
                        }
                    if (event.type == KeyEventType.KeyDown) step(by)
                    true
                }.focusRing(enabled = enabled, onFocusChange = { focused = it }, onClick = {
                    when {
                        open -> close()
                        choices.isNotEmpty() -> host.open(key, row)
                    }
                })
                .semantics {
                    stateDescription = if (open) "Expanded" else "Collapsed"
                    customActions =
                        listOf(
                            CustomAccessibilityAction("Previous") {
                                step(-1)
                                true
                            },
                            CustomAccessibilityAction("Next") {
                                step(1)
                                true
                            },
                        )
                }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LabelValue(
            label = {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) colors.ink else colors.inkFaint,
                )
            },
            value = {
                Cycler(
                    value = value,
                    active = focused,
                    enabled = enabled,
                    // Faint while the list is open: it doesn't step then.
                    canGoBack = enabled && !open && canStepShown(-1),
                    canGoForward = enabled && !open && canStepShown(1),
                    onBack = { step(-1) },
                    onForward = { step(1) },
                )
            },
        )
        mark?.let { RowMark(it) }
        note?.let { RowNote(it) }
        if (open) {
            ChoiceList(
                choices,
                selected,
                onPick = {
                    onPick(it.value)
                    close()
                },
            )
        } else {
            currentNote?.let { RowNote(it) }
        }
    }
}

/**
 * The value of a [CyclerRow] or [NumberRow] in a well, between its arrows: in the beak's ink while the row is
 * [active], faint at an end they can't step past, and the value faint while the row isn't [enabled]. The arrows take
 * taps only; the row is what the focus lands on.
 */
@Composable
private fun Cycler(
    value: String,
    active: Boolean,
    enabled: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    val colors = GooseStationTheme.colors
    Row(
        modifier =
            Modifier
                .heightIn(min = 40.dp)
                .background(colors.sunken, GsShapes.medium)
                .border(1.dp, colors.lineStrong, GsShapes.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CyclerArrow(GsIcons.back, active, canGoBack, onBack)
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = if (enabled) colors.ink else colors.inkFaint,
            textAlign = TextAlign.Center,
            // After the arrows, so a long value wraps between them rather than pushing one out.
            modifier = Modifier.weight(1f, fill = false).widthIn(min = 96.dp).padding(vertical = 8.dp),
        )
        CyclerArrow(GsIcons.forward, active, canGoForward, onForward)
    }
}

@Composable
private fun CyclerArrow(
    icon: ImageVector,
    active: Boolean,
    enabled: Boolean,
    onTap: () -> Unit,
) {
    val colors = GooseStationTheme.colors
    val tap by rememberUpdatedState(onTap)
    val canTap by rememberUpdatedState(enabled)
    Box(
        modifier =
            Modifier
                .size(40.dp)
                // Taken even at an end, where it does nothing: let through, it would reach the row and step forward.
                .pointerInput(Unit) { detectTapGestures { if (canTap) tap() } },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint =
                when {
                    !enabled -> colors.inkFaint
                    active -> colors.beakInk
                    else -> colors.inkMuted
                },
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * An on/off setting: the [label], and a square toggle showing [checked]. A, Enter or a tap call [onToggle]. The label
 * and its [note] wrap, each up to [maxLines] lines, then end in an ellipsis. Disabled ([enabled] false), it's dimmed
 * and can't take the focus; its note, which says why, still shows.
 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    maxLines: Int = Int.MAX_VALUE,
    enabled: Boolean = true,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .focusRing(enabled = enabled, role = Role.Switch, onClick = { onToggle(!checked) })
                .semantics { toggleableState = ToggleableState(checked) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LabelValue(
            label = {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) GooseStationTheme.colors.ink else GooseStationTheme.colors.inkFaint,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            value = { Toggle(checked, enabled) },
        )
        note?.let { RowNote(it, maxLines) }
    }
}

/**
 * An entry that opens a page: the [label], and a [summary] of what's in it under it. A disabled one is dimmed, and
 * can't be focused or opened.
 */
@Composable
fun NavRow(
    label: String,
    summary: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = GooseStationTheme.colors
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .focusRing(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) colors.ink else colors.inkFaint,
            )
            summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted) }
        }
        if (enabled) {
            Icon(GsIcons.forward, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * A text setting: the [label] over a well holding [state]'s text, typed with Android's keyboard. Focus doesn't open
 * the keyboard, so a D-pad passes through; A, the D-pad centre or a tap do. Up and down leave it, as on any row. A
 * [password] is a secure field: masked, never offered to keyboards to learn, and read by TalkBack as a password. The
 * keyboard's action is [imeAction]; Done calls [onDone]. Its content description is [label], as TalkBack reads it.
 * With [keyboardOnFocus], for a field opened on purpose, focus opens the keyboard too.
 */
@Composable
fun TextFieldRow(
    label: String,
    state: TextFieldState,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
    keyboardOnFocus: Boolean = false,
) {
    val colors = GooseStationTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    // A or the D-pad centre asked for the keyboard, until the focus leaves. Compose's field starts its text session,
    // which the keyboard needs, only for a tap or while it's to show the keyboard on focus: so A turns that on.
    var keyboardWanted by remember { mutableStateOf(false) }
    // The keyboard shrinks the page from below once the field has focus: the field is brought back into view.
    val inView = remember { BringIntoViewRequester() }
    val keyboardHeight = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(focused, keyboardHeight) { if (focused && keyboardHeight > 0) inView.bringIntoView() }
    val field =
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(inView)
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) keyboardWanted = false
            }.onPreviewKeyEvent { event ->
                // A single-line field would move its cursor instead.
                val direction =
                    when (event.key) {
                        Key.DirectionUp -> FocusDirection.Up
                        Key.DirectionDown -> FocusDirection.Down
                        else -> null
                    }
                if (direction != null) {
                    if (event.type == KeyEventType.KeyDown) focus.moveFocus(direction)
                    return@onPreviewKeyEvent true
                }
                val opens = event.key == Key.ButtonA || event.key == Key.DirectionCenter
                if (opens && event.type == KeyEventType.KeyUp) {
                    keyboardWanted = true
                    keyboard?.show()
                }
                opens
            }.semantics { contentDescription = label }
            .focusIndicator(focused, colors.focus, GsShapes.small)
            .heightIn(min = 48.dp)
            .background(colors.sunken, GsShapes.small)
            .border(1.dp, colors.lineStrong, GsShapes.small)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.ink)
    val cursor = SolidColor(colors.beak)
    val action =
        KeyboardActionHandler { performDefault ->
            if (imeAction == ImeAction.Done && onDone != null) onDone() else performDefault()
        }
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = if (enabled) colors.ink else colors.inkFaint)
        DockedKeyboard {
            Field(
                password,
                state,
                field,
                enabled,
                textStyle,
                imeAction,
                action,
                cursor,
                keyboardOnFocus || keyboardWanted,
            )
        }
    }
}

/**
 * Keeps Android's keyboard docked under the app for the text fields in [content]: no full-screen editor. Gboard on a
 * landscape handheld (the AYN Thor) ignores IME_FLAG_NO_FULLSCREEN, which Compose sets, and opens one anyway, and
 * Compose's field never updates its box, so typing showed nothing. With IME_FLAG_NO_EXTRACT_UI too, it stays docked.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DockedKeyboard(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, next ->
            next.startInputMethod(
                object : PlatformTextInputMethodRequest by request {
                    override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
                        request.createInputConnection(outAttributes).also {
                            outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                        }
                },
            )
        },
        content = content,
    )
}

/** [TextFieldRow]'s well: a secure field for a [password], else a single-line one. */
@Composable
private fun Field(
    password: Boolean,
    state: TextFieldState,
    field: Modifier,
    enabled: Boolean,
    textStyle: TextStyle,
    imeAction: ImeAction,
    action: KeyboardActionHandler,
    cursor: Brush,
    keyboardOnFocus: Boolean,
) {
    if (password) {
        BasicSecureTextField(
            state = state,
            modifier = field,
            enabled = enabled,
            textStyle = textStyle,
            keyboardOptions = KeyboardOptions(imeAction = imeAction, showKeyboardOnFocus = keyboardOnFocus),
            onKeyboardAction = action,
            cursorBrush = cursor,
        )
    } else {
        BasicTextField(
            state = state,
            modifier = field,
            enabled = enabled,
            textStyle = textStyle,
            keyboardOptions =
                KeyboardOptions(
                    imeAction = imeAction,
                    autoCorrectEnabled = false,
                    showKeyboardOnFocus = keyboardOnFocus,
                ),
            onKeyboardAction = action,
            lineLimits = TextFieldLineLimits.SingleLine,
            cursorBrush = cursor,
        )
    }
}

/**
 * A square switch: a well with a muted thumb at the start when off, the beak with a dark thumb at the end when on.
 * Disabled, a well either way, with a faint thumb.
 */
@Composable
private fun Toggle(
    checked: Boolean,
    enabled: Boolean,
) {
    val colors = GooseStationTheme.colors
    val lit = checked && enabled
    Box(
        modifier =
            Modifier
                .size(width = 44.dp, height = 24.dp)
                .background(if (lit) colors.beak else colors.sunken, GsShapes.small)
                .border(1.dp, if (lit) colors.beak else colors.lineStrong, GsShapes.small)
                .padding(4.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .background(
                    when {
                        !enabled -> colors.inkFaint
                        checked -> colors.onBeak
                        else -> colors.inkMuted
                    },
                    RoundedCornerShape(2.dp),
                ),
        )
    }
}

/**
 * A [label] with its [value] at the end of the line, when both fit on it, or the value is short enough for the label
 * to wrap beside it; otherwise, as on a narrow screen or with large text, the value goes under the label. Either way
 * each gets the width to wrap at its spaces, rather than one squeezing the other. It takes the width it's given. It
 * isn't hardened for a parent that asks for intrinsic sizes, nor for an unbounded width: no row of the app does.
 */
@Composable
internal fun LabelValue(
    label: @Composable () -> Unit,
    value: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = {
            label()
            value()
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val (labelMeasurable, valueMeasurable) = measurables
        val gap = LABEL_VALUE_GAP.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val labelWidth = labelMeasurable.maxIntrinsicWidth(Constraints.Infinity)
        val valueWidth = valueMeasurable.maxIntrinsicWidth(Constraints.Infinity)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else labelWidth + gap + valueWidth
        if (labelWidth + gap + valueWidth <= width || valueWidth <= (width - gap) / SHORT_VALUE_SHARE) {
            val valuePlaceable = valueMeasurable.measure(loose)
            val labelMax = (width - gap - valuePlaceable.width).coerceAtLeast(0)
            val labelPlaceable = labelMeasurable.measure(loose.copy(maxWidth = labelMax))
            val height = maxOf(labelPlaceable.height, valuePlaceable.height).coerceAtLeast(constraints.minHeight)
            layout(width, height) {
                labelPlaceable.place(0, (height - labelPlaceable.height) / 2)
                valuePlaceable.place(width - valuePlaceable.width, (height - valuePlaceable.height) / 2)
            }
        } else {
            val labelPlaceable = labelMeasurable.measure(loose)
            val valuePlaceable = valueMeasurable.measure(loose)
            val between = STACKED_GAP.roundToPx()
            val height = (labelPlaceable.height + between + valuePlaceable.height).coerceAtLeast(constraints.minHeight)
            layout(width, height) {
                labelPlaceable.place(0, 0)
                valuePlaceable.place(0, labelPlaceable.height + between)
            }
        }
    }
}

private val LABEL_VALUE_GAP = 16.dp
private val STACKED_GAP = 4.dp

/** A value up to this share of the line (one in three) stays beside its label, which wraps instead. */
private const val SHORT_VALUE_SHARE = 3

/** A row's mark on a game's page, such as "Set for this game": in the beak's ink, so it reads apart from the note. */
@Composable
private fun RowMark(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = GooseStationTheme.colors.beakInk)
}

/** A line under a row's label and value, in the row, so it's read with them. */
@Composable
private fun RowNote(
    text: String,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = GooseStationTheme.colors.inkMuted,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
