package com.linmjt.whisperofsol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linmjt.whisperofsol.R
import com.linmjt.whisperofsol.ui.theme.GlowGold
import com.linmjt.whisperofsol.ui.theme.Gold100
import com.linmjt.whisperofsol.ui.theme.Gold300
import com.linmjt.whisperofsol.ui.theme.Gold500
import com.linmjt.whisperofsol.ui.theme.HeroCardShape
import com.linmjt.whisperofsol.ui.theme.Midnight900
import com.linmjt.whisperofsol.ui.theme.Red500
import com.linmjt.whisperofsol.ui.theme.Red700
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * ChristmasCountdown — top-of-screen banner with live ticking countdown
 * to 25 Dec 00:00 (Christmas Eve midnight).
 *
 * Three states:
 *   • Days remaining — 12+ days (default)
 *   • Hours remaining — under 1 day
 *   • Christmas Day — "It's here!"
 *   • Post-Christmas — "X days since Christmas"
 *
 * Stage 1.5 fix: replaced the `$n day(s)` hack with proper
 * [pluralStringResource] entries so the count reads "1 day" /
 * "84 days" instead of "84 day(s)".
 *
 * Updates every second via [LaunchedEffect] coroutine; lightweight.
 */
@Composable
fun ChristmasCountdown(
    modifier: Modifier = Modifier,
    targetYear: Int? = null,
) {
    val year = targetYear ?: LocalDate.now().year
    val target = LocalDateTime.of(year, 12, 25, 0, 0)
    val now = remember { mutableStateOf(LocalDateTime.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            now.value = LocalDateTime.now()
            delay(1_000)
        }
    }

    val diff = ChronoUnit.SECONDS.between(now.value, target)
    val isPast = diff < 0

    val (mainText, subText) = when {
        isPast -> {
            val daysSince = ChronoUnit.DAYS.between(target.toLocalDate(), LocalDate.now())
            "Merry Christmas!" to pluralStringResource(  // v1.9.11: drop 🎉
                R.plurals.days_since_christmas,
                daysSince.toInt(),
                daysSince.toInt(),
            )
        }
        diff == 0L -> "Merry Christmas!" to "It's 12/25 right now"  // v1.9.11: drop 🎄
        diff < 60 -> "$diff seconds" to "Almost time!"  // v1.9.11: drop 🎁
        diff < 3600 -> "${diff / 60} min ${diff % 60}s" to "Final hour!"  // v1.9.11: drop 🎁
        diff < 86_400 -> "${diff / 3600}h ${(diff % 3600) / 60}m" to "Almost Christmas Eve"  // v1.9.11: drop 🎄
        else -> {
            val days = (diff / 86_400).toInt()
            pluralStringResource(R.plurals.countdown_main, days, days) to
                "Until 12/25 • Unlock 50% jackpot bonus"
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 14.dp,
                shape = HeroCardShape,
                ambientColor = GlowGold,
                spotColor   = GlowGold,
            )
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(Red700, Red500, Gold500, Gold300)
                ),
                shape = HeroCardShape,
            )
            .padding(horizontal = 22.dp, vertical = 22.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "DISTANCE TO 12/25",
                    color = Gold100,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = mainText,
                color = Color.White,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subText,
                color = Gold100,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * DaysUntilChristmas — compact countdown text for cards.
 *
 * Stage 1.5 fix: pluralised ("$n day(s)" → "1 day" / "2 days").
 */
@Composable
fun DaysUntilChristmas(
    modifier: Modifier = Modifier,
    year: Int = LocalDate.now().year,
) {
    val today = LocalDate.now()
    val christmas = LocalDate.of(year, 12, 25)
    val days = ChronoUnit.DAYS.between(today, christmas)
    val text = when {
        days > 0  -> pluralStringResource(
            R.plurals.days_until_christmas,
            days.toInt(),
            days.toInt(),
        )
        days == 0L -> "Christmas Day!"  // v1.9.11: drop 🎉
        else      -> {
            val sinceDays = -days.toInt()
            pluralStringResource(
                R.plurals.days_since_christmas,
                sinceDays,
                sinceDays,
            )
        }
    }
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
}
