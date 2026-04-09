package ym.globalchain.util

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ym.globalchain.model.TrackedFlow
import ym.globalchain.model.TrackedItemMarker
import ym.globalchain.model.TrackedInventoryItem
import java.util.UUID

class TrackedItemService(private val keys: MarketKeys) {

    fun apply(item: ItemStack, flow: TrackedFlow, intentId: UUID, token: UUID): ItemStack {
        if (item.type == Material.AIR) {
            return item
        }
        val meta = item.itemMeta ?: return item
        meta.persistentDataContainer.set(keys.flow, PersistentDataType.STRING, flow.id)
        meta.persistentDataContainer.set(keys.intentId, PersistentDataType.STRING, intentId.toString())
        meta.persistentDataContainer.set(keys.token, PersistentDataType.STRING, token.toString())
        item.itemMeta = meta
        return item
    }

    fun clear(item: ItemStack): ItemStack {
        if (item.type == Material.AIR) {
            return item
        }
        val meta = item.itemMeta ?: return item
        meta.persistentDataContainer.remove(keys.flow)
        meta.persistentDataContainer.remove(keys.intentId)
        meta.persistentDataContainer.remove(keys.token)
        item.itemMeta = meta
        return item
    }

    fun marker(item: ItemStack?): TrackedItemMarker? {
        if (item == null || item.type == Material.AIR) {
            return null
        }
        val meta = item.itemMeta ?: return null
        val flow = meta.persistentDataContainer.get(keys.flow, PersistentDataType.STRING) ?: return null
        val intentId = meta.persistentDataContainer.get(keys.intentId, PersistentDataType.STRING) ?: return null
        val token = meta.persistentDataContainer.get(keys.token, PersistentDataType.STRING) ?: return null
        return TrackedItemMarker(
            flow = TrackedFlow.fromId(flow) ?: return null,
            intentId = UUID.fromString(intentId),
            token = UUID.fromString(token),
        )
    }

    fun isTracked(item: ItemStack?): Boolean = marker(item) != null

    fun scan(player: Player): List<TrackedInventoryItem> {
        return player.inventory.contents.mapIndexedNotNull { slot, item ->
            val marker = marker(item) ?: return@mapIndexedNotNull null
            TrackedInventoryItem(slot = slot, item = item!!.clone(), marker = marker)
        }
    }

    fun stripIntent(player: Player, intentId: UUID) {
        player.inventory.contents.forEachIndexed { slot, item ->
            val marker = marker(item) ?: return@forEachIndexed
            if (marker.intentId == intentId) {
                player.inventory.setItem(slot, clear(item!!.clone()))
            }
        }
    }

    fun removeIntentItem(player: Player, intentId: UUID): ItemStack? {
        player.inventory.contents.forEachIndexed { slot, item ->
            val marker = marker(item) ?: return@forEachIndexed
            if (marker.intentId == intentId) {
                player.inventory.setItem(slot, null)
                return item!!.clone()
            }
        }
        return null
    }
}
