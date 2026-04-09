package ym.globalchain.data

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import ym.globalchain.config.PluginConfig
import java.sql.Connection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DatabaseManager(config: PluginConfig) : AutoCloseable {

    private val dataSource: HikariDataSource
    private val ioExecutor: ExecutorService = Executors.newFixedThreadPool(config.performance.ioThreads) { runnable ->
        Thread(runnable, "GlobalChain-IO").apply { isDaemon = true }
    }

    init {
        val hikari = HikariConfig().apply {
            jdbcUrl = config.storage.mysql.jdbcUrl
            username = config.storage.mysql.username
            password = config.storage.mysql.password
            maximumPoolSize = config.storage.mysql.pool.maximumPoolSize
            minimumIdle = config.storage.mysql.pool.minimumIdle
            connectionTimeout = config.storage.mysql.pool.connectionTimeoutMs
            leakDetectionThreshold = config.storage.mysql.pool.leakDetectionThresholdMs
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            isAutoCommit = true
            config.storage.mysql.driverClassName?.let { driverClassName = it }
        }
        dataSource = HikariDataSource(hikari)
    }

    fun <T> supplyAsync(block: (Connection) -> T): CompletableFuture<T> {
        return CompletableFuture.supplyAsync({
            dataSource.connection.use(block)
        }, ioExecutor)
    }

    fun <T> transactionAsync(block: (Connection) -> T): CompletableFuture<T> {
        return CompletableFuture.supplyAsync({
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val result = block(connection)
                    connection.commit()
                    result
                } catch (throwable: Throwable) {
                    connection.rollback()
                    throw throwable
                } finally {
                    connection.autoCommit = true
                }
            }
        }, ioExecutor)
    }

    override fun close() {
        ioExecutor.shutdownNow()
        dataSource.close()
    }
}
