package ym.globalchain.config

import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object ResourceBootstrap {

    private val defaultResources = listOf(
        "config.yml",
        "lang/zh_cn.yml",
        "menus/loading.yml",
        "menus/market-global.yml",
        "menus/market-mine.yml",
        "menus/market-mailbox.yml",
        "menus/market-filter.yml",
        "menus/market-sort.yml",
        "menus/market-sell-confirm.yml",
        "menus/market-mine-edit.yml",
    )

    fun ensureDefaults(plugin: JavaPlugin, dataFolder: Path) {
        Files.createDirectories(dataFolder)
        defaultResources.forEach { resource ->
            ensureResource(plugin, dataFolder, resource)
        }
    }

    private fun ensureResource(plugin: JavaPlugin, dataFolder: Path, resourcePath: String) {
        val target = dataFolder.resolve(resourcePath)
        Files.createDirectories(target.parent)
        if (Files.exists(target)) {
            return
        }
        plugin.getResource(resourcePath).use { input ->
            requireNotNull(input) { "Missing bundled resource: $resourcePath" }
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
