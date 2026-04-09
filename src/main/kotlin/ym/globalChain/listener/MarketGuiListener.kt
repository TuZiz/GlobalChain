package ym.globalchain.listener

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import ym.globalchain.GlobalChain
import ym.globalchain.gui.MarketInventoryHolder

class MarketGuiListener(private val plugin: GlobalChain) : Listener {

    @EventHandler(ignoreCancelled = true)
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? MarketInventoryHolder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        plugin.runtime()?.marketGuiService?.handleClick(player, holder.id, event.rawSlot)
    }

    @EventHandler(ignoreCancelled = true)
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is MarketInventoryHolder) {
            event.isCancelled = true
        }
    }
}
