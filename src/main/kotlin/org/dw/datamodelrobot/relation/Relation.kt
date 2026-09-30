package org.dw.datamodelrobot.relation

import org.dw.datamodelrobot.evidence.ColumnRef
import org.dw.datamodelrobot.evidence.Evidence
import org.dw.datamodelrobot.evidence.EvidenceType
import org.dw.datamodelrobot.evidence.NamingEvidenceCollector
import org.dw.datamodelrobot.schema.NameMatch

/**
 * 关系的身份：`左表.列 → 右表.列`，方向固定为 from = 外键侧（多的一方）→ to = 被指向侧。
 *
 * 表名与列名统一小写：MySQL 列名大小写不敏感，代码侧与 schema 侧的拼写可能不一致
 * （真实项目里确实存在这种大小写不规范的列），不归一就会把一条关系劈成两个候选。
 * 要原始拼写就从 [RelationCandidate.evidences] 里取。
 */
data class RelationKey(
    val fromTable: String,
    val fromColumn: String,
    val toTable: String,
    val toColumn: String,
) {
    val text: String get() = "$fromTable.$fromColumn->$toTable.$toColumn"
    val fromEnd: String get() = "$fromTable.$fromColumn"
    val toEnd: String get() = "$toTable.$toColumn"
    val tablePair: String get() = "$fromTable->$toTable"
    val reversed: RelationKey get() = RelationKey(toTable, toColumn, fromTable, fromColumn)

    companion object {
        fun of(from: ColumnRef, to: ColumnRef) = RelationKey(
            from.table.lowercase(), from.column.lowercase(), to.table.lowercase(), to.column.lowercase(),
        )
    }
}

/** 两侧列的类型比对结果。Step 1-4 的冲突规则①直接吃这个，不在那里重算。 */
enum class TypeMatch {
    /** column_type 完全一致 */
    SAME,

    /** data_type 一致但 column_type 不一致 —— 典型是 `bigint unsigned` vs `bigint`，隐式转换 + 索引失效 */
    BASE_SAME,

    /** data_type 就不一致（`bigint` vs `varchar`） */
    DIFFERENT,

    /** 有一侧的表/列不在 schema 里，无从比对 */
    UNKNOWN,
}

/**
 * schema 侧可核对的事实。全部可空/可判未知，因为代码可能引用库里不存在的表（实测项目就有整片这样的模块）。
 * [fromIndexed] 按 MySQL 最左前缀口径算：列是某个索引的第一列才算「JOIN 能走索引」。
 * [sharedNameSegments] 是两表名按下划线分段后的公共前缀段数（`trade_order_item` vs `trade_order` = 2）——
 * 这类脚手架的子表名就是父表名加后缀，所以它是多义指向消歧最强的 schema 侧信号。
 */
data class RelationSignals(
    val fromTableInSchema: Boolean,
    val toTableInSchema: Boolean,
    val fromColumnInSchema: Boolean,
    val toColumnInSchema: Boolean,
    val fromType: String?,
    val toType: String?,
    val typeMatch: TypeMatch,
    val toIsPrimaryKey: Boolean,
    val toIsUniqueKey: Boolean,
    val fromIndexed: Boolean,
    val sameModule: Boolean,
    val sharedNameSegments: Int,
)

/**
 * 支撑档位。**这不是置信度**（置信度公式在 Step 1-5，要叠冲突惩罚），只是把「凭什么信」粗分三档，
 * 用来控误报：报告按档给比值，人工核对样本从上面两档挑。
 */
enum class SupportTier {
    /** 代码里真写过这条关系：JOIN / 等值条件 / Service 组装 */
    CODE_BACKED,

    /** 只有 schema 侧证据，但结构上说得通：键元组同名同序，或强命中命名约定且指向单列主键 */
    SCHEMA_STRONG,

    /** 只有尾段撞名的弱命名证据（`sku_id` → `stock_item_sku` 这类） */
    WEAK,
}

/**
 * 一条候选关系 = 同一个 [RelationKey] 上聚合的全部证据 + schema 信号。
 * [competingTargets] 是同一个 from 列的其它落点（多义指向），Step 1-4 冲突规则③的输入 ——
 * 这里只标出来，不悄悄选一个。
 */
