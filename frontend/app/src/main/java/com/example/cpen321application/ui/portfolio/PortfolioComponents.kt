package com.example.cpen321application.ui.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

internal val PortfolioGreen: Color @Composable get() = MaterialTheme.colorScheme.primary

internal val PortfolioRed: Color @Composable get() = MaterialTheme.colorScheme.error

internal val PortfolioInk: Color @Composable get() = MaterialTheme.colorScheme.onSurface

internal val PortfolioMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

internal val PortfolioBorder: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant

@Composable
internal fun SectionTitle(title: String, detail: String) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        if (detail.isNotEmpty()) Text(detail, color = PortfolioMuted, fontSize = 12.sp)
    }
}

@Composable
internal fun PortfolioPanel(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth()
            .background(tint, RoundedCornerShape(20.dp))
            .border(1.dp, PortfolioBorder, RoundedCornerShape(20.dp))
            .padding(18.dp),
        content = content,
    )
}

@Composable
internal fun ProfitLoss(amount: Double?, percent: Double?, modifier: Modifier = Modifier) {
    if (amount == null || percent == null) {
        Text("Profit/Loss unavailable", color = PortfolioMuted, modifier = modifier.padding(vertical = 4.dp))
        return
    }
    val sign = if (amount >= 0) "+" else "−"
    Text(
        "$sign${money(abs(amount))} ($sign${String.format(Locale.US, "%.2f", abs(percent))}%)",
        color = if (amount >= 0) PortfolioGreen else PortfolioRed,
        fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

internal fun money(value: Double): String = NumberFormat.getCurrencyInstance(Locale.US).format(value)

internal fun quantity(value: Double): String = NumberFormat.getNumberInstance(Locale.US).apply {
    maximumFractionDigits = 4
}.format(value)
