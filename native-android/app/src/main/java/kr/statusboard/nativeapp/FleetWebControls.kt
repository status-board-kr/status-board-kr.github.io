@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package kr.statusboard.nativeapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared web dimensions keep every native business form on the same visual scale. */
@Composable internal fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(10.dp), colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp), content: @Composable RowScope.() -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        androidx.compose.material3.Button(onClick, modifier, enabled, shape, colors, contentPadding = contentPadding) {
            ProvideTextStyle(TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold), content = { content() })
        }
    }
}

@Composable internal fun Switch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val green = Color(0xFF34D399)
    Box(modifier.padding(start = 6.dp).size(34.dp, 20.dp).alpha(if (enabled) 1f else .35f)
        .background(if (checked) green.copy(alpha = .35f) else WebLine, RoundedCornerShape(20.dp))
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)) {
        Box(Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 3.dp).size(14.dp).background(if (checked) green else WebSub, RoundedCornerShape(50)))
    }
}

@Composable internal fun WebSelect(label: String, value: String, options: List<Pair<String, String>>, change: (String) -> Unit, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    Text(label, color = WebSub, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 5.dp))
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().background(WebPanel2, RoundedCornerShape(9.dp)).border(1.dp, WebLine, RoundedCornerShape(9.dp)).clickable(enabled = enabled) { expanded = true }.padding(12.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(options.firstOrNull { it.first == value }?.second ?: value, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("▾", color = WebSub, fontSize = 12.sp)
        }
        DropdownMenu(expanded, { expanded = false }) { options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text, fontSize = 14.sp) }, onClick = { change(key); expanded = false }) } }
    }
}
@Composable internal fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(10.dp), colors: ButtonColors = ButtonDefaults.outlinedButtonColors(contentColor = FleetAppearance.text),
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp), content: @Composable RowScope.() -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        androidx.compose.material3.OutlinedButton(onClick, modifier, enabled, shape, colors,
            border = BorderStroke(1.dp, WebLine), contentPadding = contentPadding) {
            ProvideTextStyle(TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), content = { content() })
        }
    }
}
@Composable internal fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(8.dp), colors: ButtonColors = ButtonDefaults.textButtonColors(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp, vertical = 4.dp), content: @Composable RowScope.() -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        androidx.compose.material3.TextButton(onClick, modifier, enabled, shape, colors, contentPadding = contentPadding) {
            ProvideTextStyle(TextStyle(fontSize = 13.sp), content = { content() })
        }
    }
}
@Composable internal fun WebInput(value: String, change: (String) -> Unit, enabled: Boolean = true,
    placeholder: String = "", visualTransformation: VisualTransformation = VisualTransformation.None, modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp, minHeight: androidx.compose.ui.unit.Dp = 40.dp, singleLine: Boolean = true) {
    BasicTextField(value, change, enabled = enabled, singleLine = singleLine,
        visualTransformation = visualTransformation,
        textStyle = TextStyle(color = if (enabled) FleetAppearance.text else WebSub, fontSize = fontSize),
        cursorBrush = SolidColor(WebAmber),
        modifier = modifier.fillMaxWidth().background(WebPanel2, RoundedCornerShape(9.dp)).border(1.dp, WebLine, RoundedCornerShape(9.dp))
            .heightIn(min = minHeight).padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { field -> Box { if (value.isEmpty()) Text(placeholder, color = WebSub, fontSize = fontSize); field() } })
}
