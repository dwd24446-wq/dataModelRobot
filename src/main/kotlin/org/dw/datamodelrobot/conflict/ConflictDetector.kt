package org.dw.datamodelrobot.conflict

import org.dw.datamodelrobot.evidence.ColumnReferenceIndex
import org.dw.datamodelrobot.evidence.TypeHint
import org.dw.datamodelrobot.phase0.EntityIndex
import org.dw.datamodelrobot.relation.RelationCandidate
import org.dw.datamodelrobot.relation.RelationGraph
import org.dw.datamodelrobot.relation.SupportTier
import org.dw.datamodelrobot.relation.TargetResolver
import org.dw.datamodelrobot.relation.TypeMatch
import org.dw.datamodelrobot.schema.DbSchema
import org.dw.datamodelrobot.schema.SchemaTable

/**
 * 冲突检测。①③④⑤ 只吃 Step 1-3 已经算好的信号（[RelationCandidate.signals] / competingTargets）
 * 与 [ColumnReferenceIndex]，不重新扫代码；② 要读查询链路，在 [IsolationScopeCollector] 里单独算好后传进来。
 *
 * 严重度口径（写在这里而不是散在各处）：
 * - ① 按不匹配的性质分三档：data_type 就不同 = HIGH（隐式转换 + 索引失效 + 值域可能对不上）、
 *   unsigned/符号差异 = MEDIUM、只有长度精度差异 = LOW。**候选本身只有弱命名证据时一律降到 LOW** ——
 *   关系还没坐实，谈它的类型不匹配意义不大（实测项目里这类占大半）。
 * - ② 见 [IsolationScopeCollector]：deleted = MEDIUM、tenant = LOW，且只打在 JOIN 链路上。
 * - ③ 消歧不了 = HIGH（研发只能猜）、消歧结论与「按权重挑」不一致 = MEDIUM（照着分数挑会挑错，
 *   Step 1-3 实测到的 `promotion_combination_product.activity_id` 就是这种）、一致 = LOW（只是记录）。
 *   五条消歧依据住在 [TargetResolver]；其中「代码侧类型提示」要传 [hints] 才生效，不传就只有 schema 侧四条。
 * - ④ MEDIUM：两处代码对同一对表给出了相反的方向，至少有一处是错的。
 * - ⑤ LOW：孤儿字段是清理线索不是错误，且口径再宽也可能漏（见 [ColumnReferenceIndex]）。
 */
object ConflictDetector {

    /** 框架自动注入、业务代码不会显式引用的列。租户插件会给每条 SQL 自动加 `tenant_id` 条件 */
    val FRAMEWORK_MANAGED_COLUMNS = setOf("tenant_id")

    /**
     * @param isolation ② 的结果，由 [IsolationScopeCollector.collect] 算好后传入（它要读查询链路，
     *   而其余四条规则只需要关系图与引用索引）
     * @param hints 代码侧类型提示（[org.dw.datamodelrobot.evidence.TypeHintIndex.collect]），③的第五条消歧依据；不传就只有 schema 侧四条
     */
    fun detect(
        graph: RelationGraph,
        schema: DbSchema,
        refs: ColumnReferenceIndex.Result,
        index: EntityIndex? = null,
        isolation: List<Conflict> = emptyList(),
        hints: Map<String, TypeHint> = emptyMap(),
    ): List<Conflict> = (
        typeMismatch(graph) + isolation + ambiguousTargets(graph, hints) + reciprocal(graph) +
            orphanColumns(schema, refs, index)
        ).sortedWith(compareBy({ it.type.ordinal }, { it.severity.ordinal }, { it.subject }))

    fun typeMismatch(graph: RelationGraph): List<Conflict> = graph.relations.mapNotNull { c ->
        val from = c.signals.fromType ?: return@mapNotNull null
        val to = c.signals.toType ?: return@mapNotNull null
        val (label, severity) = when (c.signals.typeMatch) {
            TypeMatch.DIFFERENT -> "data_type 不同" to Severity.HIGH
            TypeMatch.BASE_SAME ->
                if (("unsigned" in from.lowercase()) != ("unsigned" in to.lowercase())) {
                    "符号不一致" to Severity.MEDIUM
                } else {
                    "长度/精度不一致" to Severity.LOW
                }
            else -> return@mapNotNull null
        }
        Conflict(
            type = ConflictType.TYPE_MISMATCH,
            severity = if (c.tier == SupportTier.WEAK) Severity.LOW else severity,
            subject = c.key.text,
            detail = "$label：${c.key.fromEnd} `$from` vs ${c.key.toEnd} `$to`" +
                if (c.tier == SupportTier.WEAK) "（候选只有弱命名证据，降级为 LOW）" else "",
            refs = c.locations,
        )
    }.sortedWith(compareBy({ it.severity.ordinal }, { it.subject }))

