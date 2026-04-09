package ym.globalchain.data

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
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MysqlMarketRepository(
    private val databaseManager: DatabaseManager,
) : MarketRepository {

    override fun countActiveListings(playerId: UUID): CompletableFuture<Int> = databaseManager.supplyAsync { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM gc_listings WHERE seller_uuid = ? AND status = ? AND (expire_at = 0 OR expire_at > ?)").use { statement ->
            statement.setString(1, playerId.toString())
            statement.setString(2, ListingStatus.ACTIVE.name)
            statement.setLong(3, System.currentTimeMillis())
            statement.executeQuery().use { resultSet -> if (resultSet.next()) resultSet.getInt(1) else 0 }
        }
    }

    override fun createSaleIntent(intentId: UUID, playerId: UUID, playerName: String, priceMinor: Long, item: MarketItemRecord): CompletableFuture<Unit> {
        return databaseManager.transactionAsync { connection ->
            val now = System.currentTimeMillis()
            connection.prepareStatement(
                "INSERT INTO gc_sale_intents (intent_id, player_uuid, player_name, price_minor, item_data, item_name, amount, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, intentId.toString())
                statement.setString(2, playerId.toString())
                statement.setString(3, playerName)
                statement.setLong(4, priceMinor)
                statement.setString(5, item.itemData)
                statement.setString(6, item.itemName)
                statement.setInt(7, item.amount)
                statement.setString(8, IntentStatus.PREPARED.name)
                statement.setLong(9, now)
                statement.setLong(10, now)
                statement.executeUpdate()
            }
            Unit
        }
    }

    override fun commitSale(intentId: UUID, playerId: UUID, playerName: String, item: MarketItemRecord, priceMinor: Long, expireAt: Long): CompletableFuture<Unit> {
        return databaseManager.transactionAsync { connection ->
            val now = System.currentTimeMillis()
            connection.prepareStatement(
                "INSERT INTO gc_listings (listing_id, seller_uuid, seller_name, item_data, item_name, material_key, amount, price_minor, status, buyer_uuid, created_at, updated_at, expire_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, intentId.toString())
                statement.setString(2, playerId.toString())
                statement.setString(3, playerName)
                statement.setString(4, item.itemData)
                statement.setString(5, item.itemName)
                statement.setString(6, item.materialKey)
                statement.setInt(7, item.amount)
                statement.setLong(8, priceMinor)
                statement.setString(9, ListingStatus.ACTIVE.name)
                statement.setString(10, null)
                statement.setLong(11, now)
                statement.setLong(12, now)
                statement.setLong(13, expireAt)
                statement.executeUpdate()
            }
            updateSaleIntentStatus(connection, intentId, IntentStatus.COMMITTED)
            Unit
        }
    }

    override fun abortSale(intentId: UUID): CompletableFuture<Unit> = databaseManager.transactionAsync { connection ->
        updateSaleIntentStatus(connection, intentId, IntentStatus.ABORTED)
        Unit
    }

    override fun recoverSaleToMailbox(intentId: UUID): CompletableFuture<Boolean> = databaseManager.transactionAsync { connection ->
        val intent = findSaleIntent(connection, intentId) ?: return@transactionAsync false
        if (intent.status != IntentStatus.PREPARED) return@transactionAsync false
        insertMailbox(connection, UUID.randomUUID(), intent.playerId, intent.itemData, intent.itemName, intent.amount, intent.intentId, MailboxStatus.AVAILABLE)
        updateSaleIntentStatus(connection, intentId, IntentStatus.RECOVERED)
        true
    }

    override fun browse(query: ListingBrowseQuery): CompletableFuture<ListingPage> = databaseManager.supplyAsync { connection ->
        val filters = ListingSqlFilter.build(query)
        val totalCount = countBrowse(connection, filters)
        val offset = (query.page - 1) * query.pageSize
        val sql = buildString {
            append("SELECT listing_id, seller_uuid, seller_name, item_data, item_name, material_key, amount, price_minor, created_at, expire_at FROM gc_listings WHERE status = ?")
            append(filters.whereClause)
            append(" ORDER BY ")
            append(sortClause(query.sort))
            append(" LIMIT ? OFFSET ?")
        }
        connection.prepareStatement(sql).use { statement ->
            var index = 1
            statement.setString(index++, ListingStatus.ACTIVE.name)
            index = filters.bind(statement, index)
            statement.setInt(index++, query.pageSize)
            statement.setInt(index, offset)
            statement.executeQuery().use { resultSet ->
                ListingPage(mapListings(resultSet), totalCount, query.page, query.pageSize)
            }
        }
    }

    override fun expireListings(now: Long): CompletableFuture<Int> = databaseManager.transactionAsync { connection ->
        val expired = connection.prepareStatement(
            "SELECT listing_id, seller_uuid, seller_name, item_data, item_name, material_key, amount, price_minor, created_at, expire_at FROM gc_listings WHERE status = ? AND expire_at > 0 AND expire_at <= ? FOR UPDATE",
        ).use { statement ->
            statement.setString(1, ListingStatus.ACTIVE.name)
            statement.setLong(2, now)
            statement.executeQuery().use(::mapListings)
        }
        expired.forEach { listing ->
            insertMailbox(connection, UUID.randomUUID(), listing.sellerId, listing.itemData, listing.itemName, listing.amount, listing.listingId, MailboxStatus.AVAILABLE)
            connection.prepareStatement("UPDATE gc_listings SET status = ?, updated_at = ? WHERE listing_id = ?").use { statement ->
                statement.setString(1, ListingStatus.CANCELLED.name)
                statement.setLong(2, now)
                statement.setString(3, listing.listingId.toString())
                statement.executeUpdate()
            }
        }
        expired.size
    }

    override fun quotePurchase(listingPrefix: String, buyerId: UUID): CompletableFuture<PurchaseQuoteResult> {
        return databaseManager.transactionAsync { connection ->
            when (val resolved = resolveListingForUpdate(connection, listingPrefix, sellerId = null)) {
                is LookupResult.NotFound -> PurchaseQuoteResult.NotFound
                is LookupResult.Ambiguous -> PurchaseQuoteResult.Ambiguous
                is LookupResult.Found -> {
                    val listing = resolved.value
                    when {
                        listing.expireAt > 0L && listing.expireAt <= System.currentTimeMillis() -> PurchaseQuoteResult.Expired
                        listing.sellerId == buyerId -> PurchaseQuoteResult.OwnListing
                        else -> PurchaseQuoteResult.Success(listing)
                    }
                }
            }
        }
    }

    override fun purchaseListing(listingPrefix: String, buyerId: UUID, buyerName: String, sellerPayoutMinor: Long): CompletableFuture<PurchaseResult> {
        return databaseManager.transactionAsync { connection ->
            when (val resolved = resolveListingForUpdate(connection, listingPrefix, sellerId = null)) {
                is LookupResult.NotFound -> PurchaseResult.NotFound
                is LookupResult.Ambiguous -> PurchaseResult.Ambiguous
                is LookupResult.Found -> {
                    val listing = resolved.value
                    if (listing.expireAt > 0L && listing.expireAt <= System.currentTimeMillis()) {
                        val now = System.currentTimeMillis()
                        insertMailbox(connection, UUID.randomUUID(), listing.sellerId, listing.itemData, listing.itemName, listing.amount, listing.listingId, MailboxStatus.AVAILABLE)
                        connection.prepareStatement("UPDATE gc_listings SET status = ?, updated_at = ? WHERE listing_id = ?").use { statement ->
                            statement.setString(1, ListingStatus.CANCELLED.name)
                            statement.setLong(2, now)
                            statement.setString(3, listing.listingId.toString())
                            statement.executeUpdate()
                        }
                        return@transactionAsync PurchaseResult.Expired
                    }
                    if (listing.sellerId == buyerId) return@transactionAsync PurchaseResult.OwnListing
                    val now = System.currentTimeMillis()
                    insertMailbox(connection, UUID.randomUUID(), buyerId, listing.itemData, listing.itemName, listing.amount, listing.listingId, MailboxStatus.AVAILABLE)
                    insertPayout(connection, listing.listingId, listing.sellerId, listing.sellerName, sellerPayoutMinor, PayoutStatus.PENDING, now)
                    connection.prepareStatement("UPDATE gc_listings SET status = ?, buyer_uuid = ?, updated_at = ? WHERE listing_id = ?").use { statement ->
                        statement.setString(1, ListingStatus.SOLD.name)
                        statement.setString(2, buyerId.toString())
                        statement.setLong(3, now)
                        statement.setString(4, listing.listingId.toString())
                        statement.executeUpdate()
                    }
                    PurchaseResult.Success(listing)
                }
            }
        }
    }

    override fun cancelListing(listingPrefix: String, sellerId: UUID): CompletableFuture<CancelResult> {
        return databaseManager.transactionAsync { connection ->
            when (val resolved = resolveListingForUpdate(connection, listingPrefix, sellerId)) {
                is LookupResult.NotFound -> CancelResult.NotFound
                is LookupResult.Ambiguous -> CancelResult.Ambiguous
                is LookupResult.Found -> {
                    val listing = resolved.value
                    insertMailbox(connection, UUID.randomUUID(), sellerId, listing.itemData, listing.itemName, listing.amount, listing.listingId, MailboxStatus.AVAILABLE)
                    connection.prepareStatement("UPDATE gc_listings SET status = ?, updated_at = ? WHERE listing_id = ?").use { statement ->
                        statement.setString(1, ListingStatus.CANCELLED.name)
                        statement.setLong(2, System.currentTimeMillis())
                        statement.setString(3, listing.listingId.toString())
                        statement.executeUpdate()
                    }
                    CancelResult.Success(listing)
                }
            }
        }
    }

    override fun repriceListing(listingPrefix: String, sellerId: UUID, priceMinor: Long): CompletableFuture<RepriceResult> {
        return databaseManager.transactionAsync { connection ->
            when (val resolved = resolveListingForUpdate(connection, listingPrefix, sellerId)) {
                is LookupResult.NotFound -> RepriceResult.NotFound
                is LookupResult.Ambiguous -> RepriceResult.Ambiguous
                is LookupResult.Found -> {
                    val listing = resolved.value
                    val now = System.currentTimeMillis()
                    connection.prepareStatement("UPDATE gc_listings SET price_minor = ?, updated_at = ? WHERE listing_id = ?").use { statement ->
                        statement.setLong(1, priceMinor)
                        statement.setLong(2, now)
                        statement.setString(3, listing.listingId.toString())
                        statement.executeUpdate()
                    }
                    RepriceResult.Success(listing.copy(priceMinor = priceMinor))
                }
            }
        }
    }

    override fun listMailbox(playerId: UUID, page: Int, pageSize: Int): CompletableFuture<MailboxPage> = databaseManager.supplyAsync { connection ->
        val total = connection.prepareStatement("SELECT COUNT(*) FROM gc_mailbox WHERE player_uuid = ? AND status = ?").use { statement ->
            statement.setString(1, playerId.toString())
            statement.setString(2, MailboxStatus.AVAILABLE.name)
            statement.executeQuery().use { resultSet -> if (resultSet.next()) resultSet.getInt(1) else 0 }
        }
        val offset = (page - 1) * pageSize
        connection.prepareStatement(
            "SELECT claim_id, player_uuid, item_data, item_name, amount, reference_id FROM gc_mailbox WHERE player_uuid = ? AND status = ? ORDER BY created_at ASC LIMIT ? OFFSET ?",
        ).use { statement ->
            statement.setString(1, playerId.toString())
            statement.setString(2, MailboxStatus.AVAILABLE.name)
            statement.setInt(3, pageSize)
            statement.setInt(4, offset)
            statement.executeQuery().use { resultSet ->
                val entries = buildList {
                    while (resultSet.next()) add(mapMailboxEntry(resultSet))
                }
                MailboxPage(entries, total, page, pageSize)
            }
        }
    }

    override fun reserveMailbox(playerId: UUID, claimPrefix: String?): CompletableFuture<MailboxReservationResult> {
        return databaseManager.transactionAsync { connection ->
            when (val resolved = resolveMailboxForUpdate(connection, playerId, claimPrefix)) {
                is LookupResult.NotFound -> MailboxReservationResult.NotFound
                is LookupResult.Ambiguous -> MailboxReservationResult.Ambiguous
                is LookupResult.Found -> {
                    val entry = resolved.value
                    val deliveryIntentId = UUID.randomUUID()
                    val now = System.currentTimeMillis()
                    connection.prepareStatement(
                        "INSERT INTO gc_delivery_intents (intent_id, claim_id, player_uuid, item_data, item_name, amount, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    ).use { statement ->
                        statement.setString(1, deliveryIntentId.toString())
                        statement.setString(2, entry.claimId.toString())
                        statement.setString(3, playerId.toString())
                        statement.setString(4, entry.itemData)
                        statement.setString(5, entry.itemName)
                        statement.setInt(6, entry.amount)
                        statement.setString(7, IntentStatus.PREPARED.name)
                        statement.setLong(8, now)
                        statement.setLong(9, now)
                        statement.executeUpdate()
                    }
                    connection.prepareStatement("UPDATE gc_mailbox SET status = ?, updated_at = ? WHERE claim_id = ?").use { statement ->
                        statement.setString(1, MailboxStatus.RESERVED.name)
                        statement.setLong(2, now)
                        statement.setString(3, entry.claimId.toString())
                        statement.executeUpdate()
                    }
                    MailboxReservationResult.Success(entry, deliveryIntentId)
                }
            }
        }
    }

    override fun commitDelivery(intentId: UUID): CompletableFuture<Boolean> = databaseManager.transactionAsync { connection ->
        val intent = findDeliveryIntent(connection, intentId) ?: return@transactionAsync false
        if (intent.status != IntentStatus.PREPARED) return@transactionAsync false
        val now = System.currentTimeMillis()
        connection.prepareStatement("UPDATE gc_delivery_intents SET status = ?, updated_at = ? WHERE intent_id = ?").use { statement ->
            statement.setString(1, IntentStatus.COMMITTED.name)
            statement.setLong(2, now)
            statement.setString(3, intentId.toString())
            statement.executeUpdate()
        }
        connection.prepareStatement("UPDATE gc_mailbox SET status = ?, updated_at = ? WHERE claim_id = ?").use { statement ->
            statement.setString(1, MailboxStatus.CLAIMED.name)
            statement.setLong(2, now)
            statement.setString(3, intent.claimId.toString())
            statement.executeUpdate()
        }
        true
    }

    override fun abortDelivery(intentId: UUID): CompletableFuture<Boolean> = databaseManager.transactionAsync { connection ->
        val intent = findDeliveryIntent(connection, intentId) ?: return@transactionAsync false
        if (intent.status != IntentStatus.PREPARED) return@transactionAsync false
        val now = System.currentTimeMillis()
        connection.prepareStatement("UPDATE gc_delivery_intents SET status = ?, updated_at = ? WHERE intent_id = ?").use { statement ->
            statement.setString(1, IntentStatus.ABORTED.name)
            statement.setLong(2, now)
            statement.setString(3, intentId.toString())
            statement.executeUpdate()
        }
        connection.prepareStatement("UPDATE gc_mailbox SET status = ?, updated_at = ? WHERE claim_id = ?").use { statement ->
            statement.setString(1, MailboxStatus.AVAILABLE.name)
            statement.setLong(2, now)
            statement.setString(3, intent.claimId.toString())
            statement.executeUpdate()
        }
        true
    }

    override fun getBalance(playerId: UUID, playerName: String): CompletableFuture<Long> = databaseManager.transactionAsync { connection ->
        lockWallet(connection, playerId, playerName)
    }

    override fun grantBalance(playerId: UUID, playerName: String, delta: Long): CompletableFuture<Long> {
        return databaseManager.transactionAsync { connection ->
            val now = System.currentTimeMillis()
            val next = lockWallet(connection, playerId, playerName) + delta
            updateWallet(connection, playerId, playerName, next, now)
            insertLedger(connection, playerId, delta, "GRANT", UUID.randomUUID(), now)
            next
        }
    }

    override fun pendingPayouts(): CompletableFuture<List<PayoutRecord>> = databaseManager.supplyAsync { connection ->
        connection.prepareStatement(
            "SELECT reference_id, seller_uuid, seller_name, amount_minor, status FROM gc_payouts WHERE status = ? ORDER BY created_at ASC",
        ).use { statement ->
            statement.setString(1, PayoutStatus.PENDING.name)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) add(mapPayout(resultSet))
                }
            }
        }
    }

    override fun completePayout(referenceId: UUID): CompletableFuture<Boolean> = databaseManager.transactionAsync { connection ->
        connection.prepareStatement("UPDATE gc_payouts SET status = ?, updated_at = ? WHERE reference_id = ? AND status = ?").use { statement ->
            statement.setString(1, PayoutStatus.PAID.name)
            statement.setLong(2, System.currentTimeMillis())
            statement.setString(3, referenceId.toString())
            statement.setString(4, PayoutStatus.PENDING.name)
            statement.executeUpdate() > 0
        }
    }

    override fun preparedSales(playerId: UUID): CompletableFuture<List<SaleIntentRecord>> = databaseManager.supplyAsync { connection ->
        connection.prepareStatement("SELECT intent_id, player_uuid, player_name, price_minor, item_data, item_name, amount, status FROM gc_sale_intents WHERE player_uuid = ? AND status = ?").use { statement ->
            statement.setString(1, playerId.toString())
            statement.setString(2, IntentStatus.PREPARED.name)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) add(mapSaleIntent(resultSet))
                }
            }
        }
    }

    override fun preparedDeliveries(playerId: UUID): CompletableFuture<List<DeliveryIntentRecord>> = databaseManager.supplyAsync { connection ->
        connection.prepareStatement("SELECT intent_id, claim_id, player_uuid, item_data, item_name, amount, status FROM gc_delivery_intents WHERE player_uuid = ? AND status = ?").use { statement ->
            statement.setString(1, playerId.toString())
            statement.setString(2, IntentStatus.PREPARED.name)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) add(mapDeliveryIntent(resultSet))
                }
            }
        }
    }

    override fun close() {
        databaseManager.close()
    }

    private fun countBrowse(connection: Connection, filter: ListingSqlFilter): Int {
        val sql = "SELECT COUNT(*) FROM gc_listings WHERE status = ?${filter.whereClause}"
        connection.prepareStatement(sql).use { statement ->
            var index = 1
            statement.setString(index++, ListingStatus.ACTIVE.name)
            index = filter.bind(statement, index)
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) resultSet.getInt(1) else 0
            }
        }
    }

    private fun sortClause(sort: ListingSort): String {
        return when (sort) {
            ListingSort.NEWEST -> "created_at DESC"
            ListingSort.PRICE_LOW -> "price_minor ASC, created_at DESC"
            ListingSort.PRICE_HIGH -> "price_minor DESC, created_at DESC"
            ListingSort.EXPIRING -> "CASE WHEN expire_at = 0 THEN 1 ELSE 0 END ASC, expire_at ASC, created_at DESC"
        }
    }

    private fun resolveListingForUpdate(connection: Connection, prefix: String, sellerId: UUID?): LookupResult<ListingSummary> {
        val sql = buildString {
            append("SELECT listing_id, seller_uuid, seller_name, item_data, item_name, material_key, amount, price_minor, created_at, expire_at FROM gc_listings WHERE listing_id LIKE ? AND status = ? AND (expire_at = 0 OR expire_at > ?)")
            if (sellerId != null) append(" AND seller_uuid = ?")
            append(" ORDER BY created_at ASC LIMIT 2 FOR UPDATE")
        }
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, "$prefix%")
            statement.setString(2, ListingStatus.ACTIVE.name)
            statement.setLong(3, System.currentTimeMillis())
            if (sellerId != null) statement.setString(4, sellerId.toString())
            statement.executeQuery().use { resultSet ->
                val listings = mapListings(resultSet)
                return when {
                    listings.isEmpty() -> LookupResult.NotFound
                    listings.size > 1 -> LookupResult.Ambiguous
                    else -> LookupResult.Found(listings.first())
                }
            }
        }
    }

    private fun resolveMailboxForUpdate(connection: Connection, playerId: UUID, prefix: String?): LookupResult<MailboxEntry> {
        val sql = buildString {
            append("SELECT claim_id, player_uuid, item_data, item_name, amount, reference_id FROM gc_mailbox WHERE player_uuid = ? AND status = ?")
            if (!prefix.isNullOrBlank()) append(" AND claim_id LIKE ?")
            append(" ORDER BY created_at ASC LIMIT 2 FOR UPDATE")
        }
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, playerId.toString())
            statement.setString(2, MailboxStatus.AVAILABLE.name)
            if (!prefix.isNullOrBlank()) statement.setString(3, "$prefix%")
            statement.executeQuery().use { resultSet ->
                val entries = buildList {
                    while (resultSet.next()) add(mapMailboxEntry(resultSet))
                }
                return when {
                    entries.isEmpty() -> LookupResult.NotFound
                    entries.size > 1 -> LookupResult.Ambiguous
                    else -> LookupResult.Found(entries.first())
                }
            }
        }
    }

    private fun lockWallets(connection: Connection, first: Pair<UUID, String>, second: Pair<UUID, String>): Map<UUID, Long> {
        val ordered = listOf(first, second).sortedBy { it.first.toString() }
        val balances = linkedMapOf<UUID, Long>()
        ordered.forEach { (playerId, playerName) -> balances[playerId] = lockWallet(connection, playerId, playerName) }
        return balances
    }

    private fun lockWallet(connection: Connection, playerId: UUID, playerName: String): Long {
        connection.prepareStatement("SELECT balance FROM gc_wallets WHERE player_uuid = ? FOR UPDATE").use { statement ->
            statement.setString(1, playerId.toString())
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) return resultSet.getLong("balance")
            }
        }
        connection.prepareStatement("INSERT INTO gc_wallets (player_uuid, player_name, balance, updated_at) VALUES (?, ?, ?, ?)").use { statement ->
            statement.setString(1, playerId.toString())
            statement.setString(2, playerName)
            statement.setLong(3, 0L)
            statement.setLong(4, System.currentTimeMillis())
            statement.executeUpdate()
        }
        return 0L
    }

    private fun updateWallet(connection: Connection, playerId: UUID, playerName: String, balance: Long, timestamp: Long) {
        connection.prepareStatement("UPDATE gc_wallets SET player_name = ?, balance = ?, updated_at = ? WHERE player_uuid = ?").use { statement ->
            statement.setString(1, playerName)
            statement.setLong(2, balance)
            statement.setLong(3, timestamp)
            statement.setString(4, playerId.toString())
            statement.executeUpdate()
        }
    }

    private fun insertLedger(connection: Connection, playerId: UUID, delta: Long, reason: String, referenceId: UUID, createdAt: Long) {
        connection.prepareStatement("INSERT INTO gc_wallet_ledger (entry_id, player_uuid, delta, reason, reference_id, created_at) VALUES (?, ?, ?, ?, ?, ?)").use { statement ->
            statement.setString(1, UUID.randomUUID().toString())
            statement.setString(2, playerId.toString())
            statement.setLong(3, delta)
            statement.setString(4, reason)
            statement.setString(5, referenceId.toString())
            statement.setLong(6, createdAt)
            statement.executeUpdate()
        }
    }

    private fun insertMailbox(
        connection: Connection,
        claimId: UUID,
        playerId: UUID,
        itemData: String,
        itemName: String,
        amount: Int,
        referenceId: UUID,
        status: MailboxStatus,
    ) {
        val now = System.currentTimeMillis()
        connection.prepareStatement("INSERT INTO gc_mailbox (claim_id, player_uuid, item_data, item_name, amount, reference_id, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)").use { statement ->
            statement.setString(1, claimId.toString())
            statement.setString(2, playerId.toString())
            statement.setString(3, itemData)
            statement.setString(4, itemName)
            statement.setInt(5, amount)
            statement.setString(6, referenceId.toString())
            statement.setString(7, status.name)
            statement.setLong(8, now)
            statement.setLong(9, now)
            statement.executeUpdate()
        }
    }

    private fun insertPayout(
        connection: Connection,
        referenceId: UUID,
        sellerId: UUID,
        sellerName: String,
        amountMinor: Long,
        status: PayoutStatus,
        now: Long,
    ) {
        connection.prepareStatement(
            "INSERT INTO gc_payouts (reference_id, seller_uuid, seller_name, amount_minor, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, referenceId.toString())
            statement.setString(2, sellerId.toString())
            statement.setString(3, sellerName)
            statement.setLong(4, amountMinor)
            statement.setString(5, status.name)
            statement.setLong(6, now)
            statement.setLong(7, now)
            statement.executeUpdate()
        }
    }

    private fun updateSaleIntentStatus(connection: Connection, intentId: UUID, status: IntentStatus) {
        connection.prepareStatement("UPDATE gc_sale_intents SET status = ?, updated_at = ? WHERE intent_id = ?").use { statement ->
            statement.setString(1, status.name)
            statement.setLong(2, System.currentTimeMillis())
            statement.setString(3, intentId.toString())
            statement.executeUpdate()
        }
    }

    private fun findSaleIntent(connection: Connection, intentId: UUID): SaleIntentRecord? {
        connection.prepareStatement("SELECT intent_id, player_uuid, player_name, price_minor, item_data, item_name, amount, status FROM gc_sale_intents WHERE intent_id = ? FOR UPDATE").use { statement ->
            statement.setString(1, intentId.toString())
            statement.executeQuery().use { resultSet -> return if (resultSet.next()) mapSaleIntent(resultSet) else null }
        }
    }

    private fun findDeliveryIntent(connection: Connection, intentId: UUID): DeliveryIntentRecord? {
        connection.prepareStatement("SELECT intent_id, claim_id, player_uuid, item_data, item_name, amount, status FROM gc_delivery_intents WHERE intent_id = ? FOR UPDATE").use { statement ->
            statement.setString(1, intentId.toString())
            statement.executeQuery().use { resultSet -> return if (resultSet.next()) mapDeliveryIntent(resultSet) else null }
        }
    }

    private fun mapListings(resultSet: ResultSet): List<ListingSummary> = buildList {
        while (resultSet.next()) add(mapListing(resultSet))
    }

    private fun mapListing(resultSet: ResultSet) = ListingSummary(
        listingId = UUID.fromString(resultSet.getString("listing_id")),
        sellerId = UUID.fromString(resultSet.getString("seller_uuid")),
        sellerName = resultSet.getString("seller_name"),
        itemName = resultSet.getString("item_name"),
        materialKey = resultSet.getString("material_key"),
        amount = resultSet.getInt("amount"),
        priceMinor = resultSet.getLong("price_minor"),
        itemData = resultSet.getString("item_data"),
        createdAt = resultSet.getLong("created_at"),
        expireAt = resultSet.getLong("expire_at"),
    )

    private fun mapMailboxEntry(resultSet: ResultSet) = MailboxEntry(
        claimId = UUID.fromString(resultSet.getString("claim_id")),
        playerId = UUID.fromString(resultSet.getString("player_uuid")),
        itemData = resultSet.getString("item_data"),
        itemName = resultSet.getString("item_name"),
        amount = resultSet.getInt("amount"),
        referenceId = UUID.fromString(resultSet.getString("reference_id")),
    )

    private fun mapSaleIntent(resultSet: ResultSet) = SaleIntentRecord(
        intentId = UUID.fromString(resultSet.getString("intent_id")),
        playerId = UUID.fromString(resultSet.getString("player_uuid")),
        playerName = resultSet.getString("player_name"),
        priceMinor = resultSet.getLong("price_minor"),
        itemData = resultSet.getString("item_data"),
        itemName = resultSet.getString("item_name"),
        amount = resultSet.getInt("amount"),
        status = IntentStatus.valueOf(resultSet.getString("status")),
    )

    private fun mapDeliveryIntent(resultSet: ResultSet) = DeliveryIntentRecord(
        intentId = UUID.fromString(resultSet.getString("intent_id")),
        claimId = UUID.fromString(resultSet.getString("claim_id")),
        playerId = UUID.fromString(resultSet.getString("player_uuid")),
        itemData = resultSet.getString("item_data"),
        itemName = resultSet.getString("item_name"),
        amount = resultSet.getInt("amount"),
        status = IntentStatus.valueOf(resultSet.getString("status")),
    )

    private fun mapPayout(resultSet: ResultSet) = PayoutRecord(
        referenceId = UUID.fromString(resultSet.getString("reference_id")),
        sellerId = UUID.fromString(resultSet.getString("seller_uuid")),
        sellerName = resultSet.getString("seller_name"),
        amountMinor = resultSet.getLong("amount_minor"),
        status = PayoutStatus.valueOf(resultSet.getString("status")),
    )
}

