package ym.globalchain.util

import org.bukkit.NamespacedKey
import org.bukkit.plugin.Plugin

class MarketKeys(plugin: Plugin) {
    val flow = NamespacedKey(plugin, "tracked_flow")
    val intentId = NamespacedKey(plugin, "tracked_intent_id")
    val token = NamespacedKey(plugin, "tracked_token")
}
