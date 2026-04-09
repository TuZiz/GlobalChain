package ym.globalchain.config

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Path

object PluginConfigLoader {

    fun load(plugin: JavaPlugin, dataFolder: Path): PluginConfig {
        val configFile = dataFolder.resolve("config.yml").toFile()
        val yaml = YamlConfiguration.loadConfiguration(configFile)
        return PluginConfig(
            serverId = yaml.getString("server-id").orEmpty().ifBlank { "node-a" },
            language = yaml.getString("language").orEmpty().ifBlank { "zh_cn" },
            currency = CurrencyConfig(
                scale = yaml.getInt("currency.scale", 2).coerceAtLeast(0),
                name = yaml.getString("currency.name").orEmpty().ifBlank { "系统余额" },
                symbol = yaml.getString("currency.symbol").orEmpty().ifBlank { "￥" },
            ),
            storage = StorageConfig(
                mode = runCatching {
                    StorageMode.valueOf(yaml.getString("storage.mode", "yaml").orEmpty().uppercase())
                }.getOrDefault(StorageMode.YAML),
                yaml = YamlStorageConfig(
                    file = yaml.getString("storage.yaml.file").orEmpty().ifBlank { "storage/local-market.yml" },
                ),
                mysql = MysqlStorageConfig(
                    jdbcUrl = yaml.getString("storage.mysql.jdbc-url").orEmpty().ifBlank { "jdbc:mysql://127.0.0.1:3306/globalchain?useSSL=false&characterEncoding=utf8&serverTimezone=UTC" },
                    username = yaml.getString("storage.mysql.username").orEmpty().ifBlank { "root" },
                    password = yaml.getString("storage.mysql.password").orEmpty(),
                    driverClassName = yaml.getString("storage.mysql.driver-class-name").orEmpty().ifBlank { "com.mysql.cj.jdbc.Driver" },
                    pool = PoolConfig(
                        maximumPoolSize = yaml.getInt("storage.mysql.pool.maximum-pool-size", 16).coerceAtLeast(2),
                        minimumIdle = yaml.getInt("storage.mysql.pool.minimum-idle", 4).coerceAtLeast(1),
                        connectionTimeoutMs = yaml.getLong("storage.mysql.pool.connection-timeout-ms", 10_000L).coerceAtLeast(1_000L),
                        leakDetectionThresholdMs = yaml.getLong("storage.mysql.pool.leak-detection-threshold-ms", 0L).coerceAtLeast(0L),
                    ),
                ),
            ),
            market = MarketConfig(
                browsePageSize = yaml.getInt("market.browse-page-size", 8).coerceIn(1, 54),
                maxListingsPerPlayer = yaml.getInt("market.max-listings-per-player", 32).coerceAtLeast(1),
                maxPriceRaw = yaml.getString("market.max-price").orEmpty().ifBlank { "1000000000.00" },
                saleFeeRateRaw = yaml.getString("market.sale-fee-rate").orEmpty().ifBlank { "0.00" },
                commandIdPrefixMinLength = yaml.getInt("market.command-id-prefix-min-length", 8).coerceAtLeast(4),
                recoveryCheckIntervalSeconds = yaml.getLong("market.recovery-check-interval-seconds", 30L).coerceAtLeast(5L),
                listingExpireSeconds = yaml.getLong("market.listing-expire-seconds", 259200L).coerceAtLeast(60L),
            ),
            performance = PerformanceConfig(
                ioThreads = yaml.getInt("performance.io-threads", 8).coerceIn(2, 64),
            ),
            debug = DebugConfig(
                compatibilityMode = yaml.getBoolean("debug.compatibility-mode", false),
            ),
        )
    }
}
