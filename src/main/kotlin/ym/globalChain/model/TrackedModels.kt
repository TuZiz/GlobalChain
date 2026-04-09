package ym.globalchain.model

import org.bukkit.inventory.ItemStack
import java.util.UUID

enum class TrackedFlow(val id: String) {
    SALE("sale"),
    DELIVERY("delivery");

    companion object {
        fun fromId(id: String): TrackedFlow? = entries.firstOrNull { it.id == id }
    }
}

data class TrackedItemMarker(
    val flow: TrackedFlow,
    val intentId: UUID,
    val token: UUID,
)

data class TrackedInventoryItem(
    val slot: Int,
    val item: ItemStack,
    val marker: TrackedItemMarker,
)
