package ym.globalchain.service

import org.bukkit.entity.Player
import net.kyori.adventure.text.Component
import ym.globalchain.GlobalChain
import ym.globalchain.config.PluginConfig
import ym.globalchain.data.MarketRepository
import ym.globalchain.model.CancelResult
import ym.globalchain.model.ListingBrowseQuery
import ym.globalchain.model.ListingPage
import ym.globalchain.model.ListingSort
import ym.globalchain.model.MarketItemRecord
import ym.globalchain.model.MailboxPage
import ym.globalchain.model.MailboxReservationResult
import ym.globalchain.model.PurchaseResult
import ym.globalchain.model.PurchaseQuoteResult
import ym.globalchain.model.RepriceResult
import ym.globalchain.model.SaleDraft
import ym.globalchain.model.TrackedFlow
import ym.globalchain.util.CompatibilityDebug
import ym.globalchain.util.InventoryOps
import ym.globalchain.util.ItemComponents
import ym.globalchain.util.ItemNaming
import ym.globalchain.util.ItemStackCodec
import ym.globalchain.util.MoneyFormats
import ym.globalchain.util.TrackedItemService
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MarketService(
    private val plugin: GlobalChain,
    private val config: PluginConfig,
    private val repository: MarketRepository,
    private val walletService: WalletService,
    private val trackedItemService: TrackedItemService,
) {

    private val codec = ItemStackCodec()
    private val maxPriceMinor = MoneyFormats.parseMinorUnits(config.market.maxPriceRaw, config.currency.scale) ?: Long.MAX_VALUE
    private val saleFeeRate = runCatching { BigDecimal(config.market.saleFeeRateRaw) }
        .getOrDefault(BigDecimal.ZERO)
        .coerceAtLeast(BigDecimal.ZERO)
        .coerceAtMost(BigDecimal.ONE)

    fun balance(player: Player): CompletableFuture<ServiceFeedback> {
        return walletService.balance(player.uniqueId, player.name).thenApply { balance ->
            ServiceFeedback("messages.balance", mapOf("balance" to MoneyFormats.formatMinorUnits(balance, config.currency.scale)))
        }
    }

    fun browsePage(
        player: Player,
        page: Int,
        mineOnly: Boolean,
        searchTerm: String?,
        categoryPrefixes: List<String>,
        sort: ListingSort,
        pageSize: Int,
    ): CompletableFuture<ListingPage> {
        val now = System.currentTimeMillis()
        return repository.expireListings(now).thenCompose {
            repository.browse(
                ListingBrowseQuery(
                    page = page,
                    pageSize = pageSize.coerceAtLeast(1),
                    sellerId = if (mineOnly) player.uniqueId else null,
                    searchTerm = searchTerm,
                    categoryPrefixes = categoryPrefixes,
                    sort = sort,
                    asOf = now,
                ),
            )
        }
    }

    fun mailboxPage(player: Player, page: Int, pageSize: Int): CompletableFuture<MailboxPage> {
        return repository.listMailbox(player.uniqueId, page, pageSize.coerceAtLeast(1))
    }

    fun sell(player: Player, priceRaw: String): CompletableFuture<ServiceFeedback> {
        val priceMinor = MoneyFormats.parseMinorUnits(priceRaw, config.currency.scale)
            ?: return CompletableFuture.completedFuture(ServiceFeedback("messages.invalid-price", mapOf("scale" to config.currency.scale.toString())))
        val validationError = validatePrice(priceMinor)
        if (validationError != null) {
            return CompletableFuture.completedFuture(validationError)
        }
        return performSale(player, priceMinor, expectedDraft = null)
    }

    fun sellDraft(player: Player, draft: SaleDraft): CompletableFuture<ServiceFeedback> {
        val priceMinor = draft.priceMinor
            ?: return CompletableFuture.completedFuture(ServiceFeedback("messages.sell-confirm-price-required"))
        val validationError = validatePrice(priceMinor)
        if (validationError != null) {
            return CompletableFuture.completedFuture(validationError)
        }
        return performSale(player, priceMinor, draft)
    }

    private fun performSale(player: Player, priceMinor: Long, expectedDraft: SaleDraft?): CompletableFuture<ServiceFeedback> {
        val intentId = UUID.randomUUID()
        val token = UUID.randomUUID()
        return repository.expireListings(System.currentTimeMillis()).thenCompose {
            repository.countActiveListings(player.uniqueId)
        }.thenCompose { count ->
            if (count >= config.market.maxListingsPerPlayer) {
                CompletableFuture.completedFuture(
                    ServiceFeedback("messages.too-many-listings", mapOf("limit" to config.market.maxListingsPerPlayer.toString())),
                )
            } else {
                plugin.scheduler().supplyOnPlayer(player) {
                    InventoryOps.stageMainHandSale(player, trackedItemService, intentId, token)?.let(::snapshotMarketItem)
                }.thenCompose { stagedSnapshot ->
                    if (stagedSnapshot == null) {
                        CompletableFuture.completedFuture(ServiceFeedback("messages.holding-empty"))
                    } else if (expectedDraft != null && !matchesDraft(stagedSnapshot, expectedDraft)) {
                        plugin.scheduler().runOnPlayer(player) {
                            InventoryOps.rollbackSaleStage(player, trackedItemService, intentId)
                        }.thenApply {
                            ServiceFeedback("messages.sell-confirm-item-changed")
                        }
                    } else {
                        finalizeStagedSale(player, intentId, priceMinor, stagedSnapshot)
                    }
                }
            }
        }
    }

    private fun finalizeStagedSale(player: Player, intentId: UUID, priceMinor: Long, stagedItem: MarketItemRecord): CompletableFuture<ServiceFeedback> {
        val expireAt = System.currentTimeMillis() + (config.market.listingExpireSeconds * 1000L)
        return repository.createSaleIntent(intentId, player.uniqueId, player.name, priceMinor, stagedItem).thenCompose {
            plugin.scheduler().supplyOnPlayer(player) {
                InventoryOps.removeStagedSale(player, trackedItemService, intentId)
            }
        }.thenCompose { removedItem ->
            if (removedItem == null) {
                repository.abortSale(intentId).thenCompose {
                    plugin.scheduler().runOnPlayer(player) {
                        InventoryOps.rollbackSaleStage(player, trackedItemService, intentId)
                    }
                }.thenApply {
                    ServiceFeedback("messages.sale-failed")
                }
            } else {
                CompatibilityDebug.logSnapshot(plugin, config.debug.compatibilityMode, "sale-commit-remove", removedItem, stagedItem.itemData)
                val soldItemComponent = ItemComponents.fromItemStack(removedItem, stagedItem.itemName)
                repository.commitSale(intentId, player.uniqueId, player.name, stagedItem, priceMinor, expireAt)
                    .thenApply {
                        ServiceFeedback(
                            key = "messages.sale-success",
                            placeholders = mapOf(
                                "id" to intentId.toString(),
                                "amount" to stagedItem.amount.toString(),
                                "price" to MoneyFormats.formatMinorUnits(priceMinor, config.currency.scale),
                            ),
                            componentPlaceholders = mapOf(
                                "item" to soldItemComponent,
                            ),
                        )
                    }.exceptionallyCompose {
                        repository.recoverSaleToMailbox(intentId).thenApply {
                            ServiceFeedback("messages.sale-failed")
                        }
                    }
            }
        }.exceptionallyCompose {
            plugin.scheduler().runOnPlayer(player) {
                InventoryOps.rollbackSaleStage(player, trackedItemService, intentId)
            }.thenApply { ServiceFeedback("messages.sale-failed") }
        }
    }

    fun buy(player: Player, listingIdPrefix: String): CompletableFuture<ServiceFeedback> {
        return repository.expireListings(System.currentTimeMillis()).thenCompose {
            repository.quotePurchase(listingIdPrefix, player.uniqueId)
        }.thenCompose { quote ->
            when (quote) {
                PurchaseQuoteResult.NotFound -> CompletableFuture.completedFuture(ServiceFeedback("messages.listing-not-found"))
                PurchaseQuoteResult.Ambiguous -> CompletableFuture.completedFuture(ServiceFeedback("messages.listing-id-ambiguous"))
                PurchaseQuoteResult.OwnListing -> CompletableFuture.completedFuture(ServiceFeedback("messages.buy-self"))
                PurchaseQuoteResult.Expired -> CompletableFuture.completedFuture(ServiceFeedback("messages.listing-expired"))
                is PurchaseQuoteResult.Success -> purchaseQuotedListing(player, quote.listing)
            }
        }
    }

    fun cancel(player: Player, listingIdPrefix: String): CompletableFuture<ServiceFeedback> {
        return repository.cancelListing(listingIdPrefix, player.uniqueId).thenApply { result ->
            when (result) {
                is CancelResult.NotFound -> ServiceFeedback("messages.listing-not-found")
                is CancelResult.Ambiguous -> ServiceFeedback("messages.listing-id-ambiguous")
                is CancelResult.Success -> ServiceFeedback(
                    "messages.cancel-success",
                    mapOf(
                        "amount" to result.listing.amount.toString(),
                    ),
                    componentPlaceholders = mapOf(
                        "item" to ItemComponents.fromMaterialKey(result.listing.materialKey, result.listing.itemName),
                    ),
                )
            }
        }
    }

    fun reprice(player: Player, listingIdPrefix: String, priceRaw: String): CompletableFuture<ServiceFeedback> {
        val priceMinor = MoneyFormats.parseMinorUnits(priceRaw, config.currency.scale)
            ?: return CompletableFuture.completedFuture(ServiceFeedback("messages.invalid-price", mapOf("scale" to config.currency.scale.toString())))
        if (priceMinor <= 0L) {
            return CompletableFuture.completedFuture(ServiceFeedback("messages.invalid-price", mapOf("scale" to config.currency.scale.toString())))
        }
        if (priceMinor > maxPriceMinor) {
            return CompletableFuture.completedFuture(ServiceFeedback("messages.price-too-high"))
        }
        return repository.expireListings(System.currentTimeMillis()).thenCompose {
            repository.repriceListing(listingIdPrefix, player.uniqueId, priceMinor)
        }.thenApply { result ->
            when (result) {
                is RepriceResult.NotFound -> ServiceFeedback("messages.listing-not-found")
                is RepriceResult.Ambiguous -> ServiceFeedback("messages.listing-id-ambiguous")
                is RepriceResult.Success -> ServiceFeedback(
                    "messages.reprice-success",
                    mapOf(
                        "amount" to result.listing.amount.toString(),
                        "price" to MoneyFormats.formatMinorUnits(result.listing.priceMinor, config.currency.scale),
                    ),
                    componentPlaceholders = mapOf(
                        "item" to ItemComponents.fromMaterialKey(result.listing.materialKey, result.listing.itemName),
                    ),
                )
            }
        }
    }

    fun claim(player: Player, claimIdPrefix: String?): CompletableFuture<ServiceFeedback> {
        return repository.reserveMailbox(player.uniqueId, claimIdPrefix).thenCompose { result ->
            when (result) {
                is MailboxReservationResult.NotFound -> CompletableFuture.completedFuture(ServiceFeedback("messages.claim-empty"))
                is MailboxReservationResult.Ambiguous -> CompletableFuture.completedFuture(ServiceFeedback("messages.claim-id-ambiguous"))
                is MailboxReservationResult.Success -> {
                    plugin.scheduler().supplyOnPlayer(player) {
                        val taggedItem = codec.decode(result.entry.itemData)
                        CompatibilityDebug.logSnapshot(plugin, config.debug.compatibilityMode, "mailbox-claim-decode", taggedItem, result.entry.itemData)
                        trackedItemService.apply(taggedItem, TrackedFlow.DELIVERY, result.deliveryIntentId, UUID.randomUUID())
                        val itemComponent = ItemComponents.fromMaterial(taggedItem.type, result.entry.itemName)
                        if (!InventoryOps.canFit(player, taggedItem)) {
                            ClaimAttempt(false, itemComponent)
                        } else {
                            ClaimAttempt(InventoryOps.addClaimedItem(player, taggedItem.clone()), itemComponent)
                        }
                    }.thenCompose { claim ->
                        if (!claim.added) {
                            repository.abortDelivery(result.deliveryIntentId).thenApply {
                                ServiceFeedback("messages.claim-no-space")
                            }
                        } else {
                            repository.commitDelivery(result.deliveryIntentId).thenCompose {
                                plugin.scheduler().runOnPlayer(player) {
                                    trackedItemService.stripIntent(player, result.deliveryIntentId)
                                }.thenApply {
                                    ServiceFeedback(
                                        "messages.claim-success",
                                        mapOf(
                                            "amount" to result.entry.amount.toString(),
                                        ),
                                        componentPlaceholders = mapOf(
                                            "item" to claim.itemComponent,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun snapshotMarketItem(item: org.bukkit.inventory.ItemStack): MarketItemRecord {
        val clean = trackedItemService.clear(item.clone())
        val encoded = codec.encode(clean)
        CompatibilityDebug.logSnapshot(plugin, config.debug.compatibilityMode, "sale-snapshot", clean, encoded)
        return MarketItemRecord(
            itemData = encoded,
            itemName = ItemNaming.name(clean),
            materialKey = clean.type.key.toString(),
            amount = clean.amount,
        )
    }

    private fun purchaseQuotedListing(player: Player, listing: ym.globalchain.model.ListingSummary): CompletableFuture<ServiceFeedback> {
        return walletService.withdraw(player.uniqueId, player.name, listing.priceMinor).thenCompose { withdrawal ->
            when (withdrawal) {
                WalletOperationResult.InsufficientFunds -> CompletableFuture.completedFuture(
                    ServiceFeedback(
                        "messages.insufficient-funds",
                        mapOf("price" to MoneyFormats.formatMinorUnits(listing.priceMinor, config.currency.scale)),
                    ),
                )
                is WalletOperationResult.Failure -> {
                    plugin.logger.warning("Vault withdraw failed for ${player.uniqueId}: ${withdrawal.message.orEmpty()}")
                    CompletableFuture.completedFuture(ServiceFeedback("messages.economy-operation-failed"))
                }
                is WalletOperationResult.Success -> {
                    repository.purchaseListing(listing.listingId.toString(), player.uniqueId, player.name, sellerPayoutMinor(listing.priceMinor)).thenCompose { result ->
                        when (result) {
                            is PurchaseResult.NotFound -> refundFailedPurchase(player, listing, ServiceFeedback("messages.listing-not-found"))
                            is PurchaseResult.Ambiguous -> refundFailedPurchase(player, listing, ServiceFeedback("messages.listing-id-ambiguous"))
                            is PurchaseResult.OwnListing -> refundFailedPurchase(player, listing, ServiceFeedback("messages.buy-self"))
                            is PurchaseResult.Expired -> refundFailedPurchase(player, listing, ServiceFeedback("messages.listing-expired"))
                            is PurchaseResult.InsufficientFunds -> refundFailedPurchase(
                                player,
                                listing,
                                ServiceFeedback(
                                    "messages.insufficient-funds",
                                    mapOf("price" to MoneyFormats.formatMinorUnits(result.required, config.currency.scale)),
                                ),
                            )
                            is PurchaseResult.Success -> settleSellerPayout(result.listing).thenApply {
                                ServiceFeedback(
                                    "messages.buy-success",
                                    mapOf(
                                        "amount" to result.listing.amount.toString(),
                                        "price" to MoneyFormats.formatMinorUnits(result.listing.priceMinor, config.currency.scale),
                                    ),
                                    componentPlaceholders = mapOf(
                                        "item" to ItemComponents.fromMaterialKey(result.listing.materialKey, result.listing.itemName),
                                    ),
                                )
                            }
                        }
                    }.exceptionallyCompose { throwable ->
                        refundFailedPurchase(player, listing, ServiceFeedback("messages.economy-operation-failed"), throwable)
                    }
                }
            }
        }
    }

    private fun refundFailedPurchase(
        player: Player,
        listing: ym.globalchain.model.ListingSummary,
        feedback: ServiceFeedback,
        throwable: Throwable? = null,
    ): CompletableFuture<ServiceFeedback> {
        return walletService.deposit(player.uniqueId, player.name, listing.priceMinor).handle { result, refundError ->
            if (throwable != null) {
                plugin.logger.warning("Purchase finalize failed for ${listing.listingId}: ${throwable.message}")
            }
            if (refundError != null) {
                plugin.logger.log(java.util.logging.Level.SEVERE, "Vault refund failed for ${player.uniqueId} after purchase rollback.", refundError)
            } else if (result !is WalletOperationResult.Success) {
                plugin.logger.severe("Vault refund did not complete for ${player.uniqueId} after purchase rollback.")
            }
            feedback
        }
    }

    private fun settleSellerPayout(listing: ym.globalchain.model.ListingSummary): CompletableFuture<Boolean> {
        val payoutMinor = sellerPayoutMinor(listing.priceMinor)
        return walletService.deposit(listing.sellerId, listing.sellerName, payoutMinor).thenCompose { result ->
            when (result) {
                is WalletOperationResult.Success -> repository.completePayout(listing.listingId).thenApply { completed ->
                    if (!completed) {
                        plugin.logger.severe("Payout completion flag was not updated for listing ${listing.listingId}.")
                    }
                    completed
                }
                WalletOperationResult.InsufficientFunds -> {
                    plugin.logger.warning("Vault rejected seller payout for ${listing.sellerId} on listing ${listing.listingId}.")
                    CompletableFuture.completedFuture(false)
                }
                is WalletOperationResult.Failure -> {
                    plugin.logger.warning("Vault seller payout failed for ${listing.sellerId} on listing ${listing.listingId}: ${result.message.orEmpty()}")
                    CompletableFuture.completedFuture(false)
                }
            }
        }
    }

    private fun validatePrice(priceMinor: Long): ServiceFeedback? {
        if (priceMinor <= 0L) {
            return ServiceFeedback("messages.invalid-price", mapOf("scale" to config.currency.scale.toString()))
        }
        if (priceMinor > maxPriceMinor) {
            return ServiceFeedback("messages.price-too-high")
        }
        return null
    }

    private fun matchesDraft(snapshot: MarketItemRecord, draft: SaleDraft): Boolean {
        return snapshot.itemData == draft.itemData &&
            snapshot.materialKey == draft.materialKey &&
            snapshot.amount == draft.amount
    }

    private fun sellerPayoutMinor(priceMinor: Long): Long {
        val fee = BigDecimal.valueOf(priceMinor)
            .multiply(saleFeeRate)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
        return (priceMinor - fee).coerceAtLeast(0L)
    }

    fun feeMinor(priceMinor: Long): Long {
        val payout = sellerPayoutMinor(priceMinor)
        return (priceMinor - payout).coerceAtLeast(0L)
    }

    private data class ClaimAttempt(
        val added: Boolean,
        val itemComponent: Component,
    )
}
