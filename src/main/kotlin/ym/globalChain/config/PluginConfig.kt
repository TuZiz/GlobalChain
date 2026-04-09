package ym.globalchain.config

data class PluginConfig(
    val serverId: String,
    val language: String,
    val currency: CurrencyConfig,
    val storage: StorageConfig,
    val market: MarketConfig,
    val performance: PerformanceConfig,
    val debug: DebugConfig,
)

data class CurrencyConfig(
    val scale: Int,
    val name: String,
    val symbol: String,
)

enum class StorageMode {
    YAML,
    MYSQL,
}

data class StorageConfig(
    val mode: StorageMode,
    val yaml: YamlStorageConfig,
    val mysql: MysqlStorageConfig,
)

data class YamlStorageConfig(
    val file: String,
)

data class MysqlStorageConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val driverClassName: String?,
    val pool: PoolConfig,
)

data class PoolConfig(
    val maximumPoolSize: Int,
    val minimumIdle: Int,
    val connectionTimeoutMs: Long,
    val leakDetectionThresholdMs: Long,
)

data class MarketConfig(
    val browsePageSize: Int,
    val maxListingsPerPlayer: Int,
    val maxPriceRaw: String,
    val saleFeeRateRaw: String,
    val commandIdPrefixMinLength: Int,
    val recoveryCheckIntervalSeconds: Long,
    val listingExpireSeconds: Long,
)

data class PerformanceConfig(
    val ioThreads: Int,
)

data class DebugConfig(
    val compatibilityMode: Boolean,
)
