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

// ══════════════════════════════════════════════════
//  TRADES SCREEN
// ══════════════════════════════════════════════════
@Composable
fun TradesScreen(vm: MainViewModel) {
    val trades by vm.tradeHistory.collectAsState()
    val dailyEarnings by vm.dailyEarnings.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BgDeep),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("Trades", style = MaterialTheme.typography.displayMedium.copy(
                fontFamily = ParafinaFamily, fontWeight = FontWeight.Black
            ), color = GreenPrimary)
        }

        // Summary stats
        item {
            val total = trades.size
            val wins = trades.count { it.status == TradeStatus.CLOSED_WIN }
            val losses = trades.count { it.status == TradeStatus.CLOSED_LOSS }
            val open = trades.count { it.status == TradeStatus.OPEN }
            val totalPnl = trades.sumOf { it.pnl ?: 0.0 }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GsMetricCard("TOTAL P&L", "$${String.format("%.2f", totalPnl)}",
                    Icons.Default.TrendingUp, if (totalPnl >= 0) ProfitGreen else LossRed,
                    modifier = Modifier.weight(1f))
                GsMetricCard("WIN RATE",
                    if (wins + losses > 0) "${(wins * 100 / (wins + losses))}%" else "—",
                    Icons.Default.EmojiEvents,
                    modifier = Modifier.weight(1f))
            }
        }

        item { GsLabel("RECENT TRADES (${trades.size})") }

        if (trades.isEmpty()) {
            item {
                GsCard {
                    Text("No trades yet. The agent will begin trading once the engine runs.",
                        style = MaterialTheme.typography.bodySmall, color = TextMuted,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        items(trades.reversed()) { trade ->
            TradeCard(trade)
        }
    }
}

@Composable
fun TradeCard(trade: TradeRecord) {
    val time = SimpleDateFormat("MMM dd HH:mm", Locale.US).format(Date(trade.timestamp))
    val statusColor = when (trade.status) {
        TradeStatus.CLOSED_WIN  -> ProfitGreen
        TradeStatus.CLOSED_LOSS -> LossRed
        TradeStatus.OPEN        -> WarnAmber
        TradeStatus.CANCELLED   -> TextMuted
    }
    GsCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        GsChip(trade.strategy.name.replace("_", " "), GreenDim)
                        GsChip(trade.side.name, if (trade.side == TradeSide.YES) GreenPrimary else WarnAmber)
                    }
                    Text(trade.marketQuestion,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(trade.status.name.replace("_", " "),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor)
                    trade.pnl?.let {
                        Text(if (it >= 0) "+$${String.format("%.2f", it)}" else "-$${String.format("%.2f", -it)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (it >= 0) ProfitGreen else LossRed)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Size: \$${String.format("%.2f", trade.size)} @ ${(trade.entryPrice * 100).toInt()}¢",
                    style = MaterialTheme.typography.labelSmall, color = TextMuted)
                Text(time, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
        }
    }
}

// ══════════════════════════════════════════════════
//  LOG SCREEN
// ══════════════════════════════════════════════════
@Composable
fun LogScreen(vm: MainViewModel) {
    val logs by vm.cycleLogs.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BgDeep),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            Text("Agent Log", style = MaterialTheme.typography.displayMedium.copy(
                fontFamily = ParafinaFamily, fontWeight = FontWeight.Black
            ), color = GreenPrimary)
        }

        if (logs.isEmpty()) {
            item {
                GsCard {
                    Text("No logs yet. Agent has not run.",
                        style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
            }
        }

        items(logs.reversed()) { log ->
            val time = SimpleDateFormat("MM/dd HH:mm:ss", Locale.US).format(Date(log.timestamp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(time,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.width(88.dp))
                Text(
                    log.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        log.isError -> ErrorRed
                        log.message.startsWith("✅") -> ProfitGreen
                        log.message.startsWith("⚠️") -> WarnAmber
                        log.message.startsWith("💸") -> WhaleBlue
                        else -> TextSecondary
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            HorizontalDivider(color = BorderColor.copy(alpha = 0.3f), thickness = 0.5.dp)
        }
    }
}

// ══════════════════════════════════════════════════
//  SETTINGS SCREEN
// ══════════════════════════════════════════════════
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val walletState by vm.walletState.collectAsState()
    val setupState by vm.setupState.collectAsState()
    val configs by vm.strategyConfigs.collectAsState()
    var userWalletInput by remember { mutableStateOf(walletState.userWalletAddress) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(BgDeep),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.displayMedium.copy(
                fontFamily = ParafinaFamily, fontWeight = FontWeight.Black
            ), color = GreenPrimary)
        }

        // Wallet info
        item {
            GsCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GsLabel("TRADING WALLET")
                    GsBodyText("GarbageSys wallet (auto-generated, secured by Android Keystore):")
                    Text(walletState.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = GreenPrimary,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    GsDivider()
                    GsLabel("YOUR RECEIVING WALLET (50% daily earnings)")
                    OutlinedTextField(
                        value = userWalletInput,
                        onValueChange = { userWalletInput = it },
                        label = { Text("0x... Polygon Address", color = TextMuted) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GreenPrimary,
                            unfocusedBorderColor = BorderColor,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = GreenPrimary
                        ),
                        maxLines = 2
                    )
                    GsButton("Save Wallet", onClick = { vm.saveUserWallet(userWalletInput) })
                }
            }
        }

        // Strategy toggles
        item {
            GsCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GsLabel("STRATEGY CONFIG")
                    listOf(
                        "Weather Bayesian" to configs.weather.enabled,
                        "Whale Copy" to configs.whaleCopy.enabled,
                        "Crowd Contra" to configs.crowdContra.enabled,
                        "Latency Arb" to configs.latencyArb.enabled,
                    ).forEach { (name, enabled) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(name, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            Switch(
                                checked = enabled,
                                onCheckedChange = { on ->
                                    val updated = when (name) {
                                        "Weather Bayesian" -> configs.copy(weather = configs.weather.copy(enabled = on))
                                        "Whale Copy"       -> configs.copy(whaleCopy = configs.whaleCopy.copy(enabled = on))
                                        "Crowd Contra"     -> configs.copy(crowdContra = configs.crowdContra.copy(enabled = on))
                                        "Latency Arb"      -> configs.copy(latencyArb = configs.latencyArb.copy(enabled = on))
                                        else -> configs
                                    }
                                    vm.updateStrategyConfig(updated)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = GreenPrimary,
                                    checkedTrackColor = GreenGlow
                                )
                            )
                        }
                    }
                }
            }
        }

        // Model info
        item {
            GsCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GsLabel("AI MODEL")
                    val model = RECOMMENDED_MODELS.find { it.id == setupState.selectedModelId }
                    Text(model?.name ?: "Not selected",
                        style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text("RAM: ~${vm.availableRamGb}GB available",
                        style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        }

        // About
        item {
            GsCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    GsLabel("ABOUT")
                    Text("GarbageSys v1.0.0", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("Fully offline AI trading agent", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    Text("No cloud dependency after setup", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    Text("Strategies: Weather/NOAA, Whale copy, Crowd contra, Latency arb",
                        style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
            }
        }
    }
}