    fun ambiguousTargets(graph: RelationGraph, hints: Map<String, TypeHint> = emptyMap()): List<Conflict> =
        graph.competing().toSortedMap().map { (fromEnd, list) ->
            val r = TargetResolver.resolve(list, hints)
            val severity = when {
                !r.resolved -> Severity.HIGH
                r.contradictsWeightOrder -> Severity.MEDIUM
                else -> Severity.LOW
            }
            Conflict(
                type = ConflictType.AMBIGUOUS_TARGET,
                severity = severity,
                subject = fromEnd,
                detail = "$fromEnd 有 ${list.size} 个落点: " + r.ordered.take(5).joinToString {
                    "${it.key.toEnd}[${it.tier.name}" +
                        (if (it.codeBacked) ",code" else "") +
                        (it.namingMatch?.let { m -> ",${m.name.lowercase()}" } ?: "") + "]"
                } + if (list.size > 5) " …等 ${list.size} 个" else "" +
                    // 查过代码侧但仍选不出来时如实说出来：这比「消歧不了」四个字有用，人可以直接去看那几个文件
                    if (!r.resolved && r.typeHint != null) "；代码侧类型提示 ${r.typeHint.describe()}" else "",
                refs = list.flatMap { it.locations }.distinct().sorted(),
                resolution = r.winner?.let { "${it.key.toEnd}（${r.reason}）" },
            )
        }

    /** ④ from/to 互换的两个候选同时存在：A 处写 `order.userId = user.id`、B 处写 `user.orderId = order.id` */
    fun reciprocal(graph: RelationGraph): List<Conflict> {
        val byKey = graph.byKey()
        return graph.relations
            .filter { it.key.text < it.key.reversed.text } // 一对只报一次
            .mapNotNull { c ->
                val other = byKey[c.key.reversed.text] ?: return@mapNotNull null
                Conflict(
                    type = ConflictType.RECIPROCAL_INCONSISTENT,
                    severity = Severity.MEDIUM,
                    subject = "${c.key.text} ↔ ${other.key.text}",
                    detail = "两处代码对同一对表给出了相反的方向，至少有一处是错的：" +
                        "${c.key.fromEnd} 指向 ${c.key.toEnd}（${c.sources.joinToString { it.name }}），" +
                        "反过来也成立（${other.sources.joinToString { it.name }}）",
                    refs = (c.locations + other.locations).distinct().sorted(),
                )
            }
            .sortedBy { it.subject }
    }

    /**
     * ⑤ 孤儿字段。口径是 [ColumnReferenceIndex] 的四个口径**全部**没命中（方法引用 /
     * getter·setter·builder 调用名 / 字符串字面量 / XML 分词）。Step 1-4a 只看方法引用时报出 85 个，
     * 抽样 6 个全是假阳性；补上后三个口径只剩 1 个 —— 这才是能拿去讨论「要不要清理」的清单。
     *
     * [index] 用来拿实体属性名拼访问器名（`@TableField` 改过名时列名推不出属性名）；传 null 就只按列名推。
     */
    fun orphanColumns(
        schema: DbSchema,
        refs: ColumnReferenceIndex.Result,
        index: EntityIndex? = null,
        tables: List<SchemaTable> = schema.includedTables,
    ): List<Conflict> {
        val propertyOf = index?.entitiesByFqn?.values
            ?.flatMap { e -> e.columns.values.map { "${e.tableName}.${it.column}".lowercase() to it.property } }
            ?.toMap()
            .orEmpty()
        return tables.flatMap { t ->
            t.columns
                .filter { it.name.endsWith("_id", ignoreCase = true) && it.name.lowercase() !in FRAMEWORK_MANAGED_COLUMNS }
                .filterNot { refs.isReferenced(t.name, it.name, propertyOf["${t.name}.${it.name}".lowercase()]) }
                .map { c ->
                    Conflict(
                        type = ConflictType.ORPHAN_COLUMN,
                        severity = Severity.LOW,
                        subject = "${t.name}.${c.name}",
                        detail = "`${c.fullType}`" +
                            (c.comment?.takeIf { it.isNotBlank() }?.let { "，注释「$it」" } ?: "，无注释") +
                            (if (c.key == "PRI") "，是主键列" else "") +
                            " —— 方法引用 / getter·setter·builder 调用 / 字符串列名 / XML 四个口径都没出现过",
                    )
                }
        }.sortedBy { it.subject }
    }
}
