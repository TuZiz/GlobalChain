package ym.globalchain.util

import org.bukkit.inventory.ItemStack

object ItemNaming {

    fun name(item: ItemStack): String {
        val meta = item.itemMeta
        if (meta != null && meta.hasDisplayName()) {
            return meta.displayName
        }
        return item.type.key.toString()
    }
}
