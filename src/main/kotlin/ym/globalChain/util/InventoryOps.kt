package ym.globalchain.util

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ym.globalchain.model.TrackedInventoryItem
import java.util.UUID

object InventoryOps {

    fun stageMainHandSale(player: Player, trackedItemService: TrackedItemService, intentId: UUID, token: UUID): ItemStack? {
        val current = player.inventory.itemInMainHand
        if (current.type == Material.AIR || current.amount <= 0) {
            return null
        }
        if (trackedItemService.isTracked(current)) {
            return null
        }
        val tagged = current.clone()
        trackedItemService.apply(tagged, ym.globalchain.model.TrackedFlow.SALE, intentId, token)
        player.inventory.setItemInMainHand(tagged)
        return tagged.clone()
    }

    fun rollbackSaleStage(player: Player, trackedItemService: TrackedItemService, intentId: UUID) {
        trackedItemService.stripIntent(player, intentId)
    }

    fun removeStagedSale(player: Player, trackedItemService: TrackedItemService, intentId: UUID): ItemStack? {
        return trackedItemService.removeIntentItem(player, intentId)
    }

    fun canFit(player: Player, item: ItemStack): Boolean {
        var remaining = item.amount
        player.inventory.storageContents.forEach { current ->
            if (current == null || current.type == Material.AIR) {
                remaining -= item.maxStackSize.coerceAtMost(remaining)
            } else if (current.isSimilar(item)) {
                remaining -= (current.maxStackSize - current.amount).coerceAtLeast(0).coerceAtMost(remaining)
            }
            if (remaining <= 0) {
                return true
            }
        }
        return remaining <= 0
    }

    fun addClaimedItem(player: Player, item: ItemStack): Boolean {
        val leftovers = player.inventory.addItem(item)
        return leftovers.isEmpty()
    }

    fun trackedItems(player: Player, trackedItemService: TrackedItemService): List<TrackedInventoryItem> {
        return trackedItemService.scan(player)
    }
}
