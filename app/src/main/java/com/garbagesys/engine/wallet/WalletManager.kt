package com.garbagesys.engine.wallet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.garbagesys.GarbageSysApp
import com.garbagesys.data.db.PreferencesRepository
import com.garbagesys.data.models.WalletState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import org.web3j.crypto.*
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.http.HttpService
import org.web3j.tx.gas.DefaultGasProvider
import org.web3j.utils.Convert
import java.math.BigDecimal
import java.math.BigInteger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * WalletManager handles:
 * - Wallet creation + secure key storage (Android Keystore AES-GCM)
 * - USDC balance queries
 * - USDC transfer (for 50% daily send + trade execution)
 * - Polygon RPC via free public endpoint (no API key)
 */
class WalletManager(private val context: Context) {

    private val prefs = PreferencesRepository(context)

    // Lazy Web3j — connects to free public Polygon RPC
    private val web3j: Web3j by lazy {
        Web3j.build(HttpService(GarbageSysApp.POLYGON_RPC))
    }

    // USDC ERC-20 minimal ABI for balanceOf + transfer
    private val usdcAbi = """[
        {"name":"balanceOf","type":"function","stateMutability":"view",
         "inputs":[{"name":"account","type":"address"}],
         "outputs":[{"name":"","type":"uint256"}]},
        {"name":"transfer","type":"function","stateMutability":"nonpayable",
         "inputs":[{"name":"to","type":"address"},{"name":"amount","type":"uint256"}],
         "outputs":[{"name":"","type":"bool"}]},
        {"name":"decimals","type":"function","stateMutability":"view",
         "inputs":[],"outputs":[{"name":"","type":"uint8"}]}
    ]"""

    // ── Keystore constants ──
    private val KEYSTORE_ALIAS = "garbagesys_wallet_key"
    private val ANDROID_KEYSTORE = "AndroidKeyStore"
    private val AES_GCM_NOPAD = "AES/GCM/NoPadding"
    private val GCM_IV_LENGTH = 12
    private val GCM_TAG_LENGTH = 128

    // ── Create or load wallet ──
    suspend fun getOrCreateWallet(): Credentials = withContext(Dispatchers.IO) {
        val stored = prefs.cycleLogsFlow  // we use datastore directly below
        val encKey = getEncryptedKey()
        if (encKey != null) {
            val privateKey = decryptKey(encKey)
            Credentials.create(privateKey)
        } else {
            createNewWallet()
        }
    }

    private suspend fun getEncryptedKey(): String? {
        return context.getSharedPreferences("gs_secure", Context.MODE_PRIVATE)
            .getString("enc_key", null)
    }

    private suspend fun createNewWallet(): Credentials = withContext(Dispatchers.IO) {
        val ecKeyPair = Keys.createEcKeyPair()
        val credentials = Credentials.create(ecKeyPair)
        val privateKeyHex = credentials.ecKeyPair.privateKey.toString(16).padStart(64, '0')
        val encrypted = encryptKey(privateKeyHex)
        context.getSharedPreferences("gs_secure", Context.MODE_PRIVATE)
            .edit().putString("enc_key", encrypted).apply()

        // Update wallet state
        val state = WalletState(address = credentials.address)
        prefs.saveWalletState(state)
        credentials
    }

