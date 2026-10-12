package com.linmjt.whisperofsol.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linmjt.whisperofsol.ui.theme.GlowGold
import com.linmjt.whisperofsol.ui.theme.Gold100
import com.linmjt.whisperofsol.ui.theme.Gold500
import com.linmjt.whisperofsol.ui.theme.Gold700
import com.linmjt.whisperofsol.ui.theme.Green500
import com.linmjt.whisperofsol.ui.theme.Green700
import com.linmjt.whisperofsol.ui.theme.HeroCardShape
import com.linmjt.whisperofsol.ui.theme.HeroNumberStyle
import com.linmjt.whisperofsol.ui.theme.StatNumberStyle
import com.linmjt.whisperofsol.ui.theme.InlineCardShape
import com.linmjt.whisperofsol.ui.theme.Red500
import com.linmjt.whisperofsol.ui.theme.Red700

/**
 * GlowCard — premium surface with subtle gold-tinted shadow.
 *
 * Used for hero cards (treasury, countdown banner, inbox top-card).
 * Adds a 12dp ambient + 14dp spot shadow tinted gold so the card "glows"
 * against the midnight backdrop — a touch of festive magic without
 * sacrificing performance.
 */
@Composable
fun GlowCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    elevation: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = RoundedCornerShape(cornerRadius),
                ambientColor = GlowGold,
                spotColor   = GlowGold,
            ),
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor,
            contentColor   = contentColor,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), // shadow handled above
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        content = { content() },
    )
}

/**
 * StatTile — single stat card with icon, label, big number.
 *
 * Used to display Treasury Balance, Expected Value, etc.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    icon: ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier,
    subValue: String? = null,
) {
    GlowCard(
        modifier = modifier,
        cornerRadius = 24.dp,
        backgroundColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IconBadge(icon = icon, color = accentColor)
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = value,
                style = StatNumberStyle,
                color = accentColor,
                maxLines = 1,
                softWrap = false,
            )
            if (subValue != null) {
                Text(
                    text = subValue,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * IconBadge — small circular tinted icon holder.
 */
@Composable
fun IconBadge(
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
) {
    Row(
        modifier = modifier
            .size(size)
            .background(
                color = color.copy(alpha = 0.18f),
                shape = CircleShape,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * HeroBanner — large radial-gradient banner with title + subtitle.
 *
 * Used as the top element on Main (countdown) and Rules (lottery) screens.
 */
@Composable
fun HeroBanner(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 14.dp,
                shape = HeroCardShape,
                ambientColor = GlowGold,
                spotColor   = GlowGold,
            ),
        shape = HeroCardShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Red700,
                            Red500,
                            Gold500,
                        )
                    ),
                    shape = HeroCardShape,
                )
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                IconBadge(
                    icon = icon,
                    color = Color.White,
                    size = 56.dp,
                    iconSize = 28.dp,
                )
                Spacer(Modifier.width(16.dp))
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    color = Gold100,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * InlineListRow — list-item-style row inside Cards.
 */
@Composable
fun InlineListRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.fillMaxWidth().padding(end = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}