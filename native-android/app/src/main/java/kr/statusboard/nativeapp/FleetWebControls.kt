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
    placeholder: String = "", visualTransformation: VisualTransformation = VisualTransformation.None, modifier: Modifier = Modifier) {
    BasicTextField(value, change, enabled = enabled, singleLine = true,
        visualTransformation = visualTransformation,
        textStyle = TextStyle(color = if (enabled) FleetAppearance.text else WebSub, fontSize = 14.sp),
        cursorBrush = SolidColor(WebAmber),
        modifier = modifier.fillMaxWidth().background(WebPanel2, RoundedCornerShape(9.dp)).border(1.dp, WebLine, RoundedCornerShape(9.dp))
            .heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { field -> Box { if (value.isEmpty()) Text(placeholder, color = WebSub, fontSize = 14.sp); field() } })
}
