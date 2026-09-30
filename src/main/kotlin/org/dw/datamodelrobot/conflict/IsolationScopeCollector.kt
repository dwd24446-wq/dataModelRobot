package org.dw.datamodelrobot.conflict

import org.dw.datamodelrobot.evidence.Evidence
import org.dw.datamodelrobot.phase0.EntityIndex
import org.dw.datamodelrobot.phase0.EntityInfo
import org.dw.datamodelrobot.phase0.RefRecord
import org.dw.datamodelrobot.schema.DbSchema

/**
 * ② 租户 / 逻辑删除隔离缺失。
 *
 * **只打在有 JOIN 的查询链路上**，这是实测逼出来的收窄：真实项目的业务代码里几乎没人手写
 * `getDeleted()` / `getTenantId()` 条件（全项目只有框架层几个文件出现），因为 MyBatis-Plus 的
 * `@TableLogic` 与 `TenantLineInnerInterceptor` 会自动加。所以「代码里没写」在单表查询上根本不是信号，
 * 照 PLAN 原话全量报会产出几千条噪声。
 *
 * 自动注入覆盖不到的地方才是真风险，两者还不一样：
 * - **`deleted`（MEDIUM）**：`@TableLogic` 是 MP 的 SQL 注入器加的，只覆盖它自己内置方法的**主表**；
 *   被 JOIN 的表要不要加，mybatis-plus-join 各版本行为不一致 —— 所以这条要人工确认，但确实是最可能出错的一条
 * - **`tenant_id`（LOW）**：租户拦截器在 SQL 层解析后追加，JOIN 的表一般也能覆盖；真会漏的是表在
 *   ignore 名单里、拦截器被关、或 SQL 复杂到解析不了。所以只作提示
 *
 * 表有没有这两列，看**实体列 ∪ schema 列**：
 * - 实体：实测项目里参与 JOIN 的部分模块表根本不在这个库里，但它们照样继承 `BaseDO`（deleted），
 *   这正是要查的那批
 * - schema：这类脚手架的租户隔离是**按表名**在拦截器里配的，DO 不一定要有 `tenant_id` 字段 ——
 *   实测项目里继承 `TenantBaseDO` 的 DO 只占零头，库里却有上百张表带 `tenant_id`。只看实体会漏掉
 *   「表有租户列、DO 没声明」这种最常见的形态，所以两边都要看
 *
 * 手写 SQL（Mapper XML / `@Select`）不走这条规则：那是文本级 SQL 分析，别名与子查询的归属判不准，
 * 宁可不做也不猜。
 */
object IsolationScopeCollector {

    private const val DELETED = "deleted"
    private const val TENANT = "tenant_id"

    /** 一次 JOIN 查询链路：同一个文件同一个方法里的一次多表查询 */
    data class QuerySite(val file: String, val method: String?, val line: Int, val tables: Set<String>)

    fun collect(
        joinEvidences: List<Evidence>,
        records: List<RefRecord>,
        index: EntityIndex,
        schema: DbSchema? = null,
    ): List<Conflict> {
        val entitiesByTable = index.entitiesByFqn.values.associateBy { it.tableName.lowercase() }
        val recordsBySite = records.filter { it.success }
            .groupBy { it.file to (it.enclosingMethod ?: "") }
            .mapValues { (_, rs) -> rs.mapNotNull { r -> r.table?.let { "$it.${r.column}".lowercase() } }.toSet() }

        val out = mutableListOf<Conflict>()
        for (site in sites(joinEvidences)) {
            val referenced = recordsBySite[site.file to (site.method ?: "")].orEmpty()
            val fileName = site.file.substringAfterLast('/')
            for (table in site.tables.sorted()) {
                val entity = entitiesByTable[table.lowercase()] ?: continue
                val columns = columnsOf(entity, table, schema)
                if (DELETED in columns && "$table.$DELETED" !in referenced) {
                    out += conflict(
                        ConflictType.MISSING_LOGICAL_DELETE, Severity.MEDIUM, site, table, fileName, DELETED,
                        "这张表有 `deleted`（逻辑删除）但这条 JOIN 链路里没出现它的删除条件。" +
                            "@TableLogic 只覆盖 MyBatis-Plus 内置方法的主表，被 JOIN 的表要不要加依 mybatis-plus-join 版本而定 —— 需人工确认",
                    )
                }
                if (TENANT in columns && "$table.$TENANT" !in referenced) {
                    out += conflict(
                        ConflictType.MISSING_TENANT_SCOPE, Severity.LOW, site, table, fileName, TENANT,
                        "这张表有 `tenant_id` 但这条 JOIN 链路里没出现它的租户条件。" +
                            "租户拦截器按**表名**在 SQL 层追加（含 JOIN 表，DO 不声明这个字段也会加），所以多数情况不是 bug；" +
                            "表在 ignore 名单里或 SQL 解析不了时才会真漏",
                    )
                }
            }
        }
        return out.sortedWith(compareBy({ it.type.ordinal }, { it.severity.ordinal }, { it.subject }))
    }

    /** 实体列 ∪ schema 列。实体缺（表不在这个库里）或 schema 缺（DO 没声明租户字段）都要能判 */
    private fun columnsOf(entity: EntityInfo, table: String, schema: DbSchema?): Set<String> = buildSet {
        entity.columns.values.forEach { add(it.column.lowercase()) }
        schema?.table(table)?.columns?.forEach { add(it.name.lowercase()) }
    }

    /** JOIN 链路 = 带 JOIN 语境的证据所在的 (文件, 方法)。on-lambda 形态的证据也带 joinType，所以两种写法都认 */
    private fun sites(joinEvidences: List<Evidence>): List<QuerySite> =
        joinEvidences.filter { it.joinType != null }
            .mapNotNull { e -> e.from.file?.let { f -> Triple(f, e.enclosingMethod.orEmpty(), e) } }
            .groupBy { it.first to it.second }
            .map { (key, list) ->
                QuerySite(
                    file = key.first,
                    method = key.second.ifEmpty { null },
                    line = list.minOf { it.third.from.line },
                    tables = list.flatMap { listOf(it.third.from.table, it.third.to.table) }.toSet(),
                )
            }

    private fun conflict(
        type: ConflictType,
        severity: Severity,
        site: QuerySite,
        table: String,
        fileName: String,
        column: String,
        why: String,
    ) = Conflict(
        type = type,
        severity = severity,
        subject = "$fileName:${site.line} ${site.method ?: "-"} → $table.$column",
        detail = why + "。这条链路涉及 ${site.tables.size} 张表: ${site.tables.sorted().joinToString(", ")}",
        refs = listOf("$fileName:${site.line}"),
    )
}