data class RelationCandidate(
    val key: RelationKey,
    val evidences: List<Evidence>,
    val signals: RelationSignals,
    val tier: SupportTier,
    val competingTargets: List<String> = emptyList(),
) {
    val sources: Set<EvidenceType> get() = evidences.map { it.type }.toSet()
    val maxWeight: Double get() = evidences.maxOf { it.weight }
    val ambiguous: Boolean get() = competingTargets.isNotEmpty()
    val schemaAligned: Boolean get() = evidences.all { it.schemaAligned }
    /** 代码出处 `文件名:行号`；schema 侧证据没有出处，所以这里只列真有文件的那些 */
    val locations: List<String>
        get() = evidences.mapNotNull { e -> e.from.file?.let { "${it.substringAfterLast('/')}:${e.from.line}" } }
            .distinct().sorted()
    val orientedBy: Set<String> get() = evidences.mapNotNull { e -> e.notes.firstOrNull { it.startsWith("oriented_by=") } }.toSet()
    val directionUnresolved: Boolean get() = evidences.any { e -> e.notes.contains("direction_unresolved") }

    /** 有代码撑腰（JOIN / eq / 组装）—— 消歧时这是最硬的信号，代码写死了是哪个实体 */
    val codeBacked: Boolean get() = tier == SupportTier.CODE_BACKED

    /** 命名证据里最强的那一档命中强度；没有命名证据时为 null */
    val namingMatch: NameMatch?
        get() = evidences.mapNotNull { e ->
            e.notes.firstOrNull { it.startsWith(NamingEvidenceCollector.NAMING_NOTE) }
                ?.removePrefix(NamingEvidenceCollector.NAMING_NOTE)
                ?.let { NameMatch.valueOf(it.uppercase()) }
        }.maxByOrNull { it.ordinal }
}

data class GraphStats(
    val evidenceIn: Int,
    val duplicateEvidence: Int,
    val selfPairsSkipped: Int,
    val relations: Int,
    val tablePairs: Int,
    val bySource: Map<EvidenceType, Int>,
    val byTier: Map<SupportTier, Int>,
    /** from/to 互换的两个候选都在 —— Step 1-4 冲突规则④（双向不一致）的输入 */
    val reciprocalPairs: Int,
    /** schema 里 `*_id` 列总数，误报控制的分母 */
    val fkColumns: Int,
    val fkColumnsWithCandidate: Int,
    val fkColumnsAmbiguous: Int,
    /** 一个候选都没有的 `*_id` 列。注意这不是 Step 1-4 的「孤儿字段」（那条看的是代码零引用） */
    val fkColumnsWithoutCandidate: Int,
    val relationsPerFkColumn: Double,
    val typeMismatch: Int,
    val baseTypeMismatch: Int,
    val unaligned: Int,
    val codeBackedUnaligned: Int,
    val unresolvedDirection: Int,
    val ambiguousFromColumns: Int,
)

/** 候选关系图。[relations] 按 key 排序，同一份输入两次推断字节一致（事实源要可 diff）。 */
data class RelationGraph(val relations: List<RelationCandidate>, val stats: GraphStats) {

    fun byKey(): Map<String, RelationCandidate> = relations.associateBy { it.key.text }

    fun byTablePair(): Map<String, List<RelationCandidate>> = relations.groupBy { it.key.tablePair }

    fun byFromColumn(): Map<String, List<RelationCandidate>> = relations.groupBy { it.key.fromEnd }

    /** 多义指向：同一 from 列有 >1 个落点 */
    fun competing(): Map<String, List<RelationCandidate>> = byFromColumn().filter { (_, v) -> v.size > 1 }

    /**
     * 报告用的排序，**不是置信度**：档位 → 最高证据权重 → 证据条数 → key。
     * 置信度是 Step 1-5 的事（要叠冲突惩罚），这里只需要一个稳定可复现的展示顺序。
     */
    fun ranked(): List<RelationCandidate> = relations.sortedWith(
        compareBy({ it.tier.ordinal }, { -it.maxWeight }, { -it.evidences.size }, { it.key.text }),
    )
}
