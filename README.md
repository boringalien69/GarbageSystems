# GarbageSys 🤖

> Autonomous AI trading agent for Android. Earns its own starting funds. Runs entirely offline after setup.

![Android](https://img.shields.io/badge/Android-9%2B-green) ![License](https://img.shields.io/badge/License-MIT-blue) ![Build](https://img.shields.io/badge/Build-GitHub_Actions-brightgreen)

---

## What is GarbageSys?

GarbageSys is a fully autonomous AI trading agent that runs on your Android phone. After a one-time setup, it requires zero interaction — the AI finds opportunities, places trades, and sends 50% of daily earnings to your wallet automatically.

It uses a local LLM (no internet needed for AI reasoning) and trades on Polymarket prediction markets via free public APIs.

---

## Features

| Feature | Description |
|---|---|
| 🧠 Local AI | GGUF model runs fully on-device via llama.cpp. No API keys, no cloud. |
| 🌤 Weather Edge | Reads NOAA forecasts (free, no key) to find mispriced weather markets |
| 🐋 Whale Copy | Auto-discovers top 5 Polymarket wallets by P&L, copies their positions |
| 📉 Crowd Contra | Fades markets where crowd is ≥80% confident (historically overpriced) |
| ⚡ Latency Arb | Compares CoinGecko prices to Polymarket implied probs for lag |
| 🪣 Faucet Bootstrap | Claims free MATIC from public faucets to cover gas fees from zero |
| 💸 Auto Send | 50% of daily earnings transferred to your wallet every 24h |
| 🔒 Secure Wallet | Private key encrypted with Android Keystore (AES-256-GCM, hardware-backed) |
| 📱 Android 9+ | Works on any arm64 Android phone from API 28 (Android 9) upward |
| 🔄 Self-healing | If any API goes down, strategies skip and retry. No crashes, no manual fixes. |

---

## RAM-tiered Model Selection

The app detects your available RAM and recommends the best model automatically:

| RAM | Model | Size |
|---|---|---|
| 2–3 GB | Qwen 2.5 0.5B (Q4) | 0.4 GB |
| 3–4 GB | Llama 3.2 1B (Q4) | 0.7 GB |
| 4–6 GB | Llama 3.2 3B (Q4) | 1.9 GB |
| 8 GB+ | Llama 3.1 8B (Q4) | 4.7 GB |
| 8 GB+ | DeepSeek R1 7B (Q4) | 4.4 GB — best reasoning |
| 12 GB+ | Mistral 7B (Q5) | 4.1 GB |

---

## Install

### Option A: Download APK (recommended)
1. Go to **Releases** (right sidebar on GitHub)
2. Download the latest `GarbageSys-debug-*.apk`
3. On your phone: **Settings → Security → Install Unknown Apps** → allow your browser
4. Open the APK to install

### Option B: Build from source
1. Fork this repo on GitHub
2. Push any change to `main`
3. GitHub Actions builds the APK automatically
4. Download from **Actions → latest run → Artifacts**

---

## First-Time Setup (5 minutes)

1. **Open app** → Setup wizard starts automatically
2. **Select AI model** → app recommends best for your RAM
3. **Download model** → ~0.4–4.7 GB one-time download (HuggingFace)
4. **Enter your receiving wallet** → Polygon address (MetaMask/Trust Wallet/any EVM wallet)
5. **Done** → Agent runs every 15 minutes in the background

The app creates its own trading wallet automatically (secured by Android Keystore). You never need to manage it.

---

## Architecture

```
┌─────────────────────────────────────────┐
│              GarbageSys App             │
│                                         │
│  ┌─────────────┐  ┌──────────────────┐ │
│  │  LLM Brain  │  │   Dashboard UI   │ │
│  │ llama.cpp   │  │  Jetpack Compose │ │
│  │ GGUF model  │  │  Inter font      │ │
│  │ (RAM-aware) │  │  #31F2B3 theme   │ │
│  └──────┬──────┘  └──────────────────┘ │
│         │                              │
│  ┌──────▼──────────────────────────┐   │
│  │         Strategy Engine         │   │
│  │  • Weather Bayesian (NOAA API)  │   │
│  │  • Whale Copy (Polygon events)  │   │
│  │  • Crowd Contra (80%+ fade)     │   │
│  │  • Latency Arb (CoinGecko)      │   │
│  │  • Faucet Bootstrap             │   │
│  └──────┬──────────────────────────┘   │
│         │                              │
│  ┌──────▼──────────────────────────┐   │
│  │    Web3j Wallet (Polygon)       │   │
│  │  USDC.e + MATIC                 │   │
│  │  Keys in Android Keystore       │   │
│  │  50% auto-send daily            │   │
│  └─────────────────────────────────┘   │
└─────────────────────────────────────────┘
```

---

## Strategy Details

### Weather Bayesian
- Pulls NOAA gridpoint forecasts (free, no key, 93–96% accuracy 24–48h)
- Converts forecast to probability distribution across Polymarket bucket markets
- Uses Bayesian updating + Kelly criterion for sizing
- Targets: NYC, Chicago, Miami, LA, Houston, Phoenix, Denver, Seattle, Boston, Atlanta

### Whale Copy
- Auto-discovers top 5 Polymarket wallets by P&L via leaderboard API
- Refreshes whale list every 30 minutes (catches new alpha wallets)
- Copies positions proportionally sized by Kelly criterion
- Only copies if whale got a better entry price (confirms their edge)

### Crowd Contra
- Monitors all active markets for ≥80% crowd confidence
- Historical data: 80%-priced markets resolve correctly ~72% of the time
- Fades with the opposite side when gap is ≥15% edge
- Conservative position sizing (quarter-Kelly)

### Latency Arb
- Polls CoinGecko (free) for BTC/ETH/SOL prices every 30s
- Compares to Polymarket implied probability on short crypto markets
- Enters when real price has moved >1% but Polymarket hasn't repriced
- Exits quickly when market catches up

---

## Security

- **Private key**: Generated locally, encrypted with AES-256-GCM via Android Keystore (hardware-backed on most phones)
- **No key exposure**: Key never leaves the device, never stored in plaintext
- **Isolated wallet**: Trading wallet is separate from your receiving wallet
- **No third-party skills**: No ClawHub, no random Telegram bots, no shared key services
- **Backup excluded**: Trading wallet excluded from cloud backup intentionally

---

## Free APIs Used (No Keys Required)

| API | Used For |
|---|---|
| `polygon-rpc.com` | Polygon blockchain RPC |
| `api.weather.gov` (NOAA) | Weather forecasts |
| `api.coingecko.com` | Crypto price feeds |
| `gamma-api.polymarket.com` | Market data, leaderboard |
| `clob.polymarket.com` | Order book data |
| `huggingface.co` | Model download (one-time) |

---

## Realistic Expectations

- This is a **trading application** — starting funds can be lost
- Weather + crowd markets: supplemental income possible if tuned well
- Not a "set and forget millionaire machine" — edges are small and compound slowly
- Faucet bootstrap is very slow (~days to accumulate gas fees)
- Manual seed ($10–50 USDC) dramatically speeds up time-to-first-trade

---

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **LLM**: llama.cpp via llmedge JNI bindings (GGUF)
- **Blockchain**: web3j (Polygon/EVM)
- **Background**: WorkManager (runs every 15 min, survives reboots)
- **Storage**: DataStore + Android Keystore
- **Networking**: OkHttp3
- **Build**: Gradle + GitHub Actions

---

## License

MIT — use freely, no restrictions.
