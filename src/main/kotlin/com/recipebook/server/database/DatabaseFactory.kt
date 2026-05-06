package com.recipebook.server.database

import com.recipebook.server.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.net.URI
import kotlin.math.min
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

object DatabaseFactory {
    private const val LOCAL_FALLBACK_URL =
        "jdbc:h2:file:./data/recipebook-local;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)
    @Volatile
    private var dataSourceRef: HikariDataSource? = null
    @Volatile
    private var databaseUrlRef: String? = null

    private data class JdbcConnection(
        val jdbcUrl: String,
        val username: String? = null,
        val password: String? = null,
    )

    fun init(config: AppConfig) {
        if (config.databaseUrl.contains("-pooler.", ignoreCase = true)) {
            logger.warn(
                "Detected Neon pooler URL. For Ktor + HikariCP use direct connection string (without -pooler).",
            )
        }
        if (config.databaseFallbackLocal) {
            logger.warn("DATABASE_FALLBACK_LOCAL=true. Starting server with local H2 database.")
            connectWithRetries(
                url = LOCAL_FALLBACK_URL,
                autoCreateSchema = true,
                maxAttempts = 2,
            )
            return
        }

        try {
            connectWithRetries(
                url = config.databaseUrl,
                autoCreateSchema = config.autoCreateSchema,
                maxAttempts = 6,
            )
        } catch (primaryError: Throwable) {
            logger.error(
                "Primary database is unstable ({}). Falling back to local H2 database for development.",
                primaryError.message ?: primaryError::class.simpleName ?: "unknown error",
            )
            connectWithRetries(
                url = LOCAL_FALLBACK_URL,
                autoCreateSchema = true,
                maxAttempts = 3,
            )
        }
    }

    private fun connectWithRetries(
        url: String,
        autoCreateSchema: Boolean,
        maxAttempts: Int,
    ) {
        var lastError: Throwable? = null
        repeat(maxAttempts) { index ->
            val attempt = index + 1
            var dataSource: HikariDataSource? = null
            try {
                dataSource = hikari(url)
                dataSourceRef = dataSource
                databaseUrlRef = url
                Database.connect(dataSource)
                if (autoCreateSchema) {
                    transaction {
                        SchemaUtils.create(
                            UsersTable,
                            RecipesTable,
                            RatingsTable,
                            CommentsTable,
                            FavoritesTable,
                            FollowsTable,
                        )
                    }
                }
                if (attempt > 1) {
                    logger.info("Database connection recovered on attempt {}/{}", attempt, maxAttempts)
                }
                return
            } catch (t: Throwable) {
                dataSource?.close()
                lastError = t
                val delayMs = min(5_000L * attempt, 20_000L)
                if (attempt < maxAttempts) {
                    logger.warn(
                        "Database init attempt {}/{} failed ({}). Retrying in {} ms",
                        attempt,
                        maxAttempts,
                        t.message ?: t::class.simpleName ?: "unknown error",
                        delayMs,
                    )
                    Thread.sleep(delayMs)
                }
            }
        }
        throw IllegalStateException("Failed to connect to database after $maxAttempts attempts", lastError)
    }

    fun connectForTests(databaseUrl: String) {
        val dataSource = hikari(databaseUrl)
        dataSourceRef = dataSource
        databaseUrlRef = databaseUrl
        Database.connect(dataSource)
        transaction {
            SchemaUtils.drop(
                FollowsTable,
                FavoritesTable,
                CommentsTable,
                RatingsTable,
                RecipesTable,
                UsersTable,
            )
            SchemaUtils.create(
                UsersTable,
                RecipesTable,
                RatingsTable,
                CommentsTable,
                FavoritesTable,
                FollowsTable,
            )
        }
    }

    fun dataSource(): HikariDataSource {
        return dataSourceRef ?: error("Database is not initialized")
    }

    @Synchronized
    fun reconnectDataSource() {
        val url = databaseUrlRef ?: error("Database URL is not initialized")
        val old = dataSourceRef
        val fresh = hikari(url)
        Database.connect(fresh)
        dataSourceRef = fresh
        old?.close()
        logger.warn("Database pool was recreated after a connection failure")
    }

    private fun hikari(url: String): HikariDataSource {
        val connection = parseJdbcConnection(url)
        val isH2 = connection.jdbcUrl.startsWith("jdbc:h2")
        val config = HikariConfig().apply {
            jdbcUrl = connection.jdbcUrl
            connection.username?.let { username = it }
            connection.password?.let { password = it }
            if (!isH2) {
                addDataSourceProperty("gssEncMode", "disable")
                addDataSourceProperty("tcpKeepAlive", "true")
                addDataSourceProperty("connectTimeout", "10")
                addDataSourceProperty("socketTimeout", "20")
            }
            driverClassName = if (isH2) "org.h2.Driver" else "org.postgresql.Driver"
            maximumPoolSize = 5
            minimumIdle = 1
            connectionTimeout = 30_000
            initializationFailTimeout = 60_000
            maxLifetime = 30 * 60_000
            idleTimeout = 5 * 60_000
            keepaliveTime = 30_000
            validationTimeout = 5_000
            connectionTestQuery = "SELECT 1"
            isAutoCommit = true
            validate()
        }

        return HikariDataSource(config)
    }

    private fun parseJdbcConnection(url: String): JdbcConnection {
        if (!url.startsWith("jdbc:postgresql://")) return JdbcConnection(jdbcUrl = url)

        val asUri = URI(url.removePrefix("jdbc:"))
        val userInfo = asUri.userInfo ?: return JdbcConnection(jdbcUrl = url)
        val username = userInfo.substringBefore(":")
        val password = userInfo.substringAfter(":", "")
        val port = if (asUri.port == -1) 5432 else asUri.port
        val query = asUri.rawQuery?.let { "?$it" } ?: ""
        val jdbcUrl = "jdbc:postgresql://${asUri.host}:$port${asUri.rawPath}$query"
        return JdbcConnection(
            jdbcUrl = jdbcUrl,
            username = username,
            password = password,
        )
    }
}
