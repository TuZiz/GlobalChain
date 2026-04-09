package ym.globalchain.lang

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Path

class LanguageService private constructor(private val yaml: YamlConfiguration) {

    private val miniMessage = MiniMessage.miniMessage()
    private val legacySerializer = LegacyComponentSerializer.builder()
        .character('§')
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .build()
    private val plainTextSerializer = PlainTextComponentSerializer.plainText()
    private val prefixTemplate = yaml.getString("prefix").orEmpty().ifBlank { "<gray>[GlobalChain]</gray> " }
    private val prefixComponent = miniMessage.deserialize(prefixTemplate)

    fun renderLines(
        path: String,
        placeholders: Map<String, String> = emptyMap(),
        componentPlaceholders: Map<String, Component> = emptyMap(),
    ): List<Component> {
        val list = yaml.getStringList(path)
        return if (list.isNotEmpty()) {
            list.map { renderTemplate(it, placeholders, componentPlaceholders) }
        } else {
            listOf(render(path, placeholders, componentPlaceholders))
        }
    }

    fun render(
        path: String,
        placeholders: Map<String, String> = emptyMap(),
        componentPlaceholders: Map<String, Component> = emptyMap(),
    ): Component {
        val template = yaml.getString(path) ?: "<prefix><red>Missing language key: $path</red>"
        return renderTemplate(template, placeholders, componentPlaceholders)
    }

    fun renderRaw(
        template: String,
        placeholders: Map<String, String> = emptyMap(),
        componentPlaceholders: Map<String, Component> = emptyMap(),
    ): Component {
        return renderTemplate(template, placeholders, componentPlaceholders)
    }

    fun renderLegacy(path: String, placeholders: Map<String, String> = emptyMap()): String {
        return legacySerializer.serialize(render(path, placeholders))
    }

    fun renderLegacyRaw(template: String, placeholders: Map<String, String> = emptyMap()): String {
        return legacySerializer.serialize(renderRaw(template, placeholders))
    }

    fun renderLegacyLines(path: String, placeholders: Map<String, String> = emptyMap()): List<String> {
        return renderLines(path, placeholders).map(legacySerializer::serialize)
    }

    fun renderLegacyRawLines(lines: List<String>, placeholders: Map<String, String> = emptyMap()): List<String> {
        return lines.map { renderLegacyRaw(it, placeholders) }
    }

    fun renderPlain(path: String, placeholders: Map<String, String> = emptyMap()): String {
        return plainTextSerializer.serialize(render(path, placeholders))
    }

    fun renderPlainRaw(template: String, placeholders: Map<String, String> = emptyMap()): String {
        return plainTextSerializer.serialize(renderRaw(template, placeholders))
    }

    private fun renderTemplate(
        template: String,
        placeholders: Map<String, String>,
        componentPlaceholders: Map<String, Component>,
    ): Component {
        val normalizedTemplate = normalizeTemplatePlaceholders(template, placeholders.keys + componentPlaceholders.keys)
        val resolvers = mutableListOf<TagResolver>(Placeholder.component("prefix", prefixComponent))
        componentPlaceholders.forEach { (key, value) ->
            resolvers += Placeholder.component(normalizePlaceholderKey(key), value)
        }
        placeholders.forEach { (key, value) ->
            resolvers += Placeholder.unparsed(normalizePlaceholderKey(key), value)
        }
        return miniMessage.deserialize(normalizedTemplate, TagResolver.resolver(resolvers))
    }

    private fun normalizeTemplatePlaceholders(template: String, keys: Collection<String>): String {
        var normalized = template
        keys.distinct().forEach { key ->
            val normalizedKey = normalizePlaceholderKey(key)
            if (normalizedKey != key) {
                normalized = normalized.replace("<$key>", "<$normalizedKey>")
                normalized = normalized.replace("</$key>", "</$normalizedKey>")
            }
        }
        return normalized
    }

    private fun normalizePlaceholderKey(key: String): String {
        return key.trim().lowercase().filter { it.isLowerCase() || it.isDigit() || it == '_' || it == '-' }
    }

    companion object {
        fun load(dataFolder: Path, locale: String): LanguageService {
            val target = dataFolder.resolve("lang").resolve("$locale.yml").toFile()
            val yaml = YamlConfiguration.loadConfiguration(target)
            return LanguageService(yaml)
        }
    }
}