private class ListingSqlFilter private constructor(
    val whereClause: String,
    private val parameters: List<Any>,
) {

    fun bind(statement: java.sql.PreparedStatement, startIndex: Int): Int {
        var index = startIndex
        parameters.forEach { value ->
            when (value) {
                is String -> statement.setString(index++, value)
                is Long -> statement.setLong(index++, value)
                else -> statement.setObject(index++, value)
            }
        }
        return index
    }

    companion object {
        fun build(query: ListingBrowseQuery): ListingSqlFilter {
            val where = StringBuilder()
            val parameters = mutableListOf<Any>()
            where.append(" AND (expire_at = 0 OR expire_at > ?)")
            parameters += query.asOf
            query.sellerId?.let {
                where.append(" AND seller_uuid = ?")
                parameters += it.toString()
            }
            query.searchTerm?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()?.let { search ->
                where.append(" AND (LOWER(item_name) LIKE ? OR LOWER(seller_name) LIKE ? OR LOWER(material_key) LIKE ?)")
                repeat(3) { parameters += "%$search%" }
            }
            if (query.categoryPrefixes.isNotEmpty()) {
                where.append(" AND (")
                where.append(query.categoryPrefixes.joinToString(" OR ") { "LOWER(material_key) LIKE ?" })
                where.append(")")
                query.categoryPrefixes.forEach { parameters += "${it.lowercase()}%" }
            }
            return ListingSqlFilter(where.toString(), parameters)
        }
    }
}
