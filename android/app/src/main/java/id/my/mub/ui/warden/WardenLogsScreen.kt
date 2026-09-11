package id.my.mub.ui.warden

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.my.mub.data.LogLevel
import id.my.mub.data.LogRepository
import id.my.mub.ui.theme.*

data class DemoLogItem(
    val time: String,
    val level: String,
    val color: Color,
    val message: String
)

val DEFAULT_DEMO_LOGS = listOf(
    DemoLogItem("12:04:11", "core", WardenMint, "vless-reality handshake ok — frankfurt-3 (42ms)"),
    DemoLogItem("12:04:12", "route", WardenIndigo, "geosite:netflix → proxy-sticky group"),
    DemoLogItem("12:04:15", "dns", WardenMuted, "resolved instagram.com via DoH (cloudflare)"),
    DemoLogItem("12:04:18", "tun", WardenMuted, "gVisor stack up, mtu 1420"),
    DemoLogItem("12:04:20", "warn", WardenAmber, "hysteria2 singapore-1 packet loss 4%"),
    DemoLogItem("12:04:24", "udpgw", WardenIndigo, "udp relay bound 127.0.0.1:7300"),
    DemoLogItem("12:04:26", "split", WardenIndigo, "PUBG_MOBILE bound to vless-pubg node")
)

@Composable
fun WardenLogsScreen() {
    val liveLogs by LogRepository.logs.collectAsState()
    val listState = rememberLazyListState()

    // Blinking cursor animation
    val infiniteTransition = rememberInfiniteTransition(label = "cursorBlink")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )

    LaunchedEffect(liveLogs.size) {
        if (liveLogs.isNotEmpty()) {
            listState.animateScrollToItem(liveLogs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        // Header with Clear button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Logs",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = WardenText
            )

            if (liveLogs.isNotEmpty()) {
                IconButton(
                    onClick = { LogRepository.clear() },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Clear",
                        tint = WardenMutedDim,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Terminal Card with #080A0E background
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF080A0E))
                .border(1.dp, WardenBorder, RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            if (liveLogs.isEmpty()) {
                // Display spec demo logs if no live VPN connection has started yet
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(DEFAULT_DEMO_LOGS) { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = item.time,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.8.sp,
                                color = WardenMutedDim
                            )
                            Text(
                                text = item.level,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.8.sp,
                                color = item.color,
                                modifier = Modifier.widthIn(min = 46.dp)
                            )
                            Text(
                                text = item.message,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.8.sp,
                                color = Color(0xFFB9C3CB),
                                modifier = Modifier.weight(1f),
                                lineHeight = 16.sp
                            )
                        }
                    }

                    item {
                        Text(
                            text = "▍",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = WardenMint,
                            modifier = Modifier.alpha(cursorAlpha)
                        )
                    }
                }
            } else {
                // Live logs
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(liveLogs) { entry ->
                        val (levelStr, color) = when (entry.level) {
                            LogLevel.SUCCESS -> "ok" to WardenMint
                            LogLevel.WARN -> "warn" to WardenAmber
                            LogLevel.ERROR -> "err" to WardenRed
                            LogLevel.NET -> "route" to WardenIndigo
                            else -> entry.tag.lowercase() to (if (entry.tag.contains("TUN", ignoreCase = true)) WardenMuted else WardenMint)
                        }

                        val timeShort = if (entry.formattedTime.length >= 8) entry.formattedTime.substring(0, 8) else entry.formattedTime

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = timeShort,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.8.sp,
                                color = WardenMutedDim
                            )
                            Text(
                                text = levelStr,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.8.sp,
                                color = color,
                                modifier = Modifier.widthIn(min = 46.dp)
                            )
                            Text(
                                text = entry.message,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.8.sp,
                                color = Color(0xFFB9C3CB),
                                modifier = Modifier.weight(1f),
                                lineHeight = 16.sp
                            )
                        }
                    }

                    item {
                        Text(
                            text = "▍",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = WardenMint,
                            modifier = Modifier.alpha(cursorAlpha)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}
