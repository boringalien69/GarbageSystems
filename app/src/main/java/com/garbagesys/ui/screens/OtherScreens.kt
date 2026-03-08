package com.garbagesys.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.garbagesys.data.models.*
import com.garbagesys.ui.components.*
import com.garbagesys.ui.theme.*
import com.garbagesys.ui.viewmodel.MainViewModel
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
        item {
            val wins = trades.count { it.status == TradeStatus.CLOSED_WIN }
            val losses = trades.count { it.status == TradeStatus.CLOSED_LOSS }
            val totalPnl = trades.sumOf { it.pnl ?: 0.0 }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GsMetricCard("TOTAL P&L", "$${String.format("%.2f", totalPnl)}",
                    Icons.Default.TrendingUp, if (totalPnl >= 0) ProfitGreen else LossRed,
                    modifier = Modifier.weight(1f))
                GsMetricCard("WIN RATE",
                    if (wins + losses > 0) "${(wins * 100 / (wins + losses))}%" else "—",
                    Icons.Default.EmojiEvents, modifier = Modifier.weight(1f))
            }
        }
        item { GsLabel("RECENT TRADES (${trades.size})") }
        if (trades.isEmpty()) {
            item { GsCard { Text("No trades yet. Agent is scanning markets...", style = MaterialTheme.typography.bodySmall, color = TextMuted) } }
        }
        items(trades.reversed()) { trade ->
            GsCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(trade.strategy.name.replace("_", " "), style = MaterialTheme.typography.labelSmall, color = GreenPrimary)
                        val sc = when (trade.status) {
                            TradeStatus.CLOSED_WIN -> ProfitGreen
                            TradeStatus.CLOSED_LOSS -> LossRed
                            TradeStatus.OPEN -> WarnAmber
                            else -> TextMuted
                        }
                        Text(trade.status.name.replace("_", " "), style = MaterialTheme.typography.labelSmall, color = sc)
                    }
                    Text(trade.marketQuestion, style = MaterialTheme.typography.bodySmall, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${trade.side} $${String.format("%.2f", trade.size)}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        trade.pnl?.let { Text("P&L: ${if (it >= 0) "+" else ""}$${String.format("%.3f", it)}", style = MaterialTheme.typography.bodySmall, color = if (it >= 0) ProfitGreen else LossRed) }
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════
//  LOG SCREEN — with Opportunities tab
// ══════════════════════════════════════════════════
@Composable
fun LogScreen(vm: MainViewModel) {
    val logs by vm.cycleLogs.collectAsState()
    val airdrops by vm.airdropOpportunities.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize().background(BgDeep)) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = BgCard,
            contentColor = GreenPrimary,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = GreenPrimary
                )
            }
        ) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                selectedContentColor = GreenPrimary, unselectedContentColor = TextMuted,
                text = { Text("Agent Log", style = MaterialTheme.typography.labelMedium) })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                selectedContentColor = GreenPrimary, unselectedContentColor = TextMuted,
                text = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Opportunities", style = MaterialTheme.typography.labelMedium)
                        val pending = airdrops.count { it.status == AirdropStatus.PENDING || it.status == AirdropStatus.REQUIRES_ACTION }
                        if (pending > 0) {
                            Surface(color = GreenPrimary, shape = MaterialTheme.shapes.small) {
                                Text("$pending", style = MaterialTheme.typography.labelSmall, color = BgDeep,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                            }
                        }
                    }
                })
        }
        when (selectedTab) {
            0 -> AgentLogTab(logs)
            1 -> OpportunitiesTab(airdrops, vm)
        }
    }
}

@Composable
private fun AgentLogTab(logs: List<AgentCycleLog>) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (logs.isEmpty()) {
            item { GsCard { Text("No logs yet. Agent has not run.", style = MaterialTheme.typography.bodySmall, color = TextMuted) } }
        }
        items(logs.reversed()) { log ->
            val time = SimpleDateFormat("MM/dd HH:mm:ss", Locale.US).format(Date(log.timestamp))
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Text(time, style = MaterialTheme.typography.labelSmall, color = TextMuted,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.width(88.dp))
                Text(log.message, style = MaterialTheme.typography.bodySmall,
                    color = when {
                        log.isError -> ErrorRed
                        log.message.startsWith("✅") -> ProfitGreen
                        log.message.startsWith("⚠️") -> WarnAmber
                        log.message.startsWith("💸") -> WhaleBlue
                        else -> TextSecondary
                    }, modifier = Modifier.weight(1f))
            }
            HorizontalDivider(color = BorderColor.copy(alpha = 0.3f), thickness = 0.5.dp)
        }
    }
}

