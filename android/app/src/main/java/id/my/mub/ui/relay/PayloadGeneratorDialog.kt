package id.my.mub.ui.relay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.my.mub.ui.theme.*

enum class InjectionMethod(val displayName: String, val desc: String) {
    NORMAL("Normal", "Standard payload injection"),
    FRONT_INJECT("Front Inject", "Front inject before CONNECT"),
    BACK_INJECT("Back Inject", "Back inject after CONNECT")
}

@Composable
fun PayloadGeneratorDialog(
    initialTargetHost: String = "m.facebook.com",
    onDismiss: () -> Unit,
    onGenerated: (String) -> Unit
) {
    var targetHost by remember { mutableStateOf(initialTargetHost.ifBlank { "m.facebook.com" }) }
    var selectedMethod by remember { mutableStateOf("CONNECT") }
    var selectedInjection by remember { mutableStateOf(InjectionMethod.NORMAL) }

    // Header checkboxes
    var optOnlineHost by remember { mutableStateOf(true) }
    var optKeepAlive by remember { mutableStateOf(true) }
    var optUserAgent by remember { mutableStateOf(true) }
    var optReverseProxy by remember { mutableStateOf(false) }

    // Computes HTTP Injector payload string based on current user options
    fun generatePayloadString(): String {
        val host = targetHost.trim().ifBlank { "[host]" }
        val sb = StringBuilder()

        val headers = StringBuilder()
        headers.append("Host: $host[crlf]")
        if (optOnlineHost) headers.append("X-Online-Host: $host[crlf]")
        if (optReverseProxy) headers.append("X-Forward-Host: $host[crlf]")
        if (optKeepAlive) headers.append("Connection: Keep-Alive[crlf]")
        if (optUserAgent) headers.append("User-Agent: [ua][crlf]")

        when (selectedInjection) {
            InjectionMethod.NORMAL -> {
                sb.append("$selectedMethod [host_port] [protocol][crlf]")
                sb.append(headers)
                sb.append("[crlf]")
            }
            InjectionMethod.FRONT_INJECT -> {
                sb.append("GET http://$host/ [protocol][crlf]Host: $host[crlf][crlf]")
                sb.append("$selectedMethod [host_port] [protocol][crlf]")
                sb.append(headers)
                sb.append("[crlf]")
            }
            InjectionMethod.BACK_INJECT -> {
                sb.append("$selectedMethod [host_port] [protocol][crlf]")
                sb.append(headers)
                sb.append("[crlf]")
                sb.append("GET http://$host/ [protocol][crlf]Host: $host[crlf][crlf]")
            }
        }
        return sb.toString()
    }

    val previewPayload = remember(targetHost, selectedMethod, selectedInjection, optOnlineHost, optKeepAlive, optUserAgent, optReverseProxy) {
        generatePayloadString()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceCardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Payload Generator",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Configure and generate custom HTTP carrier payloads",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 14.dp)
                )

                // 1. Target URL / Host
                Text("URL / Host (Target)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = targetHost,
                    onValueChange = { targetHost = it },
                    placeholder = { Text("e.g. m.facebook.com", fontSize = 12.sp, color = TextMuted) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentPrimary,
                        unfocusedBorderColor = SurfaceCardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 2. Request Method
                Text("Request Method", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val methods = listOf("CONNECT", "GET", "POST", "HEAD")
                    for (m in methods) {
                        val isSelected = selectedMethod == m
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) AccentPrimary else BgMain)
                                .border(1.dp, if (isSelected) AccentPrimary else SurfaceCardBorder, RoundedCornerShape(8.dp))
                                .clickable { selectedMethod = m }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = m,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color.White else TextSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 3. Injection Method
                Text("Injection Method", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(modifier = Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (inj in InjectionMethod.values()) {
                        val isSelected = selectedInjection == inj
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) BgMain.copy(alpha = 0.8f) else Color.Transparent)
                                .clickable { selectedInjection = inj }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { selectedInjection = inj },
                                colors = RadioButtonDefaults.colors(selectedColor = AccentPrimary)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(inj.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text(inj.desc, fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 4. Extra Headers Checkboxes
                Text("Extra Headers", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = optOnlineHost,
                            onCheckedChange = { optOnlineHost = it },
                            colors = CheckboxDefaults.colors(checkedColor = AccentPrimary)
                        )
                        Text("Online Host", fontSize = 12.sp, color = TextPrimary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = optKeepAlive,
                            onCheckedChange = { optKeepAlive = it },
                            colors = CheckboxDefaults.colors(checkedColor = AccentPrimary)
                        )
                        Text("Keep-Alive", fontSize = 12.sp, color = TextPrimary)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = optUserAgent,
                            onCheckedChange = { optUserAgent = it },
                            colors = CheckboxDefaults.colors(checkedColor = AccentPrimary)
                        )
                        Text("User-Agent", fontSize = 12.sp, color = TextPrimary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = optReverseProxy,
                            onCheckedChange = { optReverseProxy = it },
                            colors = CheckboxDefaults.colors(checkedColor = AccentPrimary)
                        )
                        Text("Reverse Proxy", fontSize = 12.sp, color = TextPrimary)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 5. Payload Preview
                Text("Payload Preview", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(BgMain)
                        .border(1.dp, SurfaceCardBorder, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = previewPayload,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = CyberMint,
                        lineHeight = 16.sp
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 6. Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceCardBorder)
                    ) {
                        Text("Cancel", fontSize = 13.sp)
                    }

                    Button(
                        onClick = {
                            onGenerated(previewPayload)
                        },
                        modifier = Modifier.weight(1.5f),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
                    ) {
                        Text("Apply Payload", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
