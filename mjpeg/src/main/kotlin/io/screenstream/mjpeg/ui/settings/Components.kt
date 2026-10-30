package io.screenstream.mjpeg.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Label
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.window.core.layout.WindowSizeClass
import info.dvkr.screenstream.common.ui.ExpandableCard
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.ui.LocalMjpegSnackbarHostState
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.NumberFormat
import java.text.ParsePosition
import java.util.Locale

internal class SettingOpenerFocus {
    val requester: FocusRequester = FocusRequester()
    private var restorationPending by mutableStateOf(false)

    fun markDismissed() {
        restorationPending = true
    }

    fun restoreIfPending() {
        if (restorationPending) {
            requester.requestFocus()
            restorationPending = false
        }
    }
}

@Composable
internal fun rememberSettingOpenerFocus(editorVisible: Boolean): SettingOpenerFocus {
    val openerFocus = remember { SettingOpenerFocus() }
    LaunchedEffect(editorVisible) {
        if (!editorVisible) openerFocus.restoreIfPending()
    }
    return openerFocus
}

@Composable
internal fun SettingsGroupCard(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    searching: Boolean,
    group: SettingSearchGroup,
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val headerContent: @Composable BoxScope.() -> Unit = {
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = if (searching) 16.dp else 48.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(iconRes), contentDescription = null)
            Text(
                stringResource(group.titleRes),
                Modifier
                    .padding(start = 8.dp)
                    .semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
    if (searching) {
        ElevatedCard(modifier = modifier) {
            Box(Modifier.fillMaxWidth(), content = headerContent)
            HorizontalDivider()
            content()
        }
    } else {
        ExpandableCard(
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            headerContent = headerContent,
            modifier = modifier,
            content = content,
        )
    }
}

@Composable
internal fun SettingsItem(
    title: String,
    summary: String,
    @DrawableRes iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    valueContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    ListItem(
        onClick = onClick,
        modifier = modifier,
        shapes = ListItemShapes(
            shape = RectangleShape,
            selectedShape = RectangleShape,
            pressedShape = RectangleShape,
            focusedShape = RectangleShape,
            hoveredShape = RectangleShape,
            draggedShape = RectangleShape,
        ),
        verticalAlignment = Alignment.CenterVertically,
        leadingContent = { Icon(painterResource(iconRes), contentDescription = null) },
        supportingContent = {
            if (valueContent == null) {
                Text(summary, style = MaterialTheme.typography.bodySmall)
            } else {
                Column {
                    Text(summary, style = MaterialTheme.typography.bodySmall)
                    valueContent()
                }
            }
        },
        trailingContent = trailingContent,
        content = { Text(title, style = MaterialTheme.typography.titleMedium) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingEditor(
    title: String,
    onDismiss: () -> Unit,
    description: String = "",
    content: @Composable () -> Unit,
) {
    val windowWidthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val dialogWidth = if (windowWidthDp >= WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND.dp) 440.dp else null
    if (dialogWidth == null) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            dragHandle = null,
            sheetMaxWidth = 480.dp,
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
        ) {
            SettingEditorContent(title, description, onDismiss, Modifier.weight(1f, fill = false), content)
        }
    } else {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, windowTitle = title),
        ) {
            Surface(
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp)
                    .widthIn(max = dialogWidth)
                    .fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                SettingEditorContent(title, description, onDismiss, content = content)
            }
        }
    }
}

@Composable
private fun SettingEditorContent(
    title: String,
    description: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title, Modifier
                    .weight(1f)
                    .semantics { heading() }, style = MaterialTheme.typography.titleLarge
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.mjpeg_icon_close), stringResource(R.string.mjpeg_action_close))
            }
        }
        Column(
            Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (description.isNotEmpty()) {
                Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            content()
        }
        SnackbarHost(LocalMjpegSnackbarHostState.current)
    }
}

@Composable
internal fun <T> SettingDropdownMenu(
    expanded: Boolean,
    selectedValue: T,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onValueSelected: (T) -> Unit,
    onDismissRequest: () -> Unit,
    enabled: Boolean = true,
    optionSupportingText: (@Composable (T) -> Unit)? = null,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.selectableGroup(),
        containerColor = Color.Transparent,
        shadowElevation = 0.dp,
    ) {
        val menuColors = MenuDefaults.selectableItemColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        options.forEachIndexed { index, option ->
            SelectableDropdownMenuItem(
                selected = selectedValue == option,
                onClick = {
                    onValueSelected(option)
                    onDismissRequest()
                },
                text = { Text(optionLabel(option), fontWeight = FontWeight.Normal) },
                supportingText = if (optionSupportingText != null) {
                    { optionSupportingText(option) }
                } else null,
                modifier = Modifier.padding(vertical = if (optionSupportingText == null) 2.dp else 0.dp),
                shapes = MenuDefaults.itemShape(index, options.size),
                enabled = enabled,
                colors = menuColors,
            )
        }
    }
}

