package com.recipebook.server.database

import com.recipebook.server.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.net.URI
import java.net.SocketException
import java.net.SocketTimeoutException
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import org.slf4j.LoggerFactory

object DatabaseFactory {
    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)
    private const val initMaxAttempts = 3

    @Volatile
    private var initConfigRef: AppConfig? = null

    @Volatile
    private var dataSourceRef: HikariDataSource? = null

    @Volatile
    private var databaseUrlRef: String? = null

    @Volatile
    private var autoCreateSchemaRef: Boolean = true

    private val indexWarmupInProgress = AtomicBoolean(false)

    private data class JdbcConnection(
        val jdbcUrl: String,
        val username: String? = null,
        val password: String? = null,
    )

    fun init(config: AppConfig) {
        initConfigRef = config
        autoCreateSchemaRef = config.autoCreateSchema
        connectWithRetries(
            url = config.databaseUrl,
            autoCreateSchema = config.autoCreateSchema,
        )
    }

    private fun connectWithRetries(
        url: String,
        autoCreateSchema: Boolean,
    ) {
        var lastError: Throwable? = null
        repeat(initMaxAttempts) { index ->
            val attempt = index + 1
            var dataSource: HikariDataSource? = null
            try {
                dataSource = hikari(url)
                dataSourceRef = dataSource
                databaseUrlRef = url

                Database.connect(
                    datasource = dataSource,
                    databaseConfig = DatabaseConfig {
                        if (!url.startsWith("jdbc:h2")) {
                            explicitDialect = PostgreSQLDialect()
                        }
                        defaultMaxAttempts = 1
                    },
                )

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
                scheduleIndexWarmup(dataSource)

                if (attempt > 1) {
                    logger.info("Database connection recovered on attempt {}/{}", attempt, initMaxAttempts)
                }
                return
            } catch (t: Throwable) {
                dataSource?.close()
                lastError = t
                val delayMs = min(5_000L * attempt, 20_000L)
                if (attempt < initMaxAttempts) {
                    logger.warn(
                        "Database init attempt {}/{} failed ({}). Retrying in {} ms",
                        attempt,
                        initMaxAttempts,
                        t.message ?: t::class.simpleName ?: "unknown error",
                        delayMs,
                    )
                    Thread.sleep(delayMs)
                }
            }
        }
        throw IllegalStateException("Failed to connect to database after $initMaxAttempts attempts", lastError)
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
        ensureInitialized()
        return dataSourceRef ?: error("Database is not initialized")
    }

    fun <T> withDbRetry(maxAttempts: Int = 2, block: () -> T): T {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        ensureInitialized()
        var lastError: Throwable? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (error: Throwable) {
                lastError = error
                val retryable = isRetryableDbFailure(error)
                val isLast = attempt == maxAttempts - 1
                if (!retryable || isLast) {
                    throw error
                }
                logger.warn(
                    "Retryable database failure detected (attempt {}/{}): {}. Retrying.",
                    attempt + 1,
                    maxAttempts,
                    error.message ?: error::class.simpleName ?: "unknown error",
                )
                // Soft-evict stale idle connections without tearing down active ones.
                // Immediate pool recreation can close sockets for in-flight requests.
                runCatching { dataSourceRef?.hikariPoolMXBean?.softEvictConnections() }
                Thread.sleep(200L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("Database operation failed")
    }

    private fun hikari(url: String): HikariDataSource {
        val connection = parseJdbcConnection(url)
        val isH2 = connection.jdbcUrl.startsWith("jdbc:h2")
        val isPooler = connection.jdbcUrl.contains("-pooler.", ignoreCase = true)
        val config = HikariConfig().apply {
            jdbcUrl = connection.jdbcUrl
            connection.username?.let { username = it }
            connection.password?.let { password = it }

            if (!isH2) {
                addDataSourceProperty("gssEncMode", "disable")
                addDataSourceProperty("tcpKeepAlive", "true")
                addDataSourceProperty("sslmode", "require")
                addDataSourceProperty("connectTimeout", "10")
                addDataSourceProperty("socketTimeout", if (isPooler) "12" else "15")
                if (isPooler) {
                    addDataSourceProperty("preparedStatementCacheQueries", "0")
                    addDataSourceProperty("preparedStatementCacheSizeMiB", "0")
                    addDataSourceProperty("preferQueryMode", "simple")
                }
            }

            driverClassName = if (isH2) "org.h2.Driver" else "org.postgresql.Driver"
            maximumPoolSize = if (isPooler) 3 else 4
            minimumIdle = 0
            connectionTimeout = 12_000
            initializationFailTimeout = 15_000
            maxLifetime = 30 * 60_000
            idleTimeout = 2 * 60_000
            keepaliveTime = 120_000
            validationTimeout = 5_000
            connectionTestQuery = "SELECT 1"
            isAutoCommit = true
            validate()
        }

        return HikariDataSource(config)
    }

    @Synchronized
    private fun ensureInitialized() {
        if (dataSourceRef != null) return
        val config = initConfigRef ?: error("Database config is not initialized")
        connectWithRetries(
            url = config.databaseUrl,
            autoCreateSchema = autoCreateSchemaRef,
        )
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

    private fun scheduleIndexWarmup(dataSource: HikariDataSource) {
        if (!dataSource.jdbcUrl.startsWith("jdbc:postgresql://")) return
        if (!indexWarmupInProgress.compareAndSet(false, true)) return

        Thread(
            {
                try {
                    ensureIndexesIfNeeded(dataSource)
                } finally {
                    indexWarmupInProgress.set(false)
                }
            },
            "recipebook-db-index-warmup",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun ensureIndexesIfNeeded(dataSource: HikariDataSource) {
        if (!dataSource.jdbcUrl.startsWith("jdbc:postgresql://")) return
        runCatching {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.queryTimeout = 5
                    statement.execute("SET lock_timeout = '3s'")
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_recipes_author_created ON recipes(author_id, created_at DESC)")
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_ratings_recipe ON ratings(recipe_id)")
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_ratings_recipe_value ON ratings(recipe_id, value)")
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_comments_recipe_created ON comments(recipe_id, created_at DESC)")
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_favorites_user ON favorites(user_id)")
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_follows_follower ON follows(follower_id)")
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_follows_following ON follows(following_id)")
                }
            }
        }.onFailure { error ->
            logger.warn("Unable to ensure db indexes: {}", error.message ?: "unknown")
        }
    }

    private fun isRetryableDbFailure(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            when (cause) {
                is SocketTimeoutException, is SocketException -> return true
                is SQLException -> {
                    if (
                        cause.sqlState == "08006" ||
                        cause.sqlState == "08001" ||
                        cause.sqlState == "57P01" ||
                        cause.sqlState == "57014" ||
                        cause.sqlState == "55P03"
                    ) {
                        return true
                    }
                }
            }
            cause = cause.cause
        }
        return false
    }
}
