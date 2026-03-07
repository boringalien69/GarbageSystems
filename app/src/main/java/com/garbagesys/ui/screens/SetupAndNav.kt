package com.garbagesys.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.navigation.compose.*
import com.garbagesys.data.models.*
import com.garbagesys.ui.components.*
import com.garbagesys.ui.theme.*
import com.garbagesys.ui.viewmodel.MainViewModel
import androidx.compose.ui.text.font.FontWeight

// ── Navigation ──
@Composable
fun MainNavigation(vm: MainViewModel) {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    Scaffold(
        containerColor = BgDeep,
        bottomBar = {
            GsBottomBar(currentRoute) { route ->
                navController.navigate(route) {
                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true; restoreState = true
                }
            }
        }
    ) { padding ->
        NavHost(navController, startDestination = "dashboard", modifier = Modifier.padding(padding)) {
            composable("dashboard") { DashboardScreen(vm) }
            composable("trades")   { TradesScreen(vm) }
            composable("log")      { LogScreen(vm) }
            composable("settings") { SettingsScreen(vm) }
        }
    }
}

@Composable
fun GsBottomBar(currentRoute: String?, onNavigate: (String) -> Unit) {
    NavigationBar(
        containerColor = BgCard,
        tonalElevation = 0.dp
    ) {
        listOf(
            Triple("dashboard", Icons.Default.Home,          "Home"),
            Triple("trades",    Icons.Default.ShowChart,     "Trades"),
            Triple("log",       Icons.Default.Terminal,      "Log"),
            Triple("settings",  Icons.Default.Settings,      "Settings"),
        ).forEach { (route, icon, label) ->
            NavigationBarItem(
                selected = currentRoute == route,
                onClick  = { onNavigate(route) },
                icon     = { Icon(icon, label, tint = if (currentRoute == route) GreenPrimary else TextMuted) },
                label    = {
                    Text(label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (currentRoute == route) GreenPrimary else TextMuted)
                },
                colors   = NavigationBarItemDefaults.colors(
                    indicatorColor = GreenGlow,
                    selectedIconColor = GreenPrimary,
                    unselectedIconColor = TextMuted
                )
            )
        }
    }
}

// ════════════════════════════════════════════
//  SETUP FLOW
// ════════════════════════════════════════════
@Composable
fun SetupFlow(vm: MainViewModel) {
    var step by remember { mutableStateOf(0) }
    var selectedModelId by remember { mutableStateOf(vm.recommendedModel.id) }
    var userWallet by remember { mutableStateOf("") }
    val downloadProgress by vm.downloadProgress.collectAsState()
    val downloadMessage by vm.downloadMessage.collectAsState()
    val setupState by vm.setupState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDeep),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Logo + title
            GsLogo(size = 72.dp)
            Text("GarbageSys",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontFamily = ParafinaFamily, fontWeight = FontWeight.Black
                ),
                color = GreenPrimary,
                textAlign = TextAlign.Center)
            Text("Autonomous AI Trading Agent",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center)

            GsDivider()

            when (step) {
                0 -> SetupStepWelcome { step = 1 }
                1 -> SetupStepModel(
                    availableRamGb = vm.availableRamGb,
                    recommended = vm.recommendedModel,
                    selectedId = selectedModelId,
                    onSelect = { selectedModelId = it }
                ) { step = 2; vm.downloadModel(selectedModelId) }
                2 -> SetupStepDownload(
                    progress = downloadProgress,
                    message = downloadMessage,
                    modelDownloaded = setupState.modelDownloaded
                ) { step = 3 }
                3 -> SetupStepWallet(
                    walletText = userWallet,
                    onWalletChange = { userWallet = it }
                ) {
                    vm.completeSetup(selectedModelId, userWallet)
                }
            }
        }
    }
}