    // ── AES-GCM encryption via Android Keystore ──
    private fun encryptKey(plaintext: String): String {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
            )
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            keyGenerator.generateKey()
        }
        val secretKey = keyStore.getKey(KEYSTORE_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance(AES_GCM_NOPAD)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decryptKey(encodedData: String): String {
        val combined = Base64.decode(encodedData, Base64.NO_WRAP)
        val iv = combined.sliceArray(0 until GCM_IV_LENGTH)
        val encrypted = combined.sliceArray(GCM_IV_LENGTH until combined.size)
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
        val secretKey = keyStore.getKey(KEYSTORE_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance(AES_GCM_NOPAD)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    // ── Balance queries ──
    suspend fun getUsdcBalance(address: String): Double = withContext(Dispatchers.IO) {
        try {
            val function = org.web3j.abi.FunctionEncoder.encode(
                org.web3j.abi.datatypes.Function(
                    "balanceOf",
                    listOf(org.web3j.abi.datatypes.Address(address)),
                    listOf(org.web3j.abi.TypeReference.create(
                        org.web3j.abi.datatypes.generated.Uint256::class.java))
                )
            )
            val response = web3j.ethCall(
                Transaction.createEthCallTransaction(address, GarbageSysApp.USDC_CONTRACT, function),
                DefaultBlockParameterName.LATEST
            ).send()
            if (response.hasError()) return@withContext 0.0
            val decoded = org.web3j.abi.FunctionReturnDecoder.decode(
                response.value,
                org.web3j.abi.Utils.convert(
                    listOf(org.web3j.abi.TypeReference.create(
                        org.web3j.abi.datatypes.generated.Uint256::class.java))
                )
            )
            if (decoded.isEmpty()) 0.0
            else (decoded[0].value as BigInteger).toBigDecimal()
                .divide(BigDecimal.TEN.pow(6)).toDouble() // USDC has 6 decimals
        } catch (e: Exception) {
            0.0
        }
    }

    suspend fun getMaticBalance(address: String): Double = withContext(Dispatchers.IO) {
        try {
            val balance = web3j.ethGetBalance(address, DefaultBlockParameterName.LATEST).send()
            Convert.fromWei(balance.balance.toBigDecimal(), Convert.Unit.ETHER).toDouble()
        } catch (e: Exception) {
            0.0
        }
    }

    /**
     * Transfer USDC to a recipient address.
     * Used for: 50% daily send + Polymarket trade execution.
     * Returns txHash or null on failure.
     */
    suspend fun transferUsdc(
        credentials: Credentials,
        toAddress: String,
        amountUsdc: Double
    ): String? = withContext(Dispatchers.IO) {
        try {
            val amountRaw = BigDecimal(amountUsdc)
                .multiply(BigDecimal.TEN.pow(6)).toBigInteger()

            val function = org.web3j.abi.datatypes.Function(
                "transfer",
                listOf(
                    org.web3j.abi.datatypes.Address(toAddress),
                    org.web3j.abi.datatypes.generated.Uint256(amountRaw)
                ),
                listOf(org.web3j.abi.TypeReference.create(
                    org.web3j.abi.datatypes.Bool::class.java))
            )
            val encodedFunction = org.web3j.abi.FunctionEncoder.encode(function)
            val nonce = web3j.ethGetTransactionCount(
                credentials.address, DefaultBlockParameterName.LATEST
            ).send().transactionCount

            // Get current gas price dynamically
            val gasPrice = web3j.ethGasPrice().send().gasPrice
            val gasLimit = BigInteger.valueOf(100_000L) // ERC-20 transfer

            val rawTx = RawTransaction.createTransaction(
                nonce, gasPrice, gasLimit,
                GarbageSysApp.USDC_CONTRACT, BigInteger.ZERO, encodedFunction
            )
            val signedTx = TransactionEncoder.signMessage(rawTx, 137L, credentials) // 137 = Polygon chainId
            val hexTx = "0x" + signedTx.joinToString("") { "%02x".format(it) }
            val receipt = web3j.ethSendRawTransaction(hexTx).send()
            receipt.transactionHash
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Refresh and persist the wallet state.
     */
    suspend fun refreshWalletState(credentials: Credentials) {
        val usdc = getUsdcBalance(credentials.address)
        val matic = getMaticBalance(credentials.address)
        val current = prefs.walletStateFlow.firstOrNull() ?: WalletState()
        prefs.saveWalletState(
            current.copy(
                address = credentials.address,
                usdcBalance = usdc,
                maticBalance = matic,
                lastUpdated = System.currentTimeMillis()
            )
        )
    }
}
