package com.sesame.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.sesame.app.R
import com.sesame.app.data.profile.RouteCarriers
import com.sesame.app.data.profile.Timer98260
import com.sesame.app.ui.state.OverlayActions
import com.sesame.app.ui.state.SessionState
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The sheet's local draft; 保存 applies it, dismissal discards it. */
private data class RouteMatrixDraft(
    val backend: String,
    val syscall: String?,
    val carrier: String?,
    val dirtyFragOn: Boolean,
)

private const val GhostLockBackend = "ghostlock"

/**
 * The route matrix, opened from the home's single route entry.
 *
 * Two bordered backend cards — a large GhostLock card and a compact timer card.
 * The GhostLock card keeps its three choices as **folded rows**: a row shows
 * the current value and opens a floating list above the sheet, so the layout
 * never moves and only the opened row's options are visible. A choice with a
 * single value has no chevron and does not open. Every change is a draft until
 * 保存 applies it (and closes the sheet); there is no cancel button.
 */
@Composable
internal fun RouteMatrixContent(state: SessionState, actions: OverlayActions) {
    val support = state.support
    val nativeRoutes = support.routeCandidates.filterNot { it.timer }
    val timerCandidate = support.routeCandidates.firstOrNull { it.timer }
    val currentIsTimer = support.selectedRoute != null &&
        support.routeCandidates.firstOrNull { it.name == support.selectedRoute }?.timer == true

    fun carriersOf(syscall: String?): List<String> =
        nativeRoutes.firstOrNull { it.name == syscall }?.carriers?.takeIf { it.isNotEmpty() }
            ?: support.carrierCandidates

    /* The DirtyFrag values the pipeline can express: on when it names
     * dirtyfrag, off when it names ghostlock. */
    val dirtyFragOptions = buildList {
        if ("dirtyfrag" in support.pipelineOrder) add(true)
        if ("ghostlock" in support.pipelineOrder) add(false)
    }.ifEmpty { listOf(false) }

    val initialSyscall = support.route?.takeIf { name -> nativeRoutes.any { it.name == name } }
        ?: nativeRoutes.firstOrNull()?.name
    var draft by remember {
        mutableStateOf(
            RouteMatrixDraft(
                backend = if (currentIsTimer) Timer98260.BLOCK_KEY else GhostLockBackend,
                syscall = initialSyscall,
                carrier = support.selectedCarrier?.takeIf { it in carriersOf(initialSyscall) }
                    ?: carriersOf(initialSyscall).firstOrNull(),
                dirtyFragOn = support.dirtyFragOn,
            ),
        )
    }

    Column(Modifier.fillMaxWidth()) {
        BackendCard(
            selected = draft.backend == GhostLockBackend,
            onClick = { draft = draft.copy(backend = GhostLockBackend) },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.route_matrix_ghostlock),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (draft.backend == GhostLockBackend) {
                    Spacer(Modifier.width(8.dp))
                    CurrentMarker()
                }
            }
            /* Each choice is its own inset field, so the tappable rows read as
             * controls rather than as plain text. */
            Column(
                modifier = Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FoldedRow(
                    label = stringResource(R.string.route_matrix_syscall),
                    options = nativeRoutes.map { it.name to it.name },
                    selected = draft.syscall,
                    onSelect = { name ->
                        val carriers = carriersOf(name)
                        draft = draft.copy(
                            backend = GhostLockBackend,
                            syscall = name,
                            carrier = draft.carrier?.takeIf { it in carriers }
                                ?: carriers.firstOrNull(),
                        )
                    },
                )
                FoldedRow(
                    label = stringResource(R.string.carrier_choice_title),
                    options = carriersOf(draft.syscall).map { it to carrierLabel(it) },
                    selected = draft.carrier,
                    onSelect = { carrier ->
                        draft = draft.copy(backend = GhostLockBackend, carrier = carrier)
                    },
                )
                FoldedRow(
                    label = stringResource(R.string.dirtyfrag_choice_title),
                    options = dirtyFragOptions.map { on ->
                        on to stringResource(
                            if (on) R.string.dirtyfrag_on_short else R.string.dirtyfrag_off_short,
                        )
                    },
                    selected = draft.dirtyFragOn,
                    onSelect = { on ->
                        draft = draft.copy(backend = GhostLockBackend, dirtyFragOn = on)
                    },
                )
            }
        }

        if (timerCandidate != null) {
            Spacer(Modifier.height(12.dp))
            BackendCard(
                selected = draft.backend == Timer98260.BLOCK_KEY,
                onClick = { draft = draft.copy(backend = Timer98260.BLOCK_KEY) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(
                            R.string.route_choice_timer_label,
                            timerCandidate.name,
                        ),
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (draft.backend == Timer98260.BLOCK_KEY) CurrentMarker()
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(
            modifier = Modifier.fillMaxWidth(),
            text = stringResource(R.string.route_matrix_save),
            colors = ButtonDefaults.textButtonColorsPrimary(),
            onClick = {
                actions.onRouteMatrixSave(
                    backend = draft.backend,
                    syscall = draft.syscall,
                    carrier = draft.carrier,
                    dirtyFragOn = draft.dirtyFragOn,
                )
            },
        )
    }
}

@Composable
private fun BackendCard(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.defaultColors()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.dividerLine
                },
                shape = RoundedCornerShape(CardDefaults.CornerRadius),
            )
            .clickable(onClick = onClick),
        colors = colors,
        insideMargin = PaddingValues(16.dp),
        content = content,
    )
}

