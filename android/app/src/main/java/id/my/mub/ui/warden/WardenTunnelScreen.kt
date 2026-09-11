package id.my.mub.ui.warden

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.my.mub.ui.theme.*

data class RouteRuleItem(
    val match: String,
    val action: String,
    val color: Color
)

data class SplitAppItem(
    val name: String,
    val rule: String,
    val on: Boolean
)

val DEFAULT_RULES = listOf(
    RouteRuleItem("geosite:netflix", "Proxy · sticky", WardenMint),
    RouteRuleItem("geosite:cn", "Direct", WardenMuted),
    RouteRuleItem("geoip:private", "Direct", WardenMuted),
    RouteRuleItem("geosite:ads (AWAvenue)", "Block", WardenRed)
)

val DEFAULT_SPLIT_APPS = listOf(
    SplitAppItem("PUBG Mobile", "Proxy · dedicated node", true),
    SplitAppItem("WhatsApp", "Bypass", true),
    SplitAppItem("Chrome", "Proxy", true),
    SplitAppItem("Instagram", "Bypass", false),
    SplitAppItem("Banking app", "Bypass", true)
)

@Composable
fun WardenTunnelScreen() {
    var rules by remember { mutableStateOf(DEFAULT_RULES) }
    var apps by remember { mutableStateOf(DEFAULT_SPLIT_APPS) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Tunnel",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = WardenText
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Domain Routing Section
        WardenSectionLabel(
            title = "Domain routing",
            rightText = "${rules.size} rules"
        )
        WardenCard(padding = PaddingValues(0.dp)) {
            rules.forEachIndexed { i, r ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = r.match,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        color = WardenText
                    )
                    WardenChip(text = r.action, color = r.color)
                }

                if (i < rules.size - 1) {
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(WardenBorder))
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Split Tunneling Section
        val activeCount = apps.count { it.on }
        WardenSectionLabel(
            title = "Split tunneling",
            rightText = "$activeCount bypassed/proxied"
        )
        WardenCard(padding = PaddingValues(0.dp)) {
            apps.forEachIndexed { i, a ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = a.name,
                            fontSize = 12.5.sp,
                            color = WardenText,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = a.rule,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = WardenMutedDim
                        )
                    }

                    WardenToggle(
                        on = a.on,
                        onToggle = {
                            apps = apps.toMutableList().also { list ->
                                list[i] = a.copy(on = !a.on)
                            }
                        }
                    )
                }

                if (i < apps.size - 1) {
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(WardenBorder))
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Note Section
        WardenSectionLabel(title = "Note")
        WardenCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.VpnKey,
                    contentDescription = null,
                    tint = WardenViolet,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "SSH accounts, UDPGW & payload editor live under Servers → SSH accounts",
                    fontSize = 11.5.sp,
                    color = WardenMuted,
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}