@Composable
internal fun SettingNumberField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    errorText: String?,
    inputLocale: Locale,
    modifier: Modifier = Modifier,
    maximumValue: Int? = null,
    allowDecimal: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    showSupportingError: Boolean = true,
    enabled: Boolean = true,
    labelAbove: Boolean = false,
) {
    val numberFormat = rememberSettingNumberFormat(inputLocale)
    val focusManager = LocalFocusManager.current
    val field: @Composable (Modifier) -> Unit = { fieldModifier ->
        OutlinedTextField(
            value = value,
            onValueChange = { next ->
                if (!enabled) return@OutlinedTextField
                if (
                    next.text == value.text ||
                    acceptsSettingNumberInput(next.text, numberFormat, allowDecimal = allowDecimal, maximumValue = maximumValue)
                ) {
                    onValueChange(next)
                }
            },
            modifier = fieldModifier.semantics {
                if (errorText != null) error(errorText)
                if (labelAbove) contentDescription = label
            },
            enabled = enabled,
            label = if (labelAbove) null else ({ Text(label) }),
            isError = errorText != null,
            supportingText = if (showSupportingError && errorText != null) {
                { Text(errorText) }
            } else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = if (allowDecimal) KeyboardType.Decimal else KeyboardType.Number, imeAction = imeAction),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }, onNext = { focusManager.moveFocus(FocusDirection.Next) }),
        )
    }
    if (labelAbove) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                label,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .clearAndSetSemantics {},
                color = if (errorText != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            field(Modifier.fillMaxWidth())
        }
    } else {
        field(modifier)
    }
}

@Composable
internal fun SettingSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    compact: Boolean = false,
) {
    val switchModifier = if (compact) {
        val switchTransformOrigin = TransformOrigin(if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else 0f, 0.5f)
        modifier.graphicsLayer {
            scaleX = 0.75f
            scaleY = 0.75f
            transformOrigin = switchTransformOrigin
        }
    } else modifier
    Switch(
        checked = checked,
        enabled = enabled,
        onCheckedChange = null,
        modifier = switchModifier,
        interactionSource = interactionSource,
        thumbContent = if (checked) {
            {
                Icon(
                    painterResource(R.drawable.mjpeg_icon_check),
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize),
                )
            }
        } else null,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingSliderThumb(
    valueText: String,
    interactionSource: MutableInteractionSource,
    isPersistent: Boolean,
    color: Color,
) {
    Label(
        interactionSource = interactionSource,
        isPersistent = isPersistent,
        label = {
            PlainTooltip(
                shape = CircleShape,
                caretShape = TooltipDefaults.caretShape(),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Box(
                    Modifier.defaultMinSize(minWidth = 24.dp, minHeight = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(valueText)
                }
            }
        },
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .hoverable(interactionSource)
                .indication(interactionSource, ripple(bounded = false, radius = 20.dp, color = color))
                .background(color, CircleShape),
        )
    }
}

@Composable
internal fun SettingInfo(
    text: String,
    @DrawableRes iconRes: Int = R.drawable.mjpeg_icon_info,
    centered: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (centered) Alignment.CenterHorizontally else Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text,
            modifier = Modifier.weight(1f, fill = !centered),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
    }
}

@Composable
internal fun rememberSettingNumberFormat(locale: Locale = LocalConfiguration.current.locales[0]): NumberFormat = remember(locale) {
    NumberFormat.getNumberInstance(locale).apply {
        isGroupingUsed = false
        maximumFractionDigits = 340
        if (this is DecimalFormat) isParseBigDecimal = true
    }
}

private fun parseSettingNumber(text: String, numberFormat: NumberFormat): Number? {
    if (text.isEmpty()) return null
    val position = ParsePosition(0)
    val parsed = numberFormat.parse(text, position) ?: return null
    return parsed.takeIf { position.index == text.length && position.errorIndex < 0 }
}

internal fun parseSettingInteger(text: String, numberFormat: NumberFormat): Int? = try {
    when (val parsed = parseSettingNumber(text, numberFormat)) {
        null -> null
        is BigDecimal -> parsed.intValueExact()
        else -> parsed.toString().toBigDecimalOrNull()?.intValueExact()
    }
} catch (_: ArithmeticException) {
    null
}

internal fun parseSettingDecimal(text: String, numberFormat: NumberFormat): Double? =
    parseSettingNumber(text, numberFormat)?.toDouble()?.takeIf(Double::isFinite)

/** Allows incomplete numeric drafts (empty text or a lone decimal separator) during editing. */
internal fun acceptsSettingNumberInput(
    text: String,
    numberFormat: NumberFormat,
    allowDecimal: Boolean = false,
    maximumValue: Int? = null,
): Boolean {
    val separator = if (allowDecimal && numberFormat is DecimalFormat) {
        numberFormat.decimalFormatSymbols.decimalSeparator
    } else '.'
    if (!text.all { it.isDigit() || (allowDecimal && it == separator) }) {
        return false
    }
    if (allowDecimal && text.count { it == separator } > 1) {
        return false
    }
    if (text.isEmpty() || (allowDecimal && text == separator.toString())) {
        return true
    }
    if (!allowDecimal) {
        val integerValue = parseSettingInteger(text, numberFormat) ?: return false
        return integerValue in 0..(maximumValue ?: Int.MAX_VALUE)
    }

    val parsed = parseSettingNumber(text, numberFormat) ?: return false
    val numericValue = parsed.toDouble()
    if (!numericValue.isFinite() || numericValue < 0) {
        return false
    }
    if (maximumValue == null) {
        return true
    }

    val exactValue = parsed as? BigDecimal ?: parsed.toString().toBigDecimalOrNull() ?: return false
    return exactValue <= maximumValue.toBigDecimal()
}
