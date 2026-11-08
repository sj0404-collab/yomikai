package eu.kanade.tachiyomi.ui.koma

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFFF986D),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF2A1C15),
    background = androidx.compose.ui.graphics.Color(0xFF191A18),
    surface = androidx.compose.ui.graphics.Color(0xFF22231F),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF30312B),
    onBackground = androidx.compose.ui.graphics.Color(0xFFF6F3EA),
    onSurface = androidx.compose.ui.graphics.Color(0xFFF6F3EA),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFB7B8AD),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFF414238),
    secondary = androidx.compose.ui.graphics.Color(0xFFA7B89C)
)

private val LightColorScheme = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFE7784C),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    background = androidx.compose.ui.graphics.Color(0xFFF8F6F0),
    surface = androidx.compose.ui.graphics.Color(0xFFFFFDF8),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFEFEDE5),
    onBackground = androidx.compose.ui.graphics.Color(0xFF252721),
    onSurface = androidx.compose.ui.graphics.Color(0xFF252721),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF84857B),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFFE4E3D9),
    secondary = androidx.compose.ui.graphics.Color(0xFF68765E)
)

@Composable
fun KomaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val family = androidx.compose.ui.text.font.FontFamily(
        androidx.compose.ui.text.font.Font(eu.kanade.tachiyomi.R.font.koma_manrope_regular, androidx.compose.ui.text.font.FontWeight.Normal),
        androidx.compose.ui.text.font.Font(eu.kanade.tachiyomi.R.font.koma_manrope_medium, androidx.compose.ui.text.font.FontWeight.Medium),
        androidx.compose.ui.text.font.Font(eu.kanade.tachiyomi.R.font.koma_manrope_semibold, androidx.compose.ui.text.font.FontWeight.SemiBold),
        androidx.compose.ui.text.font.Font(eu.kanade.tachiyomi.R.font.koma_manrope_bold, androidx.compose.ui.text.font.FontWeight.Bold),
        androidx.compose.ui.text.font.Font(eu.kanade.tachiyomi.R.font.koma_manrope_extrabold, androidx.compose.ui.text.font.FontWeight.Black)
    )
    val base = androidx.compose.material3.Typography()
    MaterialTheme(
        colorScheme = colorScheme,
        typography = androidx.compose.material3.Typography(
            bodyLarge=base.bodyLarge.copy(fontFamily=family),bodyMedium=base.bodyMedium.copy(fontFamily=family),bodySmall=base.bodySmall.copy(fontFamily=family),
            titleLarge=base.titleLarge.copy(fontFamily=family),titleMedium=base.titleMedium.copy(fontFamily=family),titleSmall=base.titleSmall.copy(fontFamily=family),
            labelLarge=base.labelLarge.copy(fontFamily=family),labelMedium=base.labelMedium.copy(fontFamily=family),labelSmall=base.labelSmall.copy(fontFamily=family),
            headlineLarge=base.headlineLarge.copy(fontFamily=family),headlineMedium=base.headlineMedium.copy(fontFamily=family),headlineSmall=base.headlineSmall.copy(fontFamily=family)
        ),
        content = content,
    )
}
