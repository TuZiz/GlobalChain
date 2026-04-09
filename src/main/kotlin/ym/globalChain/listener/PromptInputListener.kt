package ym.globalchain.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerQuitEvent
import ym.globalchain.GlobalChain

class PromptInputListener(private val plugin: GlobalChain) : Listener {

    @EventHandler(ignoreCancelled = true)
    fun onChat(event: AsyncPlayerChatEvent) {
        val consumed = plugin.runtime()?.marketGuiService?.consumeChat(event.player, event.message) ?: false
        if (consumed) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        plugin.runtime()?.marketGuiService?.cleanup(event.player.uniqueId)
    }
}
