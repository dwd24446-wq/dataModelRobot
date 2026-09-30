package org.dw.datamodelrobot.schema

/** 框架自带表：不进事实源。Quartz 调度器表往往是库里唯一带显式外键的一批，排掉后业务表外键通常为 0。 */
object SchemaTables {

    val EXCLUDED_PREFIXES = listOf("qrtz_")
    val EXCLUDED_NAMES = setOf("flyway_schema_history")

    fun isExcluded(name: String): Boolean {
        val lower = name.lowercase()
        return lower in EXCLUDED_NAMES || EXCLUDED_PREFIXES.any { lower.startsWith(it) }
    }

    /** 这类脚手架的表前缀天然是模块边界（system_ / infra_ / trade_ / …）；无下划线的表自成模块。 */
    fun moduleOf(name: String): String = name.lowercase().substringBefore('_')
}

data class SchemaStats(
    val tables: Int,
    val columns: Int,
    val indexes: Int,
    val foreignKeys: Int,
    val foreignKeyMappings: Int,
    val commentedTables: Int,
    val commentedColumns: Int,
) {
    companion object {
        fun of(tables: List<SchemaTable>) = SchemaStats(
            tables = tables.size,
            columns = tables.sumOf { it.columns.size },
            indexes = tables.sumOf { it.indexes.size },
            foreignKeys = tables.sumOf { it.foreignKeys.size },
            foreignKeyMappings = tables.sumOf { t -> t.foreignKeys.sumOf { it.columns.size } },
            commentedTables = tables.count { !it.comment.isNullOrEmpty() },
            commentedColumns = tables.sumOf { t -> t.columns.count { !it.comment.isNullOrEmpty() } },
        )
    }
}

data class SchemaColumn(
    val name: String,
    val position: Int,
    /** data_type，如 bigint / varchar */
    val type: String,
    /** column_type，如 bigint unsigned / varchar(64) */
    val fullType: String,
    val nullable: Boolean,
    /** information_schema.COLUMN_KEY：PRI / UNI / MUL / null */
    val key: String?,
    val defaultValue: String?,
    val extra: String?,
    val comment: String?,
)

data class SchemaIndex(
    val name: String,
    val unique: Boolean,
    val type: String?,
    val columns: List<String>,
)

data class SchemaForeignKey(
    val name: String,
    val columns: List<String>,
    val refTable: String,
    val refColumns: List<String>,
    val onDelete: String?,
    val onUpdate: String?,
)

data class SchemaTable(
    val name: String,
    val module: String,
    val comment: String?,
    val engine: String?,
    val collation: String?,
    val columns: List<SchemaColumn>,
    val indexes: List<SchemaIndex>,
    val foreignKeys: List<SchemaForeignKey>,
) {
    fun column(name: String): SchemaColumn? = columns.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

/** 一个库的 schema 事实。`tables` 是 provider 读到的全部（含被排除表），事实源只取 [includedTables]。 */
data class DbSchema(
    val catalog: String,
    val dbms: String,
    val dbmsVersion: String?,
    val server: String?,
    val providerId: String,
    val tables: List<SchemaTable>,
    val views: List<String>,
) {
    private val byName: Map<String, SchemaTable> = tables.associateBy { it.name.lowercase() }

    fun table(name: String): SchemaTable? = byName[name.lowercase()]

    val includedTables: List<SchemaTable> get() = tables.filterNot { SchemaTables.isExcluded(it.name) }
    val excludedTables: List<SchemaTable> get() = tables.filter { SchemaTables.isExcluded(it.name) }

    val stats: SchemaStats get() = SchemaStats.of(includedTables)
    val rawStats: SchemaStats get() = SchemaStats.of(tables)

    fun modules(): Map<String, List<SchemaTable>> =
        includedTables.groupBy { it.module }.toSortedMap()
}
