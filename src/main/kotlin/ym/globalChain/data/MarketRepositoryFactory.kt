package ym.globalchain.data

import ym.globalchain.config.PluginConfig
import ym.globalchain.config.StorageMode
import ym.globalchain.util.TrackedItemService
import java.nio.file.Path

object MarketRepositoryFactory {

    fun create(dataFolder: Path, config: PluginConfig, trackedItemService: TrackedItemService): MarketRepository {
        return when (config.storage.mode) {
            StorageMode.YAML -> {
                val file = dataFolder.resolve(config.storage.yaml.file).normalize()
                YamlMarketRepository(file)
            }
            StorageMode.MYSQL -> {
                val databaseManager = DatabaseManager(config)
                databaseManager.supplyAsync { connection ->
                    SchemaManager.ensureSchema(connection)
                }.join()
                MysqlMarketRepository(databaseManager)
            }
        }
    }
}
