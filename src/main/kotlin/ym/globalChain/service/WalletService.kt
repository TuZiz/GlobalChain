package ym.globalchain.service

import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import ym.globalchain.config.PluginConfig
import ym.globalchain.util.MoneyFormats
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class WalletService(
    private val config: PluginConfig,
    private val economy: Economy,
) : AutoCloseable {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GlobalChain-Vault").apply { isDaemon = true }
    }

    fun balance(playerId: UUID, playerName: String): CompletableFuture<Long> {
        return CompletableFuture.supplyAsync({
            val offlinePlayer = Bukkit.getOfflinePlayer(playerId)
            toMinor(economy.getBalance(offlinePlayer))
        }, executor)
    }

    fun grant(playerId: UUID, playerName: String, amountRaw: String): CompletableFuture<ServiceFeedback> {
        val delta = MoneyFormats.parseMinorUnits(amountRaw, config.currency.scale)
            ?: return CompletableFuture.completedFuture(ServiceFeedback("messages.invalid-number"))
        return deposit(playerId, playerName, delta).thenApply { result ->
            when (result) {
                is WalletOperationResult.Success -> ServiceFeedback(
                    key = "messages.grant-success",
                    placeholders = mapOf(
                        "target" to playerName,
                        "amount" to MoneyFormats.formatMinorUnits(delta, config.currency.scale),
                    ),
                )
                is WalletOperationResult.Failure -> ServiceFeedback("messages.economy-operation-failed")
                WalletOperationResult.InsufficientFunds -> ServiceFeedback("messages.economy-operation-failed")
            }
        }
    }

    fun withdraw(playerId: UUID, playerName: String, amountMinor: Long): CompletableFuture<WalletOperationResult> {
        return CompletableFuture.supplyAsync({
            val offlinePlayer = Bukkit.getOfflinePlayer(playerId)
            val major = toMajor(amountMinor)
            if (!economy.has(offlinePlayer, major)) {
                return@supplyAsync WalletOperationResult.InsufficientFunds
            }
            val response = economy.withdrawPlayer(offlinePlayer, major)
            if (!response.transactionSuccess()) {
                return@supplyAsync WalletOperationResult.Failure(response.errorMessage)
            }
            WalletOperationResult.Success(toMinor(response.balance))
        }, executor)
    }

    fun deposit(playerId: UUID, playerName: String, amountMinor: Long): CompletableFuture<WalletOperationResult> {
        return CompletableFuture.supplyAsync({
            val offlinePlayer = Bukkit.getOfflinePlayer(playerId)
            val response = economy.depositPlayer(offlinePlayer, toMajor(amountMinor))
            if (!response.transactionSuccess()) {
                return@supplyAsync WalletOperationResult.Failure(response.errorMessage)
            }
            WalletOperationResult.Success(toMinor(response.balance))
        }, executor)
    }

    private fun toMajor(amountMinor: Long): Double {
        return BigDecimal.valueOf(amountMinor, config.currency.scale).toDouble()
    }

    private fun toMinor(amountMajor: Double): Long {
        return BigDecimal.valueOf(amountMajor)
            .setScale(config.currency.scale, RoundingMode.HALF_UP)
            .movePointRight(config.currency.scale)
            .toLong()
    }

    override fun close() {
        executor.shutdownNow()
    }
}

sealed interface WalletOperationResult {
    data class Success(val balanceMinor: Long) : WalletOperationResult
    data class Failure(val message: String?) : WalletOperationResult
    data object InsufficientFunds : WalletOperationResult
}
