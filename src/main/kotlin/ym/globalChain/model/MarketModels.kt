package ym.globalchain.model

import java.util.UUID

enum class ListingStatus {
    ACTIVE,
    RESERVED,
    SOLD,
    CANCELLED,
}

enum class MailboxStatus {
    AVAILABLE,
    RESERVED,
    CLAIMED,
}

enum class IntentStatus {
    PREPARED,
    COMMITTED,
    ABORTED,
    RECOVERED,
}

enum class PayoutStatus {
    PENDING,
    PAID,
}

enum class ListingSort {
    NEWEST,
    PRICE_LOW,
    PRICE_HIGH,
    EXPIRING,
}

data class ListingSummary(
    val listingId: UUID,
    val sellerId: UUID,
    val sellerName: String,
    val itemName: String,
    val materialKey: String,
    val amount: Int,
    val priceMinor: Long,
    val itemData: String,
    val createdAt: Long,
    val expireAt: Long,
)

data class MarketItemRecord(
    val itemData: String,
    val itemName: String,
    val materialKey: String,
    val amount: Int,
)

data class SaleDraft(
    val itemData: String,
    val itemName: String,
    val materialKey: String,
    val amount: Int,
    val priceMinor: Long? = null,
)

data class ListingBrowseQuery(
    val page: Int,
    val pageSize: Int,
    val sellerId: UUID? = null,
    val searchTerm: String? = null,
    val categoryPrefixes: List<String> = emptyList(),
    val sort: ListingSort = ListingSort.NEWEST,
    val asOf: Long = System.currentTimeMillis(),
)

data class ListingPage(
    val entries: List<ListingSummary>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int,
)

data class MailboxEntry(
    val claimId: UUID,
    val playerId: UUID,
    val itemData: String,
    val itemName: String,
    val amount: Int,
    val referenceId: UUID,
)

data class MailboxPage(
    val entries: List<MailboxEntry>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int,
)

data class SaleIntentRecord(
    val intentId: UUID,
    val playerId: UUID,
    val playerName: String,
    val priceMinor: Long,
    val itemData: String,
    val itemName: String,
    val amount: Int,
    val status: IntentStatus,
)

data class DeliveryIntentRecord(
    val intentId: UUID,
    val claimId: UUID,
    val playerId: UUID,
    val itemData: String,
    val itemName: String,
    val amount: Int,
    val status: IntentStatus,
)

data class PayoutRecord(
    val referenceId: UUID,
    val sellerId: UUID,
    val sellerName: String,
    val amountMinor: Long,
    val status: PayoutStatus,
)

sealed interface PurchaseQuoteResult {
    data class Success(val listing: ListingSummary) : PurchaseQuoteResult
    data object NotFound : PurchaseQuoteResult
    data object Ambiguous : PurchaseQuoteResult
    data object OwnListing : PurchaseQuoteResult
    data object Expired : PurchaseQuoteResult
}

sealed interface LookupResult<out T> {
    data class Found<T>(val value: T) : LookupResult<T>
    data object NotFound : LookupResult<Nothing>
    data object Ambiguous : LookupResult<Nothing>
}

sealed interface PurchaseResult {
    data class Success(val listing: ListingSummary) : PurchaseResult
    data object NotFound : PurchaseResult
    data object Ambiguous : PurchaseResult
    data class InsufficientFunds(val required: Long) : PurchaseResult
    data object OwnListing : PurchaseResult
    data object Expired : PurchaseResult
}

sealed interface CancelResult {
    data class Success(val listing: ListingSummary) : CancelResult
    data object NotFound : CancelResult
    data object Ambiguous : CancelResult
}

sealed interface RepriceResult {
    data class Success(val listing: ListingSummary) : RepriceResult
    data object NotFound : RepriceResult
    data object Ambiguous : RepriceResult
}

sealed interface MailboxReservationResult {
    data class Success(val entry: MailboxEntry, val deliveryIntentId: UUID) : MailboxReservationResult
    data object NotFound : MailboxReservationResult
    data object Ambiguous : MailboxReservationResult
}
