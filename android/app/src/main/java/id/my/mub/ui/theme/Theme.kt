package id.my.mub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    secondary = NeonViolet,
    tertiary = CyberMint,
    background = BgObsidian,
    surface = SurfaceCard,
    onPrimary = BgObsidian,
    onSecondary = PureWhite,
    onBackground = PureWhite,
    onSurface = PureWhite,
    error = CoralRed
)

@Composable
fun MubxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}

@Composable
fun MubxVpnTheme(content: @Composable () -> Unit) = MubxTheme(content)

