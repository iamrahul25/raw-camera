package com.example.first_app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Accent = Color(0xFFFFD60A)
internal val RecordRed = Color(0xFFFF3B30)
internal val Warning = Color(0xFFFF9F0A)
internal val CardColor = Color(0xFF161618)
internal val SheetColor = Color(0xFF111113)
internal val PillColor = Color(0x1FFFFFFF)
internal val DividerColor = Color(0x1AFFFFFF)
internal val TextPrimary = Color.White
internal val TextSecondary = Color(0xFF8E8E93)

@Composable
internal fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
) {
    val content = if (selected) Color.Black else TextPrimary
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(if (selected) TextPrimary else PillColor)
            .clickable(enabled = enabled, onClick = onClick)
            .height(36.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { Icon(painterResource(it), contentDescription = null, tint = content, modifier = Modifier.size(16.dp)) }
        Text(text, color = content, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
internal fun Stat(value: String, caption: String, modifier: Modifier = Modifier, valueColor: Color = TextPrimary) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = valueColor, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(caption, color = TextSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
