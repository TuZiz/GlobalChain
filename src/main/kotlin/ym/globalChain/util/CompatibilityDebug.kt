package ym.globalchain.util

import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.security.MessageDigest

object CompatibilityDebug {

    fun logSnapshot(plugin: JavaPlugin, enabled: Boolean, stage: String, item: ItemStack, encoded: String) {
        if (!enabled) {
            return
        }
        val meta = item.itemMeta
        val pdcKeys = meta?.persistentDataContainer?.keys?.joinToString(",") { it.toString() }.orEmpty().ifBlank { "-" }
        val customName = meta?.displayName?.takeIf { it.isNotBlank() } ?: meta?.itemName?.takeIf { it.isNotBlank() } ?: "-"
        plugin.logger.info(
            "[CompatDebug] stage=$stage type=${item.type} amount=${item.amount} encodedLength=${encoded.length} " +
                "hash=${shortHash(encoded)} pdcKeys=$pdcKeys customName=$customName",
        )
    }

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }
}
