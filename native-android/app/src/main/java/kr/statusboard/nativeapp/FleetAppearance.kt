package kr.statusboard.nativeapp

import android.content.Context
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Device-only appearance preference; never changes shared company settings. */
internal object FleetAppearance {
    var dark by mutableStateOf(true)
        private set
    fun load(context: Context) { dark = context.getSharedPreferences("native_appearance", Context.MODE_PRIVATE).getBoolean("dark", true) }
    fun select(context: Context, value: Boolean) {
        dark = value
        context.getSharedPreferences("native_appearance", Context.MODE_PRIVATE).edit().putBoolean("dark", value).apply()
    }
    val background get() = if (dark) Color(0xFF0F172A) else Color(0xFFF4F6FA)
    val panel get() = if (dark) Color(0xFF16213A) else Color.White
    val panel2 get() = if (dark) Color(0xFF1C2947) else Color(0xFFEBF0F7)
    val line get() = if (dark) Color(0xFF2A3757) else Color(0xFFD3DCE8)
    val text get() = if (dark) Color(0xFFE8ECF7) else Color(0xFF1C2D44)
    val sub get() = if (dark) Color(0xFF8B96B8) else Color(0xFF596B83)
    val amber get() = if (dark) Color(0xFFF5A623) else Color(0xFF946015)
    fun scheme() = if (dark) darkColorScheme(primary = amber, onPrimary = panel, background = background,
        surface = panel, onSurface = text, onBackground = text, outline = line, secondary = sub)
    else lightColorScheme(primary = amber, onPrimary = Color.White, background = background,
        surface = panel, onSurface = text, onBackground = text, outline = line, secondary = sub)
}
