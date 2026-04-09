package ym.globalchain.data

import org.bukkit.configuration.file.YamlConfiguration
import ym.globalchain.model.CancelResult
import ym.globalchain.model.DeliveryIntentRecord
import ym.globalchain.model.IntentStatus
import ym.globalchain.model.ListingBrowseQuery
import ym.globalchain.model.ListingPage
import ym.globalchain.model.ListingStatus
import ym.globalchain.model.ListingSummary
import ym.globalchain.model.LookupResult
import ym.globalchain.model.MarketItemRecord
import ym.globalchain.model.MailboxEntry
import ym.globalchain.model.MailboxPage
import ym.globalchain.model.MailboxReservationResult
import ym.globalchain.model.MailboxStatus
import ym.globalchain.model.ListingSort
import ym.globalchain.model.PayoutRecord
import ym.globalchain.model.PayoutStatus
import ym.globalchain.model.PurchaseResult
import ym.globalchain.model.PurchaseQuoteResult
import ym.globalchain.model.RepriceResult
import ym.globalchain.model.SaleIntentRecord
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class YamlMarketRepository(
    private val file: Path,
) : MarketRepository {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GlobalChain-YamlStore").apply { isDaemon = true }
    }
    private val state = SnapshotState.load(file)

    override fun countActiveListings(playerId: UUID): CompletableFuture<Int> = async {
        val now = System.currentTimeMillis()
        state.listings.values.count { it.sellerId == playerId && it.status == ListingStatus.ACTIVE && !it.isExpired(now) }
    }

    override fun createSaleIntent(intentId: UUID, playerId: UUID, playerName: String, priceMinor: Long, item: MarketItemRecord): CompletableFuture<Unit> = mutate {
        val now = System.currentTimeMillis()
        state.saleIntents[intentId] = SaleIntentSnapshot(
            intentId = intentId,
            playerId = playerId,
            playerName = playerName,
            priceMinor = priceMinor,
            itemData = item.itemData,
            itemName = item.itemName,
            amount = item.amount,
            status = IntentStatus.PREPARED,
            createdAt = now,
            updatedAt = now,
        )
    }

    override fun commitSale(intentId: UUID, playerId: UUID, playerName: String, item: MarketItemRecord, priceMinor: Long, expireAt: Long): CompletableFuture<Unit> = mutate {
        val now = System.currentTimeMillis()
        state.listings[intentId] = ListingSnapshot(
            listingId = intentId,
            sellerId = playerId,
            sellerName = playerName,
            itemData = item.itemData,
            itemName = item.itemName,
            materialKey = item.materialKey,
            amount = item.amount,
            priceMinor = priceMinor,
            status = ListingStatus.ACTIVE,
            buyerId = null,
            createdAt = now,
            updatedAt = now,
            expireAt = expireAt,
        )
        state.saleIntents[intentId]?.status = IntentStatus.COMMITTED
        state.saleIntents[intentId]?.updatedAt = now
    }

    override fun abortSale(intentId: UUID): CompletableFuture<Unit> = mutate {
        state.saleIntents[intentId]?.apply {
            status = IntentStatus.ABORTED
            updatedAt = System.currentTimeMillis()
        }
    }

    override fun recoverSaleToMailbox(intentId: UUID): CompletableFuture<Boolean> = mutateWithResult {
        val intent = state.saleIntents[intentId] ?: return@mutateWithResult false
        if (intent.status != IntentStatus.PREPARED) {
            return@mutateWithResult false
        }
        val claimId = UUID.randomUUID()
        val now = System.currentTimeMillis()
        state.mailbox[claimId] = MailboxSnapshot(
            claimId = claimId,
            playerId = intent.playerId,
            itemData = intent.itemData,
            itemName = intent.itemName,
            amount = intent.amount,
            referenceId = intent.intentId,
            status = MailboxStatus.AVAILABLE,
            createdAt = now,
            updatedAt = now,
        )
        intent.status = IntentStatus.RECOVERED
        intent.updatedAt = now
        true
    }

    override fun browse(query: ListingBrowseQuery): CompletableFuture<ListingPage> = async {
        val filtered = state.listings.values
            .asSequence()
            .filter { it.status == ListingStatus.ACTIVE && !it.isExpired(query.asOf) }
            .filter { query.sellerId == null || it.sellerId == query.sellerId }
            .filter { matchesSearch(it, query.searchTerm) }
            .filter { matchesCategory(it, query.categoryPrefixes) }
            .toList()
            .let { listings ->
                when (query.sort) {
                    ListingSort.NEWEST -> listings.sortedByDescending { it.createdAt }
                    ListingSort.PRICE_LOW -> listings.sortedWith(compareBy<ListingSnapshot> { it.priceMinor }.thenByDescending { it.createdAt })
                    ListingSort.PRICE_HIGH -> listings.sortedWith(compareByDescending<ListingSnapshot> { it.priceMinor }.thenByDescending { it.createdAt })
                    ListingSort.EXPIRING -> listings.sortedWith(compareBy<ListingSnapshot> { it.expireAt.takeIf { expireAt -> expireAt > 0L } ?: Long.MAX_VALUE }.thenByDescending { it.createdAt })
                }
            }
        val offset = ((query.page - 1) * query.pageSize).coerceAtLeast(0)
        val slice = filtered.drop(offset).take(query.pageSize).map { it.toSummary() }
        ListingPage(slice, filtered.size, query.page, query.pageSize)
    }

    override fun expireListings(now: Long): CompletableFuture<Int> = mutateWithConditionalResult {
        val expired = state.listings.values
            .filter { it.status == ListingStatus.ACTIVE && it.isExpired(now) }
            .sortedBy { it.expireAt }
        if (expired.isEmpty()) {
            return@mutateWithConditionalResult MutationResult(0, false)
        }
        expired.forEach { listing ->
            insertMailbox(
                playerId = listing.sellerId,
                itemData = listing.itemData,
                itemName = listing.itemName,
                amount = listing.amount,
                referenceId = listing.listingId,
                status = MailboxStatus.AVAILABLE,
                now = now,
            )
            listing.status = ListingStatus.CANCELLED
            listing.updatedAt = now
        }
        MutationResult(expired.size, true)
    }

    override fun quotePurchase(listingPrefix: String, buyerId: UUID): CompletableFuture<PurchaseQuoteResult> = async {
        when (val resolved = resolveListing(listingPrefix, null)) {
            is LookupResult.NotFound -> PurchaseQuoteResult.NotFound
            is LookupResult.Ambiguous -> PurchaseQuoteResult.Ambiguous
            is LookupResult.Found -> {
                val listing = resolved.value
                when {
                    listing.isExpired(System.currentTimeMillis()) -> PurchaseQuoteResult.Expired
                    listing.sellerId == buyerId -> PurchaseQuoteResult.OwnListing
                    else -> PurchaseQuoteResult.Success(listing.toSummary())
                }
            }
        }
    }

    override fun purchaseListing(listingPrefix: String, buyerId: UUID, buyerName: String, sellerPayoutMinor: Long): CompletableFuture<PurchaseResult> = mutateWithResult {
        when (val resolved = resolveListing(listingPrefix, null)) {
            is LookupResult.NotFound -> PurchaseResult.NotFound
            is LookupResult.Ambiguous -> PurchaseResult.Ambiguous
            is LookupResult.Found -> {
                val listing = resolved.value
                if (listing.isExpired(System.currentTimeMillis())) {
                    val now = System.currentTimeMillis()
                    insertMailbox(
                        playerId = listing.sellerId,
                        itemData = listing.itemData,
                        itemName = listing.itemName,
                        amount = listing.amount,
                        referenceId = listing.listingId,
                        status = MailboxStatus.AVAILABLE,
                        now = now,
                    )
                    listing.status = ListingStatus.CANCELLED
                    listing.updatedAt = now
                    return@mutateWithResult PurchaseResult.Expired
                }
                if (listing.sellerId == buyerId) {
                    PurchaseResult.OwnListing
                } else {
                    val now = System.currentTimeMillis()
                    insertMailbox(
                        playerId = buyerId,
                        itemData = listing.itemData,
                        itemName = listing.itemName,
                        amount = listing.amount,
                        referenceId = listing.listingId,
                        status = MailboxStatus.AVAILABLE,
                        now = now,
                    )
                    insertPayout(
                        referenceId = listing.listingId,
                        sellerId = listing.sellerId,
                        sellerName = listing.sellerName,
                        amountMinor = sellerPayoutMinor,
                        status = PayoutStatus.PENDING,
                        now = now,
                    )
                    listing.status = ListingStatus.SOLD
                    listing.buyerId = buyerId
                    listing.updatedAt = now
                    PurchaseResult.Success(listing.toSummary())
                }
            }
        }
    }

    override fun cancelListing(listingPrefix: String, sellerId: UUID): CompletableFuture<CancelResult> = mutateWithResult {
        when (val resolved = resolveListing(listingPrefix, sellerId)) {
            is LookupResult.NotFound -> CancelResult.NotFound
            is LookupResult.Ambiguous -> CancelResult.Ambiguous
            is LookupResult.Found -> {
                val listing = resolved.value
                val now = System.currentTimeMillis()
                insertMailbox(
                    playerId = sellerId,
                    itemData = listing.itemData,
                    itemName = listing.itemName,
                    amount = listing.amount,
                    referenceId = listing.listingId,
                    status = MailboxStatus.AVAILABLE,
                    now = now,
                )
                listing.status = ListingStatus.CANCELLED
                listing.updatedAt = now
                CancelResult.Success(listing.toSummary())
            }
        }
    }

    override fun repriceListing(listingPrefix: String, sellerId: UUID, priceMinor: Long): CompletableFuture<RepriceResult> = mutateWithResult {
        when (val resolved = resolveListing(listingPrefix, sellerId)) {
            is LookupResult.NotFound -> RepriceResult.NotFound
            is LookupResult.Ambiguous -> RepriceResult.Ambiguous
            is LookupResult.Found -> {
                val listing = resolved.value
                listing.priceMinor = priceMinor
                listing.updatedAt = System.currentTimeMillis()
                RepriceResult.Success(listing.toSummary())
            }
        }
    }

    override fun listMailbox(playerId: UUID, page: Int, pageSize: Int): CompletableFuture<MailboxPage> = async {
        val available = state.mailbox.values
            .asSequence()
            .filter { it.playerId == playerId && it.status == MailboxStatus.AVAILABLE }
            .sortedBy { it.createdAt }
            .toList()
        val offset = ((page - 1) * pageSize).coerceAtLeast(0)
        val slice = available.drop(offset).take(pageSize).map { it.toEntry() }
        MailboxPage(slice, available.size, page, pageSize)
    }

    override fun reserveMailbox(playerId: UUID, claimPrefix: String?): CompletableFuture<MailboxReservationResult> = mutateWithResult {
        when (val resolved = resolveMailbox(playerId, claimPrefix)) {
            is LookupResult.NotFound -> MailboxReservationResult.NotFound
            is LookupResult.Ambiguous -> MailboxReservationResult.Ambiguous
            is LookupResult.Found -> {
                val entry = resolved.value
                val deliveryIntentId = UUID.randomUUID()
                val now = System.currentTimeMillis()
                state.deliveryIntents[deliveryIntentId] = DeliveryIntentSnapshot(
                    intentId = deliveryIntentId,
                    claimId = entry.claimId,
                    playerId = playerId,
                    itemData = entry.itemData,
                    itemName = entry.itemName,
                    amount = entry.amount,
                    status = IntentStatus.PREPARED,
                    createdAt = now,
                    updatedAt = now,
                )
                state.mailbox[entry.claimId]?.status = MailboxStatus.RESERVED
                state.mailbox[entry.claimId]?.updatedAt = now
                MailboxReservationResult.Success(entry.toEntry(), deliveryIntentId)
            }
        }
    }

    override fun commitDelivery(intentId: UUID): CompletableFuture<Boolean> = mutateWithResult {
        val intent = state.deliveryIntents[intentId] ?: return@mutateWithResult false
        if (intent.status != IntentStatus.PREPARED) return@mutateWithResult false
        val now = System.currentTimeMillis()
        intent.status = IntentStatus.COMMITTED
        intent.updatedAt = now
        state.mailbox[intent.claimId]?.status = MailboxStatus.CLAIMED
        state.mailbox[intent.claimId]?.updatedAt = now
        true
    }

    override fun abortDelivery(intentId: UUID): CompletableFuture<Boolean> = mutateWithResult {
        val intent = state.deliveryIntents[intentId] ?: return@mutateWithResult false
        if (intent.status != IntentStatus.PREPARED) return@mutateWithResult false
        val now = System.currentTimeMillis()
        intent.status = IntentStatus.ABORTED
        intent.updatedAt = now
        state.mailbox[intent.claimId]?.status = MailboxStatus.AVAILABLE
        state.mailbox[intent.claimId]?.updatedAt = now
        true
    }

    override fun getBalance(playerId: UUID, playerName: String): CompletableFuture<Long> = async {
        state.wallets[playerId]?.balance ?: 0L
    }

    override fun grantBalance(playerId: UUID, playerName: String, delta: Long): CompletableFuture<Long> = mutateWithResult {
        val wallet = ensureWallet(playerId, playerName)
        val now = System.currentTimeMillis()
        wallet.balance += delta
        wallet.updatedAt = now
        appendLedger(playerId, delta, "GRANT", UUID.randomUUID(), now)
        wallet.balance
    }

    override fun pendingPayouts(): CompletableFuture<List<PayoutRecord>> = async {
        state.payouts.values
            .filter { it.status == PayoutStatus.PENDING }
            .sortedBy { it.createdAt }
            .map { it.toRecord() }
    }

    override fun completePayout(referenceId: UUID): CompletableFuture<Boolean> = mutateWithResult {
        val payout = state.payouts[referenceId] ?: return@mutateWithResult false
        if (payout.status != PayoutStatus.PENDING) {
            return@mutateWithResult false
        }
        payout.status = PayoutStatus.PAID
        payout.updatedAt = System.currentTimeMillis()
        true
    }

    override fun preparedSales(playerId: UUID): CompletableFuture<List<SaleIntentRecord>> = async {
        state.saleIntents.values.filter { it.playerId == playerId && it.status == IntentStatus.PREPARED }.map { it.toRecord() }
    }

    override fun preparedDeliveries(playerId: UUID): CompletableFuture<List<DeliveryIntentRecord>> = async {
        state.deliveryIntents.values.filter { it.playerId == playerId && it.status == IntentStatus.PREPARED }.map { it.toRecord() }
    }

    override fun close() {
        executor.shutdownNow()
    }

    private fun matchesSearch(listing: ListingSnapshot, raw: String?): Boolean {
        val query = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return true
        return listing.itemName.lowercase().contains(query) ||
            listing.sellerName.lowercase().contains(query) ||
            listing.materialKey.lowercase().contains(query)
    }

    private fun matchesCategory(listing: ListingSnapshot, prefixes: List<String>): Boolean {
        if (prefixes.isEmpty()) return true
        val key = listing.materialKey.lowercase()
        return prefixes.any { key.startsWith(it.lowercase()) }
    }

    private fun resolveListing(prefix: String, sellerId: UUID?): LookupResult<ListingSnapshot> {
        val now = System.currentTimeMillis()
        val matches = state.listings.values
            .asSequence()
            .filter { it.status == ListingStatus.ACTIVE && !it.isExpired(now) }
            .filter { it.listingId.toString().startsWith(prefix, ignoreCase = true) }
            .filter { sellerId == null || it.sellerId == sellerId }
            .sortedBy { it.createdAt }
            .take(2)
            .toList()
        return when {
            matches.isEmpty() -> LookupResult.NotFound
            matches.size > 1 -> LookupResult.Ambiguous
            else -> LookupResult.Found(matches.first())
        }
    }

    private fun resolveMailbox(playerId: UUID, prefix: String?): LookupResult<MailboxSnapshot> {
        val matches = state.mailbox.values
            .asSequence()
            .filter { it.playerId == playerId && it.status == MailboxStatus.AVAILABLE }
            .filter { prefix.isNullOrBlank() || it.claimId.toString().startsWith(prefix, ignoreCase = true) }
            .sortedBy { it.createdAt }
            .take(2)
            .toList()
        return when {
            matches.isEmpty() -> LookupResult.NotFound
            matches.size > 1 -> LookupResult.Ambiguous
            else -> LookupResult.Found(matches.first())
        }
    }

    private fun ensureWallet(playerId: UUID, playerName: String): WalletSnapshot {
        return state.wallets.computeIfAbsent(playerId) {
            WalletSnapshot(playerId, playerName, 0L, System.currentTimeMillis())
        }.also {
            it.playerName = playerName
        }
    }

    private fun appendLedger(playerId: UUID, delta: Long, reason: String, referenceId: UUID, createdAt: Long) {
        state.ledger += LedgerSnapshot(UUID.randomUUID(), playerId, delta, reason, referenceId, createdAt)
    }

    private fun insertPayout(
        referenceId: UUID,
        sellerId: UUID,
        sellerName: String,
        amountMinor: Long,
        status: PayoutStatus,
        now: Long,
    ) {
        state.payouts[referenceId] = PayoutSnapshot(
            referenceId = referenceId,
            sellerId = sellerId,
            sellerName = sellerName,
            amountMinor = amountMinor,
            status = status,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun insertMailbox(
        playerId: UUID,
        itemData: String,
        itemName: String,
        amount: Int,
        referenceId: UUID,
        status: MailboxStatus,
        now: Long,
    ) {
        val claimId = UUID.randomUUID()
        state.mailbox[claimId] = MailboxSnapshot(
            claimId = claimId,
            playerId = playerId,
            itemData = itemData,
            itemName = itemName,
            amount = amount,
            referenceId = referenceId,
            status = status,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun <T> async(block: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(block, executor)

    private fun mutate(block: () -> Unit): CompletableFuture<Unit> = mutateWithResult {
        block()
        Unit
    }

    private fun <T> mutateWithResult(block: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync({
        val result = block()
        state.save(file)
        result
    }, executor)

    private fun <T> mutateWithConditionalResult(block: () -> MutationResult<T>): CompletableFuture<T> = CompletableFuture.supplyAsync({
        val result = block()
        if (result.dirty) {
            state.save(file)
        }
        result.value
    }, executor)
}

private data class MutationResult<T>(
    val value: T,
    val dirty: Boolean,
)

private data class SnapshotState(
    val wallets: MutableMap<UUID, WalletSnapshot>,
    val ledger: MutableList<LedgerSnapshot>,
    val saleIntents: MutableMap<UUID, SaleIntentSnapshot>,
    val listings: MutableMap<UUID, ListingSnapshot>,
    val mailbox: MutableMap<UUID, MailboxSnapshot>,
    val deliveryIntents: MutableMap<UUID, DeliveryIntentSnapshot>,
    val payouts: MutableMap<UUID, PayoutSnapshot>,
) {

    fun save(file: Path) {
        Files.createDirectories(file.parent)
        val yaml = YamlConfiguration()
        wallets.values.forEach { wallet ->
            val base = "wallets.${wallet.playerId}"
            yaml.set("$base.player-name", wallet.playerName)
            yaml.set("$base.balance", wallet.balance)
            yaml.set("$base.updated-at", wallet.updatedAt)
        }
        ledger.forEachIndexed { index, entry ->
            val base = "ledger.$index"
            yaml.set("$base.entry-id", entry.entryId.toString())
            yaml.set("$base.player-id", entry.playerId.toString())
            yaml.set("$base.delta", entry.delta)
            yaml.set("$base.reason", entry.reason)
            yaml.set("$base.reference-id", entry.referenceId.toString())
            yaml.set("$base.created-at", entry.createdAt)
        }
        saleIntents.values.forEach { intent ->
            val base = "sale-intents.${intent.intentId}"
            yaml.set("$base.player-id", intent.playerId.toString())
            yaml.set("$base.player-name", intent.playerName)
            yaml.set("$base.price-minor", intent.priceMinor)
            yaml.set("$base.item-data", intent.itemData)
            yaml.set("$base.item-name", intent.itemName)
            yaml.set("$base.amount", intent.amount)
            yaml.set("$base.status", intent.status.name)
            yaml.set("$base.created-at", intent.createdAt)
            yaml.set("$base.updated-at", intent.updatedAt)
        }
        listings.values.forEach { listing ->
            val base = "listings.${listing.listingId}"
            yaml.set("$base.seller-id", listing.sellerId.toString())
            yaml.set("$base.seller-name", listing.sellerName)
            yaml.set("$base.item-data", listing.itemData)
            yaml.set("$base.item-name", listing.itemName)
            yaml.set("$base.material-key", listing.materialKey)
            yaml.set("$base.amount", listing.amount)
            yaml.set("$base.price-minor", listing.priceMinor)
            yaml.set("$base.status", listing.status.name)
            yaml.set("$base.buyer-id", listing.buyerId?.toString())
            yaml.set("$base.created-at", listing.createdAt)
            yaml.set("$base.updated-at", listing.updatedAt)
            yaml.set("$base.expire-at", listing.expireAt)
        }
        mailbox.values.forEach { entry ->
            val base = "mailbox.${entry.claimId}"
            yaml.set("$base.player-id", entry.playerId.toString())
            yaml.set("$base.item-data", entry.itemData)
            yaml.set("$base.item-name", entry.itemName)
            yaml.set("$base.amount", entry.amount)
            yaml.set("$base.reference-id", entry.referenceId.toString())
            yaml.set("$base.status", entry.status.name)
            yaml.set("$base.created-at", entry.createdAt)
            yaml.set("$base.updated-at", entry.updatedAt)
        }
        deliveryIntents.values.forEach { intent ->
            val base = "delivery-intents.${intent.intentId}"
            yaml.set("$base.claim-id", intent.claimId.toString())
            yaml.set("$base.player-id", intent.playerId.toString())
            yaml.set("$base.item-data", intent.itemData)
            yaml.set("$base.item-name", intent.itemName)
            yaml.set("$base.amount", intent.amount)
            yaml.set("$base.status", intent.status.name)
            yaml.set("$base.created-at", intent.createdAt)
            yaml.set("$base.updated-at", intent.updatedAt)
        }
        payouts.values.forEach { payout ->
            val base = "payouts.${payout.referenceId}"
            yaml.set("$base.seller-id", payout.sellerId.toString())
            yaml.set("$base.seller-name", payout.sellerName)
            yaml.set("$base.amount-minor", payout.amountMinor)
            yaml.set("$base.status", payout.status.name)
            yaml.set("$base.created-at", payout.createdAt)
            yaml.set("$base.updated-at", payout.updatedAt)
        }
        val temp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8)
        runCatching {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.recover {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }.getOrThrow()
    }

    companion object {
        fun load(file: Path): SnapshotState {
            if (Files.notExists(file)) {
                return SnapshotState(linkedMapOf(), mutableListOf(), linkedMapOf(), linkedMapOf(), linkedMapOf(), linkedMapOf(), linkedMapOf())
            }
            val yaml = YamlConfiguration.loadConfiguration(file.toFile())
            val wallets = linkedMapOf<UUID, WalletSnapshot>()
            yaml.getConfigurationSection("wallets")?.getKeys(false)?.forEach { rawId ->
                val base = "wallets.$rawId"
                val playerId = UUID.fromString(rawId)
                wallets[playerId] = WalletSnapshot(
                    playerId = playerId,
                    playerName = yaml.getString("$base.player-name").orEmpty(),
                    balance = yaml.getLong("$base.balance"),
                    updatedAt = yaml.getLong("$base.updated-at"),
                )
            }
            val ledger = mutableListOf<LedgerSnapshot>()
            yaml.getConfigurationSection("ledger")?.getKeys(false)?.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }?.forEach { key ->
                val base = "ledger.$key"
                ledger += LedgerSnapshot(
                    entryId = UUID.fromString(yaml.getString("$base.entry-id").orEmpty()),
                    playerId = UUID.fromString(yaml.getString("$base.player-id").orEmpty()),
                    delta = yaml.getLong("$base.delta"),
                    reason = yaml.getString("$base.reason").orEmpty(),
                    referenceId = UUID.fromString(yaml.getString("$base.reference-id").orEmpty()),
                    createdAt = yaml.getLong("$base.created-at"),
                )
            }
            val saleIntents = linkedMapOf<UUID, SaleIntentSnapshot>()
            yaml.getConfigurationSection("sale-intents")?.getKeys(false)?.forEach { rawId ->
                val base = "sale-intents.$rawId"
                val intentId = UUID.fromString(rawId)
                saleIntents[intentId] = SaleIntentSnapshot(
                    intentId = intentId,
                    playerId = UUID.fromString(yaml.getString("$base.player-id").orEmpty()),
                    playerName = yaml.getString("$base.player-name").orEmpty(),
                    priceMinor = yaml.getLong("$base.price-minor"),
                    itemData = yaml.getString("$base.item-data").orEmpty(),
                    itemName = yaml.getString("$base.item-name").orEmpty(),
                    amount = yaml.getInt("$base.amount"),
                    status = IntentStatus.valueOf(yaml.getString("$base.status", IntentStatus.PREPARED.name).orEmpty()),
                    createdAt = yaml.getLong("$base.created-at"),
                    updatedAt = yaml.getLong("$base.updated-at"),
                )
            }
            val listings = linkedMapOf<UUID, ListingSnapshot>()
            yaml.getConfigurationSection("listings")?.getKeys(false)?.forEach { rawId ->
                val base = "listings.$rawId"
                val listingId = UUID.fromString(rawId)
                listings[listingId] = ListingSnapshot(
                    listingId = listingId,
                    sellerId = UUID.fromString(yaml.getString("$base.seller-id").orEmpty()),
                    sellerName = yaml.getString("$base.seller-name").orEmpty(),
                    itemData = yaml.getString("$base.item-data").orEmpty(),
                    itemName = yaml.getString("$base.item-name").orEmpty(),
                    materialKey = yaml.getString("$base.material-key").orEmpty(),
                    amount = yaml.getInt("$base.amount"),
                    priceMinor = yaml.getLong("$base.price-minor"),
                    status = ListingStatus.valueOf(yaml.getString("$base.status", ListingStatus.ACTIVE.name).orEmpty()),
                    buyerId = yaml.getString("$base.buyer-id")?.takeIf { it.isNotBlank() }?.let(UUID::fromString),
                    createdAt = yaml.getLong("$base.created-at"),
                    updatedAt = yaml.getLong("$base.updated-at"),
                    expireAt = yaml.getLong("$base.expire-at"),
                )
            }
            val mailbox = linkedMapOf<UUID, MailboxSnapshot>()
            yaml.getConfigurationSection("mailbox")?.getKeys(false)?.forEach { rawId ->
                val base = "mailbox.$rawId"
                val claimId = UUID.fromString(rawId)
                mailbox[claimId] = MailboxSnapshot(
                    claimId = claimId,
                    playerId = UUID.fromString(yaml.getString("$base.player-id").orEmpty()),
                    itemData = yaml.getString("$base.item-data").orEmpty(),
                    itemName = yaml.getString("$base.item-name").orEmpty(),
                    amount = yaml.getInt("$base.amount"),
                    referenceId = UUID.fromString(yaml.getString("$base.reference-id").orEmpty()),
                    status = MailboxStatus.valueOf(yaml.getString("$base.status", MailboxStatus.AVAILABLE.name).orEmpty()),
                    createdAt = yaml.getLong("$base.created-at"),
                    updatedAt = yaml.getLong("$base.updated-at"),
                )
            }
            val deliveryIntents = linkedMapOf<UUID, DeliveryIntentSnapshot>()
            yaml.getConfigurationSection("delivery-intents")?.getKeys(false)?.forEach { rawId ->
                val base = "delivery-intents.$rawId"
                val intentId = UUID.fromString(rawId)
                deliveryIntents[intentId] = DeliveryIntentSnapshot(
                    intentId = intentId,
                    claimId = UUID.fromString(yaml.getString("$base.claim-id").orEmpty()),
                    playerId = UUID.fromString(yaml.getString("$base.player-id").orEmpty()),
                    itemData = yaml.getString("$base.item-data").orEmpty(),
                    itemName = yaml.getString("$base.item-name").orEmpty(),
                    amount = yaml.getInt("$base.amount"),
                    status = IntentStatus.valueOf(yaml.getString("$base.status", IntentStatus.PREPARED.name).orEmpty()),
                    createdAt = yaml.getLong("$base.created-at"),
                    updatedAt = yaml.getLong("$base.updated-at"),
                )
            }
            val payouts = linkedMapOf<UUID, PayoutSnapshot>()
            yaml.getConfigurationSection("payouts")?.getKeys(false)?.forEach { rawId ->
                val base = "payouts.$rawId"
                val referenceId = UUID.fromString(rawId)
                payouts[referenceId] = PayoutSnapshot(
                    referenceId = referenceId,
                    sellerId = UUID.fromString(yaml.getString("$base.seller-id").orEmpty()),
                    sellerName = yaml.getString("$base.seller-name").orEmpty(),
                    amountMinor = yaml.getLong("$base.amount-minor"),
                    status = PayoutStatus.valueOf(yaml.getString("$base.status", PayoutStatus.PENDING.name).orEmpty()),
                    createdAt = yaml.getLong("$base.created-at"),
                    updatedAt = yaml.getLong("$base.updated-at"),
                )
            }
            return SnapshotState(wallets, ledger, saleIntents, listings, mailbox, deliveryIntents, payouts)
        }
    }
}

private data class WalletSnapshot(
    val playerId: UUID,
    var playerName: String,
    var balance: Long,
    var updatedAt: Long,
)

private data class LedgerSnapshot(
    val entryId: UUID,
    val playerId: UUID,
    val delta: Long,
    val reason: String,
    val referenceId: UUID,
    val createdAt: Long,
)

private data class SaleIntentSnapshot(
    val intentId: UUID,
    val playerId: UUID,
    val playerName: String,
    val priceMinor: Long,
    val itemData: String,
    val itemName: String,
    val amount: Int,
    var status: IntentStatus,
    val createdAt: Long,
    var updatedAt: Long,
) {
    fun toRecord() = SaleIntentRecord(intentId, playerId, playerName, priceMinor, itemData, itemName, amount, status)
}

private data class ListingSnapshot(
    val listingId: UUID,
    val sellerId: UUID,
    val sellerName: String,
    val itemData: String,
    val itemName: String,
    val materialKey: String,
    val amount: Int,
    var priceMinor: Long,
    var status: ListingStatus,
    var buyerId: UUID?,
    val createdAt: Long,
    var updatedAt: Long,
    val expireAt: Long,
) {
    fun toSummary() = ListingSummary(listingId, sellerId, sellerName, itemName, materialKey, amount, priceMinor, itemData, createdAt, expireAt)

    fun isExpired(now: Long): Boolean = expireAt > 0L && expireAt <= now
}

private data class MailboxSnapshot(
    val claimId: UUID,
    val playerId: UUID,
    val itemData: String,
    val itemName: String,
    val amount: Int,
    val referenceId: UUID,
    var status: MailboxStatus,
    val createdAt: Long,
    var updatedAt: Long,
) {
    fun toEntry() = MailboxEntry(claimId, playerId, itemData, itemName, amount, referenceId)
}

private data class DeliveryIntentSnapshot(
    val intentId: UUID,
    val claimId: UUID,
    val playerId: UUID,
    val itemData: String,
    val itemName: String,
    val amount: Int,
    var status: IntentStatus,
    val createdAt: Long,
    var updatedAt: Long,
) {
    fun toRecord() = DeliveryIntentRecord(intentId, claimId, playerId, itemData, itemName, amount, status)
}

private data class PayoutSnapshot(
    val referenceId: UUID,
    val sellerId: UUID,
    val sellerName: String,
    val amountMinor: Long,
    var status: PayoutStatus,
    val createdAt: Long,
    var updatedAt: Long,
) {
    fun toRecord() = PayoutRecord(referenceId, sellerId, sellerName, amountMinor, status)
}
