package id.my.mub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = AccentPrimary,
    secondary = AccentPrimary,
    tertiary = AccentSuccess,
    background = BgMain,
    surface = SurfaceCard,
    onPrimary = PureWhite,
    onSecondary = PureWhite,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = AccentError
)

@Composable
fun NetPulseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}

@Composable
fun MubxTheme(content: @Composable () -> Unit) = NetPulseTheme(content)

@Composable
fun MubxVpnTheme(content: @Composable () -> Unit) = NetPulseTheme(content)
