package ym.globalchain.service

import org.bukkit.entity.Player
import ym.globalchain.GlobalChain
import ym.globalchain.data.MarketRepository
import ym.globalchain.model.PayoutRecord
import ym.globalchain.model.TrackedFlow
import ym.globalchain.util.InventoryOps
import ym.globalchain.util.TrackedItemService
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class RecoveryService(
    private val plugin: GlobalChain,
    recoveryIntervalSeconds: Long,
    private val repository: MarketRepository,
    private val walletService: WalletService,
    private val trackedItemService: TrackedItemService,
) : AutoCloseable {

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "GlobalChain-Recovery").apply { isDaemon = true }
    }

    init {
        executor.scheduleAtFixedRate({
            runCatching {
                repository.expireListings(System.currentTimeMillis()).join()
                settlePendingPayouts().join()
            }
            plugin.server.onlinePlayers.forEach(::reconcilePlayer)
        }, recoveryIntervalSeconds, recoveryIntervalSeconds, TimeUnit.SECONDS)
    }

    fun reconcilePlayer(player: Player): CompletableFuture<Void> {
        return plugin.scheduler().supplyOnPlayer(player) {
            InventoryOps.trackedItems(player, trackedItemService)
        }.thenCompose { trackedItems ->
            repository.preparedSales(player.uniqueId).thenCombine(repository.preparedDeliveries(player.uniqueId)) { sales, deliveries ->
                Triple(trackedItems, sales.associateBy { it.intentId }, deliveries.associateBy { it.intentId })
            }
        }.thenCompose { (trackedItems, sales, deliveries) ->
            val futures = mutableListOf<CompletableFuture<*>>()
            val trackedIntentIds = trackedItems.map { it.marker.intentId }.toSet()

            trackedItems.forEach { tracked ->
                when (tracked.marker.flow) {
                    TrackedFlow.SALE -> {
                        val saleIntent = sales[tracked.marker.intentId]
                        val future = if (saleIntent != null) {
                            repository.abortSale(saleIntent.intentId).thenCompose {
                                plugin.scheduler().runOnPlayer(player) { trackedItemService.stripIntent(player, saleIntent.intentId) }
                            }
                        } else {
                            plugin.scheduler().runOnPlayer(player) { trackedItemService.stripIntent(player, tracked.marker.intentId) }
                        }
                        futures += future
                    }
                    TrackedFlow.DELIVERY -> {
                        val deliveryIntent = deliveries[tracked.marker.intentId]
                        val future = if (deliveryIntent != null) {
                            repository.commitDelivery(deliveryIntent.intentId).thenCompose {
                                plugin.scheduler().runOnPlayer(player) { trackedItemService.stripIntent(player, deliveryIntent.intentId) }
                            }
                        } else {
                            plugin.scheduler().runOnPlayer(player) { trackedItemService.stripIntent(player, tracked.marker.intentId) }
                        }
                        futures += future
                    }
                }
            }

            sales.values.filterNot { trackedIntentIds.contains(it.intentId) }.forEach { sale ->
                futures += repository.recoverSaleToMailbox(sale.intentId).thenRun {
                    plugin.sendMessage(player, "messages.sale-recovered")
                }
            }
            deliveries.values.filterNot { trackedIntentIds.contains(it.intentId) }.forEach { delivery ->
                futures += repository.abortDelivery(delivery.intentId)
            }

            CompletableFuture.allOf(*futures.toTypedArray())
        }
    }

    override fun close() {
        executor.shutdownNow()
    }

    private fun settlePendingPayouts(): CompletableFuture<Void> {
        return repository.pendingPayouts().thenCompose { payouts ->
            val futures = payouts.map(::settlePayout)
            CompletableFuture.allOf(*futures.toTypedArray())
        }
    }

    private fun settlePayout(payout: PayoutRecord): CompletableFuture<Void> {
        return walletService.deposit(payout.sellerId, payout.sellerName, payout.amountMinor).thenCompose { result ->
            when (result) {
                is WalletOperationResult.Success -> repository.completePayout(payout.referenceId).thenAccept { completed ->
                    if (!completed) {
                        plugin.logger.severe("Failed to mark payout ${payout.referenceId} as paid after Vault deposit.")
                    }
                }
                WalletOperationResult.InsufficientFunds -> {
                    plugin.logger.warning("Vault rejected pending payout ${payout.referenceId} for ${payout.sellerId}.")
                    CompletableFuture.completedFuture(null)
                }
                is WalletOperationResult.Failure -> {
                    plugin.logger.warning("Pending payout ${payout.referenceId} failed: ${result.message.orEmpty()}")
                    CompletableFuture.completedFuture(null)
                }
            }
        }
    }
}