/**
 * One folded choice row. A row with more than one option is drawn as an inset
 * field (rounded `surfaceContainerHigh` fill) with the current value and the
 * app's arrow, so its tappable bounds are visible; tapping it opens a floating
 * list above the sheet (below the row when it fits, otherwise above), and
 * selecting a value closes it. A single-option row is plain text with no
 * arrow: there is nothing to choose.
 */
@Composable
private fun <T> FoldedRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
) {
    val foldable = options.size > 1
    var open by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val valueLabel = options.firstOrNull { it.first == selected }?.second
        ?: options.firstOrNull()?.second
        ?: "—"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (foldable) MiuixTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            )
            .clickable(enabled = foldable) { open = !open }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = valueLabel,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        if (foldable) {
            /* The same arrow the other rows of the app end with, rotated to
             * point down while the row's list is open. */
            Image(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                modifier = Modifier
                    .size(width = 12.dp, height = 20.dp)
                    .rotate(if (open) 90f else 0f),
                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantActions),
            )
            val gap = with(density) { 4.dp.roundToPx() }
            if (open) {
                Popup(
                    popupPositionProvider = FoldedListPositionProvider(gap),
                    onDismissRequest = { open = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    Card(
                        modifier = Modifier
                            .widthIn(min = 220.dp, max = 300.dp)
                            .border(
                                1.dp,
                                MiuixTheme.colorScheme.outline,
                                RoundedCornerShape(CardDefaults.CornerRadius),
                            ),
                        colors = CardDefaults.defaultColors(),
                        insideMargin = PaddingValues(vertical = 8.dp),
                    ) {
                        options.forEach { (value, optionLabel) ->
                            val isSelected = value == selected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelect(value)
                                        open = false
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.width(20.dp)) {
                                    if (isSelected) {
                                        Image(
                                            imageVector = MiuixIcons.Basic.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            colorFilter = ColorFilter.tint(
                                                MiuixTheme.colorScheme.primary,
                                            ),
                                        )
                                    }
                                }
                                Text(
                                    text = optionLabel,
                                    fontSize = 15.sp,
                                    color = if (isSelected) {
                                        MiuixTheme.colorScheme.primary
                                    } else {
                                        MiuixTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Places the opened list under its row; above it when the window is too short. */
private class FoldedListPositionProvider(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height) {
            below
        } else {
            (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
        }
        val x = anchorBounds.left.coerceIn(
            0,
            (windowSize.width - popupContentSize.width).coerceAtLeast(0),
        )
        return IntOffset(x, y)
    }
}

@Composable
private fun CurrentMarker() {
    Text(
        text = stringResource(R.string.route_matrix_current),
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.primary,
    )
}

@Composable
private fun carrierLabel(carrier: String): String = stringResource(
    if (carrier == RouteCarriers.Compat) {
        R.string.carrier_choice_compat_label
    } else {
        R.string.carrier_choice_native_label
    },
)
