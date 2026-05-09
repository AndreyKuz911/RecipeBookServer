package org.jetbrains.exposed.sql.statements.jdbc

import org.jetbrains.exposed.sql.statements.api.IdentifierManagerApi
import java.sql.DatabaseMetaData

/**
 * Override Exposed's default JDBC identifier manager to avoid hard failure on
 * `DatabaseMetaData#getSQLKeywords()`. On some Neon connections this metadata call
 * intermittently resets the socket, while normal CRUD queries still work.
 */
internal class JdbcIdentifierManager(metadata: DatabaseMetaData) : IdentifierManagerApi() {
    override val quoteString = runCatching { metadata.identifierQuoteString?.trim() }
        .getOrNull()
        .takeUnless { it.isNullOrBlank() }
        ?: "\""

    override val isUpperCaseIdentifiers = runCatching { metadata.storesUpperCaseIdentifiers() }.getOrDefault(false)
    override val isUpperCaseQuotedIdentifiers = runCatching { metadata.storesUpperCaseQuotedIdentifiers() }.getOrDefault(false)
    override val isLowerCaseIdentifiers = runCatching { metadata.storesLowerCaseIdentifiers() }.getOrDefault(true)
    override val isLowerCaseQuotedIdentifiers = runCatching { metadata.storesLowerCaseQuotedIdentifiers() }.getOrDefault(true)
    override val supportsMixedIdentifiers = runCatching { metadata.supportsMixedCaseIdentifiers() }.getOrDefault(true)
    override val supportsMixedQuotedIdentifiers = runCatching { metadata.supportsMixedCaseQuotedIdentifiers() }.getOrDefault(true)

    private val _keywords = runCatching { metadata.sqlKeywords.split(',') }.getOrDefault(emptyList())
    override fun dbKeywords(): List<String> = _keywords
    override val extraNameCharacters = runCatching { metadata.extraNameCharacters }.getOrDefault("")

    @Suppress("MagicNumber")
    override val oracleVersion = runCatching {
        when {
            metadata.databaseProductName != "Oracle" -> OracleVersion.NonOracle
            metadata.databaseMajorVersion <= 11 -> OracleVersion.Oracle11g
            metadata.databaseMajorVersion == 12 && metadata.databaseMinorVersion == 1 -> OracleVersion.Oracle12_1g
            else -> OracleVersion.Oracle12plus
        }
    }.getOrDefault(OracleVersion.NonOracle)

    override val maxColumnNameLength: Int = runCatching { metadata.maxColumnNameLength }.getOrDefault(63)
}

