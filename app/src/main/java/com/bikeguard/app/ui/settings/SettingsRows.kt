package com.bikeguard.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bikeguard.app.R
import com.bikeguard.app.ui.theme.Palette

/** 48dp circle with a 24dp icon — the row pattern from the Dashboard/Alerts rounds. */
@Composable
fun AccentCircle(icon: ImageVector, color: Color = Palette.Leaf) {
    Box(Modifier.size(48.dp).background(color.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
    }
}

/** Title row for a settings card: icon circle + bold title. */
@Composable
fun PanelHeader(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AccentCircle(icon)
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/** Thin divider between rows inside one card (master design reference). */
@Composable
fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Palette.Stroke)
}

/** Shared segmented-button colors for the Settings screens. */
@Composable
fun rememberSegColors() = SegmentedButtonDefaults.colors(
    // Active content is Leaf (saturated green): the primary is mint in light
    // mode, and mint text on pale mint is unreadable. Dark keeps amber Leaf.
    activeContainerColor = Palette.Ember.copy(alpha = 0.18f), activeContentColor = Palette.Leaf,
    activeBorderColor = Palette.Leaf, inactiveContainerColor = Color.Transparent,
    inactiveContentColor = Palette.Muted, inactiveBorderColor = Palette.Stroke,
)

@Composable
fun RingtoneRow(title: String, current: String, onPick: () -> Unit, icon: ImageVector? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            AccentCircle(icon)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(current, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.width(12.dp))
        LeafOutlinedButton(onClick = onPick) { Text(stringResource(R.string.ringtone_change)) }
    }
}

@Composable
fun ReadOnlyRow(label: String, value: String, icon: ImageVector? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            AccentCircle(icon)
            Spacer(Modifier.width(12.dp))
        }
        Text(label, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, icon: ImageVector? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            AccentCircle(icon)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Ember, checkedThumbColor = Palette.Ink),
        )
    }
}

@Composable
fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(
                selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) },
                // 48dp touch target (v1.2.20): the Material3 default chip is 32dp.
                modifier = Modifier.height(48.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Palette.Ember.copy(alpha = 0.2f), selectedLabelColor = Palette.Leaf,
                ),
            )
        }
    }
}

/** Outlined button in the design system: 48dp, 24dp radius, Leaf border + text. */
@Composable
fun LeafOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = modifier.height(48.dp), enabled = enabled,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Palette.Leaf),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Leaf),
        content = content,
    )
}

/**
 * Text button with Leaf text. The default primary is mint in light mode, which
 * is unreadable on white, so every settings TextButton goes through here.
 */
@Composable
fun LeafTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    TextButton(
        onClick = onClick, modifier = modifier, enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = Palette.Leaf),
        content = content,
    )
}
