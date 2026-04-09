package ym.globalchain.menu

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Files
import java.nio.file.Path

object MenuLoader {

    fun loadAll(menusDir: Path): Map<String, MenuDefinition> {
        if (Files.notExists(menusDir)) {
            return emptyMap()
        }
        return Files.list(menusDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".yml", true) }
                .sorted()
                .toList()
                .associate { path: Path ->
                    val id = path.fileName.toString().removeSuffix(".yml")
                    id to loadOne(id, path)
                }
        }
    }

    fun loadOne(id: String, path: Path): MenuDefinition {
        val yaml = YamlConfiguration.loadConfiguration(path.toFile())
        val shape = yaml.getStringList("Shape")
        require(shape.isNotEmpty()) { "Menu $id must define Shape." }
        val parsed = parseShape(shape)
        val buttonsSection = yaml.getConfigurationSection("BUTTONS")
        val buttons = buildMap {
            buttonsSection?.getKeys(false)?.forEach { token ->
                val section = buttonsSection.getConfigurationSection(token) ?: return@forEach
                put(
                    token,
                    MenuButtonDefinition(
                        token = token,
                        materials = parseMaterials(section),
                        amount = section.getInt("amount", 1).coerceIn(1, 64),
                        glow = section.getBoolean("glow", false),
                        name = section.getString("name"),
                        lore = section.getStringList("lore"),
                        click = section.getStringList("click"),
                        dynamicRole = section.getString("dynamic-role")?.takeIf { it.isNotBlank() },
                        filterPrefixes = section.getStringList("filter-prefixes").map { it.lowercase() },
                        defaultFilter = section.getBoolean("default-filter", false),
                    ),
                )
            }
        }
        return MenuDefinition(
            id = id,
            title = yaml.getString("Title").orEmpty(),
            rows = shape.size,
            slotsByToken = parsed.first,
            tokenBySlot = parsed.second,
            buttons = buttons,
        )
    }

    private fun parseMaterials(section: ConfigurationSection): List<String> {
        val mats = mutableListOf<String>()
        section.getString("mats")?.let { mats += it }
        mats += section.getStringList("mats")
        section.getString("material")?.let { mats += it }
        return mats.filter { it.isNotBlank() }
    }

    private fun parseShape(shape: List<String>): Pair<Map<String, List<Int>>, Map<Int, String>> {
        val slotsByToken = linkedMapOf<String, MutableList<Int>>()
        val tokenBySlot = linkedMapOf<Int, String>()
        shape.forEachIndexed { row, raw ->
            val tokens = resolveRowTokens(raw)
            require(tokens.size == 9) { "Menu row ${row + 1} must resolve to 9 slots, got ${tokens.size}: $raw" }
            tokens.forEachIndexed { column, token ->
                if (token == " " || token.isBlank()) {
                    return@forEachIndexed
                }
                val slot = row * 9 + column
                slotsByToken.computeIfAbsent(token) { mutableListOf() }.add(slot)
                tokenBySlot[slot] = token
            }
        }
        return slotsByToken.mapValues { it.value.toList() } to tokenBySlot.toMap()
    }

    private fun resolveRowTokens(row: String): List<String> {
        val direct = tokenizeRow(row, skipSpaces = false)
        if (direct.size == 9) {
            return direct
        }
        if (!row.contains(' ')) {
            return direct
        }
        val compact = tokenizeRow(row, skipSpaces = true)
        return if (compact.size == 9) compact else direct
    }

    private fun tokenizeRow(row: String, skipSpaces: Boolean): List<String> {
        val tokens = mutableListOf<String>()
        var index = 0
        while (index < row.length) {
            val char = row[index]
            if (char == '`') {
                val end = row.indexOf('`', startIndex = index + 1)
                require(end >= 0) { "Unclosed backtick token in row: $row" }
                tokens += row.substring(index + 1, end)
                index = end + 1
            } else if (skipSpaces && char == ' ') {
                index++
            } else {
                tokens += char.toString()
                index++
            }
        }
        return tokens
    }
}
