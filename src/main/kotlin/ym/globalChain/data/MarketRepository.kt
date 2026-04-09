package ym.globalchain.data

import ym.globalchain.model.CancelResult
import ym.globalchain.model.DeliveryIntentRecord
import ym.globalchain.model.ListingBrowseQuery
import ym.globalchain.model.ListingPage
import ym.globalchain.model.MarketItemRecord
import ym.globalchain.model.MailboxPage
import ym.globalchain.model.MailboxReservationResult
import ym.globalchain.model.PayoutRecord
import ym.globalchain.model.PurchaseResult
import ym.globalchain.model.PurchaseQuoteResult
import ym.globalchain.model.RepriceResult
import ym.globalchain.model.SaleIntentRecord
import java.util.UUID
import java.util.concurrent.CompletableFuture

interface MarketRepository : AutoCloseable {

    fun countActiveListings(playerId: UUID): CompletableFuture<Int>

    fun createSaleIntent(intentId: UUID, playerId: UUID, playerName: String, priceMinor: Long, item: MarketItemRecord): CompletableFuture<Unit>

    fun commitSale(intentId: UUID, playerId: UUID, playerName: String, item: MarketItemRecord, priceMinor: Long, expireAt: Long): CompletableFuture<Unit>

    fun abortSale(intentId: UUID): CompletableFuture<Unit>

    fun recoverSaleToMailbox(intentId: UUID): CompletableFuture<Boolean>

    fun browse(query: ListingBrowseQuery): CompletableFuture<ListingPage>

    fun expireListings(now: Long): CompletableFuture<Int>

    fun quotePurchase(listingPrefix: String, buyerId: UUID): CompletableFuture<PurchaseQuoteResult>

    fun purchaseListing(listingPrefix: String, buyerId: UUID, buyerName: String, sellerPayoutMinor: Long): CompletableFuture<PurchaseResult>

    fun cancelListing(listingPrefix: String, sellerId: UUID): CompletableFuture<CancelResult>

    fun repriceListing(listingPrefix: String, sellerId: UUID, priceMinor: Long): CompletableFuture<RepriceResult>

    fun listMailbox(playerId: UUID, page: Int, pageSize: Int): CompletableFuture<MailboxPage>

    fun reserveMailbox(playerId: UUID, claimPrefix: String?): CompletableFuture<MailboxReservationResult>

    fun commitDelivery(intentId: UUID): CompletableFuture<Boolean>

    fun abortDelivery(intentId: UUID): CompletableFuture<Boolean>

    fun getBalance(playerId: UUID, playerName: String): CompletableFuture<Long>

    fun grantBalance(playerId: UUID, playerName: String, delta: Long): CompletableFuture<Long>

    fun pendingPayouts(): CompletableFuture<List<PayoutRecord>>

    fun completePayout(referenceId: UUID): CompletableFuture<Boolean>

    fun preparedSales(playerId: UUID): CompletableFuture<List<SaleIntentRecord>>

    fun preparedDeliveries(playerId: UUID): CompletableFuture<List<DeliveryIntentRecord>>

    override fun close() = Unit
}
