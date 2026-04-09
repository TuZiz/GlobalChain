package ym.globalchain.util

import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class ItemStackCodec {

    fun encode(itemStack: ItemStack): String {
        val raw = ByteArrayOutputStream()
        BukkitObjectOutputStream(raw).use { stream ->
            stream.writeObject(itemStack)
        }
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { gzip ->
            gzip.write(raw.toByteArray())
        }
        return "${GZIP_PREFIX}${Base64.getEncoder().encodeToString(compressed.toByteArray())}"
    }

    fun decode(encoded: String): ItemStack {
        val bytes = Base64.getDecoder().decode(encoded.removePrefix(GZIP_PREFIX))
        val inputStream = if (encoded.startsWith(GZIP_PREFIX)) {
            GZIPInputStream(ByteArrayInputStream(bytes))
        } else {
            ByteArrayInputStream(bytes)
        }
        BukkitObjectInputStream(inputStream).use { stream ->
            return stream.readObject() as ItemStack
        }
    }

    companion object {
        private const val GZIP_PREFIX = "gz:"
    }
}