@Composable
fun SetupStepWelcome(onNext: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GsCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GsLabel("WHAT THIS APP DOES")
                GsBodyText("GarbageSys runs a fully autonomous trading strategy on Polymarket prediction markets using a local AI model — no internet needed for the AI itself.")
                GsDivider()
                listOf(
                    "🪣" to "Bootstrap from faucets (auto)",
                    "🌤" to "Weather markets via NOAA data",
                    "🐋" to "Copy top whale wallets automatically",
                    "📉" to "Fade overconfident crowds (80%+)",
                    "⚡" to "CEX vs Polymarket latency arb",
                    "💸" to "Sends 50% of daily earnings to you"
                ).forEach { (emoji, text) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(emoji, fontSize = 16.sp)
                        Text(text, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    }
                }
            }
        }
        GsButton("Get Started →", onClick = onNext)
    }
}

@Composable
fun SetupStepModel(
    availableRamGb: Int,
    recommended: LlmModelInfo,
    selectedId: String,
    onSelect: (String) -> Unit,
    onNext: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GsLabel("SELECT AI MODEL")
        Text("Detected RAM: ~${availableRamGb}GB usable",
            style = MaterialTheme.typography.bodySmall, color = GreenPrimary)
        Text("Recommended: ${recommended.name}",
            style = MaterialTheme.typography.bodySmall, color = TextSecondary)

        RECOMMENDED_MODELS.forEach { model ->
            val isRecommended = model.id == recommended.id
            val isSelected = model.id == selectedId
            val isAvailable = availableRamGb >= model.minRamGb

            GsCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = isAvailable) { onSelect(model.id) }
                    .then(if (isSelected) Modifier.border(1.dp, GreenPrimary, RoundedCornerShape(12.dp)) else Modifier),
                alpha = if (isAvailable) 1f else 0.4f
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(model.name, style = MaterialTheme.typography.bodyLarge, color = if (isAvailable) TextPrimary else TextMuted)
                            if (isRecommended) GsChip("BEST FIT", GreenPrimary)
                        }
                        Text(model.description, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        Text("${model.sizeGb}GB download • ${model.minRamGb}GB+ RAM",
                            style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    }
                    if (isSelected) Icon(Icons.Default.CheckCircle, null, tint = GreenPrimary, modifier = Modifier.size(20.dp))
                }
            }
        }
        GsButton("Download & Continue →", onClick = onNext)
    }
}

@Composable
fun SetupStepDownload(
    progress: Double,
    message: String,
    modelDownloaded: Boolean,
    onNext: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GsLabel("DOWNLOADING MODEL")
        GsCard {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (modelDownloaded) {
                    Icon(Icons.Default.CheckCircle, null, tint = GreenPrimary, modifier = Modifier.size(48.dp))
                    Text("Model ready!", style = MaterialTheme.typography.bodyLarge, color = GreenPrimary)
                } else {
                    CircularProgressIndicator(color = GreenPrimary, progress = { progress.toFloat() })
                    Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.displayMedium, color = GreenPrimary)
                    Text(message, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        }
        if (modelDownloaded) GsButton("Continue →", onClick = onNext)
    }
}

@Composable
fun SetupStepWallet(
    walletText: String,
    onWalletChange: (String) -> Unit,
    onComplete: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GsLabel("YOUR RECEIVING WALLET")
        GsCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GsBodyText("Enter your Polygon wallet address. GarbageSys will send 50% of daily earnings here automatically.")
                GsBodyText("⚠️ This is separate from GarbageSys's trading wallet. Use any Polygon-compatible wallet (MetaMask, Trust Wallet, etc.)")
                OutlinedTextField(
                    value = walletText,
                    onValueChange = onWalletChange,
                    label = { Text("0x... Polygon Address", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GreenPrimary,
                        unfocusedBorderColor = BorderColor,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = GreenPrimary
                    ),
                    singleLine = false,
                    maxLines = 3
                )
            }
        }
        GsButton(
            text = if (walletText.isNotEmpty()) "Complete Setup →" else "Skip (set later) →",
            onClick = onComplete
        )
    }
}
