package ym.globalchain.util

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import java.util.Locale

object ItemComponents {

    fun fromMaterialKey(materialKey: String, fallback: String): Component {
        val material = resolveMaterial(materialKey) ?: return fallbackComponent(fallback)
        return displayComponent(material, fallback)
    }

    fun fromMaterial(material: Material, fallback: String): Component {
        return displayComponent(material, fallback)
    }

    fun fromItemStack(item: ItemStack, fallback: String): Component {
        val meta = item.itemMeta
        if (meta != null) {
            if (meta.hasDisplayName()) {
                return Component.text(meta.displayName)
            }
            if (meta.hasItemName()) {
                val translationKey = item.translationKey.ifBlank { item.type.translationKey }
                return if (shouldUseTranslation(item.type, translationKey, meta.itemName)) {
                    Component.translatable(translationKey)
                } else {
                    Component.text(meta.itemName)
                }
            }
        }
        val translationKey = item.translationKey
        return if (translationKey.isNotBlank()) {
            Component.translatable(translationKey)
        } else {
            fromMaterial(item.type, fallback)
        }
    }

    private fun displayComponent(material: Material, fallback: String): Component {
        if (fallback.isBlank()) {
            return Component.translatable(material.translationKey)
        }
        return if (shouldUseTranslation(material, material.translationKey, fallback)) {
            Component.translatable(material.translationKey)
        } else {
            Component.text(fallback)
        }
    }

    private fun resolveMaterial(materialKey: String): Material? {
        return Material.matchMaterial(materialKey)
            ?: Material.matchMaterial(materialKey.substringAfter(':'))
            ?: runCatching { Material.valueOf(materialKey.substringAfter(':').uppercase(Locale.ROOT)) }.getOrNull()
    }

    private fun fallbackComponent(fallback: String): Component {
        return Component.text(fallback.ifBlank { "Unknown Item" })
    }

    private fun shouldUseTranslation(material: Material, translationKey: String, value: String): Boolean {
        if (value.isBlank()) {
            return true
        }
        return normalize(value) in genericCandidates(material, translationKey)
    }

    private fun genericCandidates(material: Material, translationKey: String): Set<String> {
        return buildSet {
            add(normalize(material.key.toString()))
            add(normalize(material.key.key))
            add(normalize(translationKey))
            add(
                normalize(
                    material.name.lowercase(Locale.ROOT)
                        .split('_')
                        .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase(Locale.ROOT) } },
                ),
            )
        }
    }

    private fun normalize(value: String): String {
        return value.lowercase(Locale.ROOT)
            .replace("minecraft:", "")
            .replace("block.", "")
            .replace("item.", "")
            .replace('_', ' ')
            .replace('.', ' ')
            .trim()
    }
}