@Composable
private fun OpportunitiesTab(airdrops: List<AirdropOpportunity>, vm: MainViewModel) {
    val uriHandler = LocalUriHandler.current
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Opportunities", style = MaterialTheme.typography.titleLarge.copy(fontFamily = ParafinaFamily, fontWeight = FontWeight.Black), color = GreenPrimary)
                    Text("AI-discovered airdrops • auto-submits wallet when possible", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
                GsButton("Scan Now", onClick = { vm.scanAirdrops() })
            }
        }
        if (airdrops.isNotEmpty()) {
            item {
                val submitted = airdrops.count { it.status == AirdropStatus.SUBMITTED }
                val needsAction = airdrops.count { it.status == AirdropStatus.REQUIRES_ACTION }
                val pending = airdrops.count { it.status == AirdropStatus.PENDING }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AirdropChip("✅ $submitted", ProfitGreen, Modifier.weight(1f))
                    AirdropChip("👆 $needsAction", WarnAmber, Modifier.weight(1f))
                    AirdropChip("🔍 $pending", TextSecondary, Modifier.weight(1f))
                }
            }
        }
        if (airdrops.isEmpty()) {
            item {
                GsCard {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("🔍", style = MaterialTheme.typography.displaySmall)
                        Text("No opportunities yet", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        Text("Tap 'Scan Now' or wait for next agent cycle", style = MaterialTheme.typography.bodySmall, color = TextMuted, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        items(airdrops) { airdrop ->
            GsCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(airdrop.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("POLYGON", style = MaterialTheme.typography.labelSmall, color = GreenPrimary)
                        }
                        val (badge, badgeColor) = when (airdrop.status) {
                            AirdropStatus.SUBMITTED       -> "✅ SUBMITTED" to ProfitGreen
                            AirdropStatus.REQUIRES_ACTION -> "👆 MANUAL" to WarnAmber
                            AirdropStatus.PENDING         -> "🔍 PENDING" to TextSecondary
                            AirdropStatus.FAILED          -> "❌ FAILED" to LossRed
                            AirdropStatus.EXPIRED         -> "💀 EXPIRED" to TextMuted
                        }
                        Surface(color = badgeColor.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
                            Text(badge, style = MaterialTheme.typography.labelSmall, color = badgeColor, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                        }
                    }
                    if (airdrop.description.isNotEmpty()) {
                        Text(airdrop.description, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (airdrop.requiresAction != null && airdrop.status == AirdropStatus.REQUIRES_ACTION) {
                        Surface(color = WarnAmber.copy(alpha = 0.1f), shape = MaterialTheme.shapes.small) {
                            Text("⚡ ${airdrop.requiresAction}", style = MaterialTheme.typography.bodySmall, color = WarnAmber, modifier = Modifier.padding(8.dp))
                        }
                    }
                    airdrop.submittedAt?.let {
                        Text("Submitted ${SimpleDateFormat("MMM dd, HH:mm", Locale.US).format(Date(it))}", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { try { uriHandler.openUri(airdrop.url) } catch (e: Exception) {} }) {
                            Icon(Icons.Default.OpenInNew, null, tint = GreenPrimary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Open", style = MaterialTheme.typography.labelMedium, color = GreenPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AirdropChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color.copy(alpha = 0.1f), shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth())
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
    var botTokenInput by remember { mutableStateOf(vm.getTelegramBotToken()) }
    var botTokenVisible by remember { mutableStateOf(false) }
    var botTokenSaved by remember { mutableStateOf(vm.hasTelegramBotToken()) }

    LazyColumn(modifier = Modifier.fillMaxSize().background(BgDeep), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.displayMedium.copy(fontFamily = ParafinaFamily, fontWeight = FontWeight.Black), color = GreenPrimary) }

        // Wallet
        item {
            GsCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GsLabel("TRADING WALLET")
                    GsBodyText("GarbageSys wallet (auto-generated, secured by Android Keystore):")
                    Text(walletState.address, style = MaterialTheme.typography.bodySmall, color = GreenPrimary, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    GsDivider()
                    GsLabel("YOUR RECEIVING WALLET (50% daily earnings)")
                    OutlinedTextField(value = userWalletInput, onValueChange = { userWalletInput = it },
                        label = { Text("0x... Polygon Address", color = TextMuted) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GreenPrimary, unfocusedBorderColor = BorderColor, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = GreenPrimary),
                        maxLines = 2)
                    GsButton("Save Wallet", onClick = { vm.saveUserWallet(userWalletInput) })
                }
            }
        }

        // Telegram Bootstrap
        item {
            GsCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("📱", style = MaterialTheme.typography.bodyLarge)
                        GsLabel("TELEGRAM FARMING BOOTSTRAP")
                    }
                    GsBodyText("Auto-farms 8 Telegram mini-app games every cycle. Earns real tokens to bootstrap trading wallet from zero.")
                    Surface(
                        color = if (botTokenSaved) ProfitGreen.copy(alpha = 0.1f) else WarnAmber.copy(alpha = 0.1f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            if (botTokenSaved) "✅ Bot token configured — farming active" else "⚠️ No bot token — add below to activate",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (botTokenSaved) ProfitGreen else WarnAmber,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    GsDivider()
                    Text("1. Open Telegram → search @BotFather\n2. Send /newbot → follow steps\n3. Copy the token (looks like 7123456:AAH...)\n4. Paste below and save",
                        style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    GsDivider()
                    OutlinedTextField(
                        value = botTokenInput,
                        onValueChange = { botTokenInput = it },
                        label = { Text("Bot Token (from @BotFather)", color = TextMuted) },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (botTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { botTokenVisible = !botTokenVisible }) {
                                Icon(if (botTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = TextMuted)
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GreenPrimary, unfocusedBorderColor = BorderColor, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = GreenPrimary),
                        maxLines = 2
                    )
                    GsButton("Save Bot Token", onClick = {
                        vm.saveTelegramBotToken(botTokenInput)
                        botTokenSaved = botTokenInput.isNotEmpty()
                    })
                    if (botTokenSaved) {
                        Text("Farming: TapSwap • Tomarket • HereWallet • Pixelverse • Boinker • Major • MemeFi • Catizen",
                            style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }
            }
        }

        // Strategy toggles
        item {
            GsCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GsLabel("STRATEGY CONFIG")
                    listOf("Weather Bayesian" to configs.weather.enabled, "Whale Copy" to configs.whaleCopy.enabled, "Crowd Contra" to configs.crowdContra.enabled, "Latency Arb" to configs.latencyArb.enabled).forEach { (name, enabled) ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(name, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            Switch(checked = enabled, onCheckedChange = { on ->
                                val updated = when (name) {
                                    "Weather Bayesian" -> configs.copy(weather = configs.weather.copy(enabled = on))
                                    "Whale Copy"       -> configs.copy(whaleCopy = configs.whaleCopy.copy(enabled = on))
                                    "Crowd Contra"     -> configs.copy(crowdContra = configs.crowdContra.copy(enabled = on))
                                    "Latency Arb"      -> configs.copy(latencyArb = configs.latencyArb.copy(enabled = on))
                                    else -> configs
                                }
                                vm.updateStrategyConfig(updated)
                            }, colors = SwitchDefaults.colors(checkedThumbColor = GreenPrimary, checkedTrackColor = GreenGlow))
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
                    Text(model?.name ?: "Not selected", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text("RAM: ~${vm.availableRamGb}GB available", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        }

        // About
        item {
            GsCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    GsLabel("ABOUT")
                    Text("GarbageSys v1.2.0", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text("Fully offline AI trading agent", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    Text("Bootstrap: Telegram farming + faucets + airdrop scanner", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    Text("Strategies: Weather/NOAA, Whale copy, Crowd contra, Latency arb", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
            }
        }
    }
}
