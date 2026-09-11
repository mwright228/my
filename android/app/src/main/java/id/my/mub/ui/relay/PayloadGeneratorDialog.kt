package id.my.mub.ui.relay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.my.mub.ui.theme.*

enum class InjectionMethod(val displayName: String, val desc: String) {
    NORMAL("Normal", "Standard proxy request"),
    FRONT_INJECT("Front Inject", "Front injection before CONNECT request"),
    BACK_INJECT("Back Inject", "Back injection after CONNECT request")
}

enum class SplitMethod(val displayName: String, val token: String) {
    NONE("None", ""),
    NORMAL_SPLIT("Normal Split", "[split]"),
    INSTANT_SPLIT("Instant Split", "[instant_split]")
}

@Composable
fun PayloadGeneratorDialog(
    initialTargetHost: String = "m.facebook.com",
    onDismiss: () -> Unit,
    onGenerated: (String) -> Unit
) {
    val context = LocalContext.current
    var targetHost by remember { mutableStateOf(initialTargetHost.ifBlank { "m.facebook.com" }) }
    var selectedMethod by remember { mutableStateOf("CONNECT") }
    var selectedInjection by remember { mutableStateOf(InjectionMethod.NORMAL) }
    var selectedSplit by remember { mutableStateOf(SplitMethod.NONE) }

    // Header options (HTTP Injector classic)
    var optOnlineHost by remember { mutableStateOf(true) }
    var optForwardHost by remember { mutableStateOf(false) }
    var optReverseProxy by remember { mutableStateOf(false) }
    var optKeepAlive by remember { mutableStateOf(true) }
    var optUserAgent by remember { mutableStateOf(true) }
    var optReferer by remember { mutableStateOf(false) }
    var optDualConnect by remember { mutableStateOf(false) }

    var copiedToast by remember { mutableStateOf(false) }

    // Computes HTTP Injector payload string dynamically
    fun generatePayloadString(): String {
        val host = targetHost.trim().ifBlank { "[host]" }
        val sb = StringBuilder()

        val headers = StringBuilder()
        headers.append("Host: $host[crlf]")
        if (optOnlineHost) headers.append("X-Online-Host: $host[crlf]")
        if (optForwardHost) headers.append("X-Forward-Host: $host[crlf]")
        if (optReverseProxy) headers.append("X-Forwarded-For: $host[crlf]")
        if (optKeepAlive) headers.append("Connection: Keep-Alive[crlf]")
        if (optUserAgent) headers.append("User-Agent: [ua][crlf]")
        if (optReferer) headers.append("Referer: http://$host/[crlf]")

        val splitToken = selectedSplit.token

        when (selectedInjection) {
            InjectionMethod.NORMAL -> {
                if (optDualConnect) {
                    sb.append("CONNECT [host_port] HTTP/1.1[crlf]")
                }
                sb.append("$selectedMethod [host_port] [protocol][crlf]")
                if (splitToken.isNotEmpty()) sb.append(splitToken)
                sb.append(headers)
                sb.append("[crlf]")
            }
            InjectionMethod.FRONT_INJECT -> {
                sb.append("GET http://$host/ [protocol][crlf]Host: $host[crlf][crlf]")
                if (splitToken.isNotEmpty()) sb.append(splitToken)
                if (optDualConnect) {
                    sb.append("CONNECT [host_port] HTTP/1.1[crlf]")
                }
                sb.append("$selectedMethod [host_port] [protocol][crlf]")
                sb.append(headers)
                sb.append("[crlf]")
            }
            InjectionMethod.BACK_INJECT -> {
                if (optDualConnect) {
                    sb.append("CONNECT [host_port] HTTP/1.1[crlf]")
                }
                sb.append("$selectedMethod [host_port] [protocol][crlf]")
                sb.append(headers)
                sb.append("[crlf]")
                if (splitToken.isNotEmpty()) sb.append(splitToken)
                sb.append("GET http://$host/ [protocol][crlf]Host: $host[crlf][crlf]")
            }
        }
        return sb.toString()
    }

    val previewPayload = remember(
        targetHost, selectedMethod, selectedInjection, selectedSplit,
        optOnlineHost, optForwardHost, optReverseProxy, optKeepAlive,
        optUserAgent, optReferer, optDualConnect
    ) {
        generatePayloadString()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.90f)
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
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Payload Generator",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "HTTP Injector format carrier payload generator",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 1. Target URL / Host
                Text("URL / Host (Carrier Bug)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = targetHost,
                    onValueChange = { targetHost = it },
                    placeholder = { Text("e.g. m.facebook.com or fast.com", fontSize = 12.sp, color = TextMuted) },
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

                // 2. Request Method (Horizontal Scrollable Chips)
                Text("Request Method", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val methods = listOf("CONNECT", "GET", "POST", "HEAD", "TRACE", "OPTIONS", "PUT", "DELETE", "PATCH")
                    for (m in methods) {
                        val isSelected = selectedMethod == m
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) AccentPrimary else BgMain)
                                .border(1.dp, if (isSelected) AccentPrimary else SurfaceCardBorder, RoundedCornerShape(8.dp))
                                .clickable { selectedMethod = m }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
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
                                .background(if (isSelected) BgMain else Color.Transparent)
                                .border(1.dp, if (isSelected) AccentPrimary.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable { selectedInjection = inj }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { selectedInjection = inj },
                                colors = RadioButtonDefaults.colors(selectedColor = AccentPrimary)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(inj.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text(inj.desc, fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 4. Split Method
                Text("Split Injection", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (sm in SplitMethod.values()) {
                        val isSelected = selectedSplit == sm
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) AccentPrimary else BgMain)
                                .border(1.dp, if (isSelected) AccentPrimary else SurfaceCardBorder, RoundedCornerShape(8.dp))
                                .clickable { selectedSplit = sm }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = sm.displayName,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color.White else TextSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 5. Extra Headers Checkboxes
                Text("Extra Headers", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(modifier = Modifier.height(4.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = optOnlineHost, onCheckedChange = { optOnlineHost = it }, colors = CheckboxDefaults.colors(checkedColor = AccentPrimary))
                        Text("Online Host", fontSize = 12.sp, color = TextPrimary)
                    }
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = optForwardHost, onCheckedChange = { optForwardHost = it }, colors = CheckboxDefaults.colors(checkedColor = AccentPrimary))
                        Text("Forward Host", fontSize = 12.sp, color = TextPrimary)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = optReverseProxy, onCheckedChange = { optReverseProxy = it }, colors = CheckboxDefaults.colors(checkedColor = AccentPrimary))
                        Text("Reverse Proxy", fontSize = 12.sp, color = TextPrimary)
                    }
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = optKeepAlive, onCheckedChange = { optKeepAlive = it }, colors = CheckboxDefaults.colors(checkedColor = AccentPrimary))
                        Text("Keep-Alive", fontSize = 12.sp, color = TextPrimary)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = optUserAgent, onCheckedChange = { optUserAgent = it }, colors = CheckboxDefaults.colors(checkedColor = AccentPrimary))
                        Text("User-Agent", fontSize = 12.sp, color = TextPrimary)
                    }
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = optReferer, onCheckedChange = { optReferer = it }, colors = CheckboxDefaults.colors(checkedColor = AccentPrimary))
                        Text("Referer", fontSize = 12.sp, color = TextPrimary)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = optDualConnect, onCheckedChange = { optDualConnect = it }, colors = CheckboxDefaults.colors(checkedColor = AccentPrimary))
                    Text("Dual Connect (Double CONNECT Header)", fontSize = 12.sp, color = TextPrimary)
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 6. Payload Preview Box with Copy
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Payload Preview", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    TextButton(
                        onClick = {
                            val clipMgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipMgr?.setPrimaryClip(ClipData.newPlainText("payload", previewPayload))
                            copiedToast = true
                        }
                    ) {
                        Text(if (copiedToast) "✓ Copied" else "Copy", fontSize = 11.sp, color = CyberMint, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(BgMain)
                        .border(1.dp, SurfaceCardBorder, RoundedCornerShape(8.dp))
                        .padding(12.dp)
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

                // 7. Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceCardBorder),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Cancel", fontSize = 13.sp)
                    }

                    Button(
                        onClick = {
                            onGenerated(previewPayload)
                        },
                        modifier = Modifier.weight(1.5f),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Apply to Payload", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}
