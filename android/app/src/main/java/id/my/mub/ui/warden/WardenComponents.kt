package id.my.mub.ui.warden

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.my.mub.ui.theme.*

@Composable
fun WardenCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = WardenSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, WardenBorder)
    ) {
        Column(modifier = Modifier.padding(padding)) {
            content()
        }
    }
}

@Composable
fun WardenSectionLabel(
    title: String,
    rightText: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = WardenText
        )
        if (rightText != null) {
            Text(
                text = rightText,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = WardenMutedDim
            )
        }
    }
}

@Composable
fun WardenToggle(
    on: Boolean,
    onToggle: () -> Unit,
    color: Color = WardenMint
) {
    val thumbOffset by animateDpAsState(targetValue = if (on) 18.dp else 2.dp, label = "thumbOffset")
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (on) color.copy(alpha = 0.2f) else WardenSurface2)
            .border(1.dp, if (on) color else WardenBorder, RoundedCornerShape(999.dp))
            .clickable { onToggle() }
            .padding(2.dp)
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(18.dp)
                .clip(CircleShape)
                .background(if (on) color else WardenMutedDim)
        )
    }
}

@Composable
fun WardenLoadBars(
    load: Int,
    color: Color = WardenMint
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.height(16.dp)
    ) {
        for (i in 1..5) {
            val barHeight = (4 + i * 2).dp
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i <= load) color else WardenSurface2)
            )
        }
    }
}

@Composable
fun WardenChip(
    text: String,
    color: Color,
    maxWidth: androidx.compose.ui.unit.Dp = 160.dp
) {
    Box(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(7.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun WardenField(
    label: String,
    value: String,
    color: Color = WardenText,
    onValueChange: ((String) -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = WardenMutedDim,
            modifier = Modifier.padding(bottom = 3.dp)
        )
        if (onValueChange == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(WardenSurface2)
                    .border(1.dp, WardenBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Text(
                    text = value,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    color = color,
                    lineHeight = 16.sp
                )
            }
        } else {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = WardenMint,
                    unfocusedBorderColor = WardenBorder,
                    focusedContainerColor = WardenSurface2,
                    unfocusedContainerColor = WardenSurface2,
                    focusedTextColor = color,
                    unfocusedTextColor = color
                ),
                shape = RoundedCornerShape(8.dp)
            )
        }
    }
}

@Composable
fun WardenSelectRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    color: Color = WardenIndigo
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (opt in options) {
            val active = opt == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) color.copy(alpha = 0.15f) else Color.Transparent)
                    .border(1.dp, if (active) color.copy(alpha = 0.6f) else WardenBorder, RoundedCornerShape(8.dp))
                    .clickable { onSelect(opt) }
                    .padding(horizontal = 11.dp, vertical = 6.dp)
            ) {
                Text(
                    text = opt,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (active) color else WardenMutedDim
                )
            }
        }
    }
}

@Composable
fun WardenQuickAction(
    icon: ImageVector,
    label: String,
    color: Color = WardenText,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(WardenSurface)
            .border(1.dp, WardenBorder, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = WardenMutedDim, modifier = Modifier.size(13.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
fun WardenSaveButton(
    label: String,
    onClick: () -> Unit,
    color: Color = WardenMint
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color.copy(alpha = 0.15f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.6f))
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = color
        )
    }
}
