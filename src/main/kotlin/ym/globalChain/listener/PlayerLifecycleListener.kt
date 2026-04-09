package ym.globalchain.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import ym.globalchain.GlobalChain

class PlayerLifecycleListener(private val plugin: GlobalChain) : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        plugin.runtime()?.recoveryService?.reconcilePlayer(event.player)
    }
}
