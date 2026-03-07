package com.garbagesys.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.garbagesys.R
import com.garbagesys.ui.theme.*

// ── Logo ──
@Composable
fun GsLogo(size: Dp = 48.dp) {
    Icon(
        painter = painterResource(R.drawable.ic_garbagesys),
        contentDescription = "GarbageSys",
        tint = GreenPrimary,
        modifier = Modifier.size(size)
    )
}

// ── Card ──
@Composable
fun GsCard(
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .alpha(alpha),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(0.5.dp, BorderColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

// ── Metric Card ──
@Composable
fun GsMetricCard(
    label: String,
    value: String,
    icon: ImageVector,
    valueColor: Color = TextPrimary,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = BgCard),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(0.5.dp, BorderColor)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(icon, null, tint = TextMuted, modifier = Modifier.size(12.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
            Text(value, style = MaterialTheme.typography.headlineLarge, color = valueColor)
        }
    }
}

// ── Button ──
@Composable
fun GsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(48.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = GreenPrimary,
            contentColor = BgDeep,
            disabledContainerColor = TextMuted.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = BgDeep)
    }
}

// ── Outline Button ──
@Composable
fun GsOutlineButton(
    text: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        border = BorderStroke(1.dp, GreenPrimary.copy(alpha = 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = GreenPrimary),
        shape = RoundedCornerShape(10.dp)
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(14.dp), tint = GreenPrimary)
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = GreenPrimary)
    }
}

// ── Status Badge ──
@Composable
fun GsStatusBadge(running: Boolean, label: String) {
    Row(
        modifier = Modifier
            .background(
                if (running) GreenGlow else BorderColor,
                RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(if (running) GreenPrimary else TextMuted, CircleShape)
        )
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = if (running) GreenPrimary else TextMuted)
    }
}

// ── Chip ──
@Composable
fun GsChip(text: String, color: Color = GreenPrimary) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color,
            fontSize = 9.sp, letterSpacing = 0.5.sp)
    }
}

// ── Label ──
@Composable
fun GsLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = GreenPrimary,
        letterSpacing = 1.sp
    )
}

// ── Body Text ──
@Composable
fun GsBodyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
}

// ── Divider ──
@Composable
fun GsDivider() {
    HorizontalDivider(color = BorderColor, thickness = 0.5.dp)
}
