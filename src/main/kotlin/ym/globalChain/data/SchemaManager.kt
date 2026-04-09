package ym.globalchain.data

import java.sql.Connection
import java.sql.SQLException

object SchemaManager {

    fun ensureSchema(connection: Connection) {
        val statements = listOf(
            """
            CREATE TABLE IF NOT EXISTS gc_wallets (
                player_uuid VARCHAR(36) PRIMARY KEY,
                player_name VARCHAR(32) NOT NULL,
                balance BIGINT NOT NULL,
                updated_at BIGINT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS gc_wallet_ledger (
                entry_id VARCHAR(36) PRIMARY KEY,
                player_uuid VARCHAR(36) NOT NULL,
                delta BIGINT NOT NULL,
                reason VARCHAR(64) NOT NULL,
                reference_id VARCHAR(64) NOT NULL,
                created_at BIGINT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS gc_sale_intents (
                intent_id VARCHAR(36) PRIMARY KEY,
                player_uuid VARCHAR(36) NOT NULL,
                player_name VARCHAR(32) NOT NULL,
                price_minor BIGINT NOT NULL,
                item_data LONGTEXT NOT NULL,
                item_name VARCHAR(255) NOT NULL,
                amount INT NOT NULL,
                status VARCHAR(16) NOT NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS gc_listings (
                listing_id VARCHAR(36) PRIMARY KEY,
                seller_uuid VARCHAR(36) NOT NULL,
                seller_name VARCHAR(32) NOT NULL,
                item_data LONGTEXT NOT NULL,
                item_name VARCHAR(255) NOT NULL,
                material_key VARCHAR(64) NOT NULL,
                amount INT NOT NULL,
                price_minor BIGINT NOT NULL,
                status VARCHAR(16) NOT NULL,
                buyer_uuid VARCHAR(36),
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                expire_at BIGINT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS gc_mailbox (
                claim_id VARCHAR(36) PRIMARY KEY,
                player_uuid VARCHAR(36) NOT NULL,
                item_data LONGTEXT NOT NULL,
                item_name VARCHAR(255) NOT NULL,
                amount INT NOT NULL,
                reference_id VARCHAR(36) NOT NULL,
                status VARCHAR(16) NOT NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS gc_delivery_intents (
                intent_id VARCHAR(36) PRIMARY KEY,
                claim_id VARCHAR(36) NOT NULL,
                player_uuid VARCHAR(36) NOT NULL,
                item_data LONGTEXT NOT NULL,
                item_name VARCHAR(255) NOT NULL,
                amount INT NOT NULL,
                status VARCHAR(16) NOT NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS gc_payouts (
                reference_id VARCHAR(36) PRIMARY KEY,
                seller_uuid VARCHAR(36) NOT NULL,
                seller_name VARCHAR(32) NOT NULL,
                amount_minor BIGINT NOT NULL,
                status VARCHAR(16) NOT NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL
            )
            """.trimIndent(),
        )

        statements.forEach { sql ->
            connection.createStatement().use { statement ->
                statement.execute(sql)
            }
        }

        safeStatement(connection, "ALTER TABLE gc_sale_intents MODIFY item_data LONGTEXT NOT NULL")
        safeStatement(connection, "ALTER TABLE gc_listings MODIFY item_data LONGTEXT NOT NULL")
        safeStatement(connection, "ALTER TABLE gc_mailbox MODIFY item_data LONGTEXT NOT NULL")
        safeStatement(connection, "ALTER TABLE gc_delivery_intents MODIFY item_data LONGTEXT NOT NULL")
        safeStatement(connection, "ALTER TABLE gc_listings ADD COLUMN expire_at BIGINT NOT NULL DEFAULT 0")

        safeIndex(connection, "CREATE INDEX gc_wallet_ledger_player_idx ON gc_wallet_ledger(player_uuid, created_at)")
        safeIndex(connection, "CREATE INDEX gc_sale_intents_player_status_idx ON gc_sale_intents(player_uuid, status)")
        safeIndex(connection, "CREATE INDEX gc_listings_status_created_idx ON gc_listings(status, created_at)")
        safeIndex(connection, "CREATE INDEX gc_listings_seller_status_idx ON gc_listings(seller_uuid, status)")
        safeIndex(connection, "CREATE INDEX gc_listings_status_expire_idx ON gc_listings(status, expire_at)")
        safeIndex(connection, "CREATE INDEX gc_mailbox_player_status_idx ON gc_mailbox(player_uuid, status, created_at)")
        safeIndex(connection, "CREATE INDEX gc_delivery_intents_player_status_idx ON gc_delivery_intents(player_uuid, status)")
        safeIndex(connection, "CREATE INDEX gc_payouts_status_created_idx ON gc_payouts(status, created_at)")
    }

    private fun safeIndex(connection: Connection, sql: String) {
        try {
            connection.createStatement().use { statement ->
                statement.execute(sql)
            }
        } catch (_: SQLException) {
        }
    }

    private fun safeStatement(connection: Connection, sql: String) {
        try {
            connection.createStatement().use { statement ->
                statement.execute(sql)
            }
        } catch (_: SQLException) {
        }
    }
}
