package com.github.tvbox.osc.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.github.tvbox.osc.util.HawkConfig
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.github.tvbox.osc.util.KV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object AppThemeState {

    private var current by mutableStateOf(load())

    val config: ThemeConfig get() = current

    private fun load(): ThemeConfig = ThemeConfig(
        source = KV.get(HawkConfig.THEME_SOURCE, ThemeSource.SYSTEM),
        mode = KV.get(HawkConfig.THEME_MODE, ThemeMode.FOLLOW_SYSTEM),
        seedArgb = KV.get(HawkConfig.THEME_SEED, DefaultSeedArgb),
        style = runCatching { PaletteStyle.valueOf(KV.get(HawkConfig.THEME_PALETTE_STYLE, "")) }
            .getOrDefault(DefaultPaletteStyle),
        pureBlack = KV.get(HawkConfig.THEME_PURE_BLACK, false),
    )

    fun setSource(source: Int) {
        KV.put(HawkConfig.THEME_SOURCE, source)
        current = current.copy(source = source)
    }

    fun setMode(mode: Int) {
        KV.put(HawkConfig.THEME_MODE, mode)
        current = current.copy(mode = mode)
    }

    fun setSeed(argb: Int) {
        KV.put(HawkConfig.THEME_SEED, argb)
        current = current.copy(seedArgb = argb)
    }

    fun setStyle(style: PaletteStyle) {
        KV.put(HawkConfig.THEME_PALETTE_STYLE, style.name)
        current = current.copy(style = style)
    }

    fun setPureBlack(enabled: Boolean) {
        KV.put(HawkConfig.THEME_PURE_BLACK, enabled)
        current = current.copy(pureBlack = enabled)
    }

    fun isDark(systemDark: Boolean): Boolean = when (current.mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> systemDark
    }

    fun resolveScheme(context: Context, config: ThemeConfig, dark: Boolean): ColorScheme = when {
        config.source == ThemeSource.CUSTOM -> customScheme(config.seedArgb, dark, config.style)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }

    fun windowSurfaceArgb(context: Context): Int {
        val dark = isDark(isSystemDark(context))
        val scheme = resolveScheme(context, current, dark)
        val resolved = if (dark && current.pureBlack) scheme.toPureBlack() else scheme
        return resolved.surface.toArgb()
    }

    private fun isSystemDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private val schemeCache = ConcurrentHashMap<Triple<Int, Boolean, PaletteStyle>, ColorScheme>()

    fun customScheme(seedArgb: Int, isDark: Boolean, style: PaletteStyle): ColorScheme =
        schemeCache.getOrPut(Triple(seedArgb, isDark, style)) {
            dynamicColorScheme(seedColor = Color(seedArgb), isDark = isDark, style = style)
        }

    private val previewCache = ConcurrentHashMap<Pair<Int, PaletteStyle>, ColorScheme>()

    suspend fun previewScheme(seedArgb: Int, style: PaletteStyle): ColorScheme {
        previewCache[seedArgb to style]?.let { return it }
        return withContext(Dispatchers.Default) {
            previewCache.getOrPut(seedArgb to style) {
                dynamicColorScheme(seedColor = Color(seedArgb), isDark = false, style = style)
            }
        }
    }
}
