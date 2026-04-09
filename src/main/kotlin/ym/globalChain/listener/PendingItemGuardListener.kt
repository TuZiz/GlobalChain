package ym.globalchain.listener

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import ym.globalchain.GlobalChain
import ym.globalchain.util.TrackedItemService

class PendingItemGuardListener(
    private val plugin: GlobalChain,
    private val trackedItemService: TrackedItemService,
) : Listener {

    @EventHandler(ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val hotbarItem = if (event.hotbarButton >= 0 && event.hotbarButton < event.whoClicked.inventory.size) {
            event.whoClicked.inventory.getItem(event.hotbarButton)
        } else {
            null
        }
        if (trackedItemService.isTracked(event.currentItem) || trackedItemService.isTracked(event.cursor) || trackedItemService.isTracked(hotbarItem)) {
            event.isCancelled = true
            warn(event.whoClicked as? Player)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (trackedItemService.isTracked(event.oldCursor)) {
            event.isCancelled = true
            warn(event.whoClicked as? Player)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (trackedItemService.isTracked(event.itemDrop.itemStack)) {
            event.isCancelled = true
            warn(event.player)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onSwap(event: PlayerSwapHandItemsEvent) {
        if (trackedItemService.isTracked(event.mainHandItem) || trackedItemService.isTracked(event.offHandItem)) {
            event.isCancelled = true
            warn(event.player)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (trackedItemService.isTracked(event.item)) {
            event.isCancelled = true
            warn(event.player)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (trackedItemService.isTracked(event.itemInHand)) {
            event.isCancelled = true
            warn(event.player)
        }
    }

    private fun warn(player: Player?) {
        if (player != null) {
            plugin.sendMessage(player, "messages.tracked-item-locked")
        }
    }
}
