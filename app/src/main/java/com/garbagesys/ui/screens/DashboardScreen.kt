package com.garbagesys.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.garbagesys.data.models.*
import com.garbagesys.ui.components.*
import com.garbagesys.ui.theme.*
import com.garbagesys.ui.viewmodel.MainViewModel
import androidx.compose.ui.text.font.FontWeight
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DashboardScreen(vm: MainViewModel) {
    val walletState by vm.walletState.collectAsState()
    val cycleLogs by vm.cycleLogs.collectAsState()
    val tradeHistory by vm.tradeHistory.collectAsState()
    val dailyEarnings by vm.dailyEarnings.collectAsState()
    val engineRunning by vm.engineRunning.collectAsState()
    val whales by vm.whaleWallets.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("GarbageSys",
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontFamily = ParafinaFamily, fontWeight = FontWeight.Black
                    ), color = GreenPrimary)
                    Text("Autonomous Agent", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
                GsStatusBadge(
                    running = engineRunning,
                    label = if (engineRunning) "ACTIVE" else "IDLE"
                )
            }
        }

        // Wallet cards
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GsMetricCard(
                    label = "USDC BALANCE",
                    value = "$${String.format("%.2f", walletState.usdcBalance)}",
                    icon = Icons.Default.AccountBalanceWallet,
                    modifier = Modifier.weight(1f)
                )
                GsMetricCard(
                    label = "MATIC",
                    value = String.format("%.4f", walletState.maticBalance),
                    icon = Icons.Default.Bolt,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GsMetricCard(
                    label = "TOTAL EARNED",
                    value = "$${String.format("%.2f", walletState.totalEarned)}",
                    icon = Icons.Default.TrendingUp,
                    valueColor = ProfitGreen,
                    modifier = Modifier.weight(1f)
                )
                GsMetricCard(
                    label = "SENT TO YOU",
                    value = "$${String.format("%.2f", walletState.totalSentToUser)}",
                    icon = Icons.Default.Send,
                    valueColor = WhaleBlue,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Quick actions
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GsOutlineButton(
                    text = "Run Now",
                    icon = Icons.Default.PlayArrow,
                    modifier = Modifier.weight(1f),
                    onClick = { vm.runCycleNow() }
                )
                GsOutlineButton(
                    text = "Refresh",
                    icon = Icons.Default.Refresh,
                    modifier = Modifier.weight(1f),
                    onClick = { vm.refreshWallet() }
                )
            }
        }

        // Daily earnings mini chart
        if (dailyEarnings.isNotEmpty()) {
            item {
                GsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GsLabel("30-DAY EARNINGS")
                        EarningsChart(earnings = dailyEarnings.takeLast(30))
                    }
                }
            }
        }

        // Strategy status
        item {
            GsCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GsLabel("STRATEGY STATUS")
                    listOf(
                        Triple("🌤 Weather Bayesian", StrategyType.WEATHER_BAYESIAN, true),
                        Triple("🐋 Whale Copy", StrategyType.WHALE_COPY, true),
                        Triple("📉 Crowd Contra", StrategyType.CROWD_CONTRA, true),
                        Triple("⚡ Latency Arb", StrategyType.LATENCY_ARB, true),
                        Triple("🪣 Faucet Bootstrap", StrategyType.FAUCET_BOOTSTRAP,
                            walletState.usdcBalance < 5.0),
                    ).forEach { (name, type, active) ->
                        val recentTrades = tradeHistory.filter { it.strategy == type }.takeLast(10)
                        val winRate = if (recentTrades.isEmpty()) 0.0 else
                            recentTrades.count { it.status == TradeStatus.CLOSED_WIN }.toDouble() / recentTrades.size
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(name, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (recentTrades.isNotEmpty()) {
                                    Text("${(winRate * 100).toInt()}% W",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (winRate > 0.5) ProfitGreen else LossRed)
                                }
                                GsChip(
                                    text = if (active) "ON" else "OFF",
                                    color = if (active) GreenPrimary else TextMuted
                                )
                            }
                        }
                    }
                }
            }
        }

        // Recent log entries
        item {
            GsCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GsLabel("RECENT ACTIVITY")
                    val recentLogs = cycleLogs.takeLast(8).reversed()
                    if (recentLogs.isEmpty()) {
                        Text("No activity yet. Run first cycle to begin.",
                            style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    } else {
                        recentLogs.forEach { log ->
                            LogEntry(log)
                        }
                    }
                }
            }
        }

        // Whale wallets
        if (whales.isNotEmpty()) {
            item {
                GsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GsLabel("TRACKED WHALES (TOP 5)")
                        whales.take(5).forEachIndexed { i, whale ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("#${i+1}", style = MaterialTheme.typography.labelSmall, color = GreenPrimary)
                                    Text(whale.address.take(6) + "..." + whale.address.takeLast(4),
                                        style = MaterialTheme.typography.bodySmall, color = TextPrimary,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                }
                                Text("$${String.format("%.0f", whale.totalPnlUsdc)} P&L",
                                    style = MaterialTheme.typography.labelSmall, color = ProfitGreen)
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
fun EarningsChart(earnings: List<DailyEarnings>) {
    // Simple bar chart using Canvas
    val maxPnl = earnings.maxOfOrNull { it.grossPnl }?.coerceAtLeast(0.01) ?: 0.01
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        earnings.forEach { day ->
            val fraction = (day.grossPnl / maxPnl).coerceIn(0.0, 1.0).toFloat()
            val color = if (day.grossPnl >= 0) GreenPrimary else LossRed
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(fraction.coerceAtLeast(0.05f))
                    .background(color.copy(alpha = 0.7f), shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(earnings.firstOrNull()?.date?.takeLast(5) ?: "",
            style = MaterialTheme.typography.labelSmall, color = TextMuted)
        Text(earnings.lastOrNull()?.date?.takeLast(5) ?: "today",
            style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

@Composable
fun LogEntry(log: AgentCycleLog) {
    val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(log.timestamp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(time, style = MaterialTheme.typography.labelSmall, color = TextMuted,
            modifier = Modifier.width(36.dp))
        Text(
            log.message,
            style = MaterialTheme.typography.bodySmall,
            color = when {
                log.isError -> ErrorRed
                log.message.contains("✅") -> ProfitGreen
                log.message.contains("⚠️") -> WarnAmber
                else -> TextSecondary
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
