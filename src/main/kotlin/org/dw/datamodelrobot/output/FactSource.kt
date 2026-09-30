package org.dw.datamodelrobot.output

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import org.dw.datamodelrobot.conflict.Conflict
import org.dw.datamodelrobot.conflict.Severity
import org.dw.datamodelrobot.evidence.Evidence
import org.dw.datamodelrobot.evidence.EvidenceType
import org.dw.datamodelrobot.schema.DbSchema
import org.dw.datamodelrobot.schema.SchemaTable
import org.dw.datamodelrobot.score.ConfidenceBand
import org.dw.datamodelrobot.score.ConfidenceScorer
import org.dw.datamodelrobot.score.ScoredGraph
import org.dw.datamodelrobot.score.ScoredRelation
import java.nio.file.Files
import java.nio.file.Path

/**
 * JSON 事实源 —— 四层架构里唯一的事实来源，MCP 只读它、Skill 只补它。
 *
 * 三条硬要求：
 * 1. **可 diff**：键序 = DTO 声明序，表/模块/关系按名字排序，时间戳由调用方传入 →
 *    同一份输入渲染两次字节完全一致，可以直接进版本库
 * 2. **可解释**：每条关系都带证据（含 `文件:行号`）、惩罚算式、消歧结论；`scoring` 段把公式与系数
 *    写进产物本身，读它的人（含 Phase 3 的 LLM）不用去翻代码，也没法自己另立一套分数
 * 3. **不过滤**：落选的候选、库里不存在的表、下不了结论的冲突全部留在里面，只压分 + 打标记 ——
 *    悄悄丢掉就等于把「证据不自洽」这件事藏起来了
 *
 * `conflicts[].subject` 与 `relations[].text` 同一套写法（①④用完整 key，③⑤用 `表.列`，②用 `文件:行号 方法 → 表.列`），
 * 需要按关系聚合冲突时按这个字段 join。
 *
 * `adjacency` 是**按表组织的邻接视图**：`{ 表名: { out: […], in: […] } }`。它是 `relations` 的**派生视图**，
 * 每次渲染重算、不单独维护 —— `relations` 才是权威，两者不一致时以 `relations` 为准。
 */
object FactSource {

    const val FORMAT = "datamodelrobot/2"

    private val GSON = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun render(
        schema: DbSchema,
        project: String,
        generatedAt: String,
        scored: ScoredGraph? = null,
        conflicts: List<Conflict> = emptyList(),
        entityDocs: Map<String, String> = emptyMap(),
    ): String {
        val stats = schema.stats
        val relations = scored?.relations.orEmpty()
        return GSON.toJson(
            Root(
                format = FORMAT,
                project = project,
                generatedAt = generatedAt,
                source = Source(schema.providerId, schema.dbms, schema.dbmsVersion, schema.catalog, schema.server),
                stats = Stats(
                    tables = stats.tables,
                    columns = stats.columns,
                    indexes = stats.indexes,
                    foreignKeys = stats.foreignKeys,
                    foreignKeyMappings = stats.foreignKeyMappings,
                    commentedTables = stats.commentedTables,
                    commentedColumns = stats.commentedColumns,
                    tablesMissingLabel = schema.includedTables.count {
                        Mermaid.cleanAlias(it.comment) == null && entityDocs[it.name.lowercase()] == null
                    },
                    excludedTables = schema.excludedTables.size,
                    views = schema.views.size,
                    relations = relations.size,
                    relationsByBand = ConfidenceBand.entries.associate { it.name to relations.count { r -> r.band == it } },
                    relationsNeedingReview = relations.count { it.needsReview },
                    relationsUnaligned = relations.count { !it.schemaAligned },
                    evidences = relations.sumOf { it.candidate.evidences.size },
                    conflicts = conflicts.size,
                    conflictsBySeverity = Severity.entries.associate { it.name to conflicts.count { c -> c.severity == it } },
                    conflictsNeedingReview = conflicts.count { it.needsReview },
                ),
                scoring = scoring(),
                modules = schema.modules().map { (name, tables) -> Module(name, tables.map { it.name }) },
                excludedTables = schema.excludedTables.map { it.name }.sorted(),
                tables = schema.includedTables.sortedBy { it.name }.map { toTable(it, entityDocs) },
                relations = relations.map { toRelation(it) },
                adjacency = adjacency(relations, schema),
                conflicts = conflicts.map { toConflict(it) },
            ),
        )
    }

    fun write(
        schema: DbSchema,
        project: String,
        generatedAt: String,
        outFile: Path,
        scored: ScoredGraph? = null,
        conflicts: List<Conflict> = emptyList(),
        entityDocs: Map<String, String> = emptyMap(),
    ): Path {
        outFile.parent?.let { Files.createDirectories(it) }
        Files.writeString(outFile, render(schema, project, generatedAt, scored, conflicts, entityDocs))
        return outFile
    }

    /** 公式与系数写进产物：读事实源的人不用翻代码就知道分是怎么来的 */
    private fun scoring() = Scoring(
        formula = "confidence = round2( min(noisyOr(evidence.weight), cap) × Π penalty.factor )",
        base = "noisyOr(w) = 1 - Π(1 - wᵢ)。证据按独立性假设做「或」：求和会超过 1，" +
            "还会把「同一个事实被写了五遍」当成五份独立证据",
        cap = ConfidenceScorer.CAP,
        bands = ConfidenceBand.entries.associate { it.name to it.min },
        solidLineMin = ConfidenceBand.MEDIUM.min,
        /** 证据源的中文展示名：读产物的人（面板、MCP、Skill）不用再记 NAMING_CONVENTION 是什么缩写 */
        sourceLabels = EvidenceType.entries.associate { it.name to it.labelZh },
        penalties = listOf(
            PenaltyDoc("type_mismatch", Severity.HIGH.name, ConfidenceScorer.TYPE_MISMATCH_FACTOR.getValue(Severity.HIGH),
                "① data_type 就不同（bigint 连 varchar）：多半根本不是外键关系"),
            PenaltyDoc("type_mismatch", Severity.MEDIUM.name, ConfidenceScorer.TYPE_MISMATCH_FACTOR.getValue(Severity.MEDIUM),
                "① unsigned/符号差异：关系大概率是真的，JOIN 是坏的"),
            PenaltyDoc("type_mismatch", Severity.LOW.name, ConfidenceScorer.TYPE_MISMATCH_FACTOR.getValue(Severity.LOW),
                "① 只有长度/精度差异，或候选本身只有弱命名证据"),
            PenaltyDoc("ambiguous_loser", null, ConfidenceScorer.AMBIGUOUS_LOSER, "③ 消歧选中了别的落点，本条是竞争者"),
            PenaltyDoc("ambiguous_unresolved", null, ConfidenceScorer.AMBIGUOUS_UNRESOLVED,
                "③ 四条依据全并列，选不出来 —— 每条落点都扣，不悄悄选一个"),
            PenaltyDoc("ambiguous_winner", null, ConfidenceScorer.AMBIGUOUS_WINNER, "③ 胜出，但 schema 层面存在竞争落点"),
            PenaltyDoc("reciprocal", null, ConfidenceScorer.RECIPROCAL, "④ 反向关系也被推断出来了，两处代码至少有一处写错"),
            PenaltyDoc("direction_unresolved", null, ConfidenceScorer.DIRECTION_UNRESOLVED,
                "键元组并列，from/to 可能互换，而事实源断言了方向。与 reciprocal 只取最重的一条"),
            PenaltyDoc("target_not_key", null, ConfidenceScorer.TARGET_NOT_KEY, "落点在库里既不是主键也不是唯一键"),
        ),
        notScored = listOf(
            "② MISSING_TENANT_SCOPE / MISSING_LOGICAL_DELETE：查询链路的问题，不影响「这条关系是否存在」，" +
                "只标 needsReview（折算成分数会让关系图失真）",
            "schema 未对齐（代码引用的表/列不在本库）：证据权重已被 SchemaAligner ×0.5，不二次扣分，只标 needsReview",
        ),
    )

    /**
     * 邻接视图：每条关系在**两端各记一次** —— from 端记进 `out`（本表持外键指向别人），
     * to 端记进 `in`（别人持外键指向本表）。所以条目总数恒为 `relations.size × 2`
     * （自环会同时落进同一张表的 out 与 in；聚合层已把自环丢掉，见 `GraphStats.selfPairsSkipped`）。
     *
     * `fromColumn`/`toColumn` 保持关系自己的方向（from = 外键侧），**不随所在的桶翻转** ——
     * 这样才能拼回 `relations[].text` 去取完整证据链。方向语义由 out/in 表达：用户问的是
     * 「这张表跟谁有关系」，而 ER 基数必须有方向，两者都要，所以拆成两个桶而不是一个无向清单。
     *
     * 只收录**至少参与一条关系**的表：不在 `adjacency` 里就等于没有任何推断关系，全库表清单看
     * `tables`/`modules`。桶内按邻居表名与列名排序、不按分数（与 `relations` 同一口径）：
     * 分数的微小变化不该让整段 diff 重排。
     */
    private fun adjacency(relations: List<ScoredRelation>, schema: DbSchema): Map<String, Adjacency> {
        val out = LinkedHashMap<String, MutableList<Neighbor>>()
        val incoming = LinkedHashMap<String, MutableList<Neighbor>>()
        for (r in relations) {
            val k = r.key
            val cardinality = Cardinality.of(k, schema).notation
            out.getOrPut(k.fromTable) { mutableListOf() } +=
                Neighbor(k.toTable, k.fromColumn, k.toColumn, r.confidence, r.band.name, r.solid, cardinality)
            incoming.getOrPut(k.toTable) { mutableListOf() } +=
                Neighbor(k.fromTable, k.fromColumn, k.toColumn, r.confidence, r.band.name, r.solid, cardinality)
        }
        val byNeighbor = compareBy<Neighbor>({ it.table }, { it.fromColumn }, { it.toColumn })
        return (out.keys + incoming.keys).distinct().sorted().associateWith { t ->
            Adjacency(
                out = out[t].orEmpty().sortedWith(byNeighbor),
                incoming = incoming[t].orEmpty().sortedWith(byNeighbor),
            )
        }
    }

    private fun toRelation(r: ScoredRelation): Relation {
        val c = r.candidate
        return Relation(
            text = c.key.text,
            key = Key(c.key.fromTable, c.key.fromColumn, c.key.toTable, c.key.toColumn),
            confidence = r.confidence,
            band = r.band.name,
            solid = r.solid,
            baseScore = r.baseScore,
            tier = c.tier.name,
            schemaAligned = c.schemaAligned,
            needsReview = r.needsReview,
            reviewReasons = r.reviewReasons,
            explain = r.explain(),
            penalties = r.penalties.map { Penalty(it.trigger, it.factor, it.reason) },
            ambiguity = r.ambiguity?.let { AmbiguityDto(it.targets, it.outcome.name, it.reason) },
            signals = Signals(
                c.signals.fromTableInSchema, c.signals.toTableInSchema,
                c.signals.fromColumnInSchema, c.signals.toColumnInSchema,
                c.signals.fromType, c.signals.toType, c.signals.typeMatch.name,
                c.signals.toIsPrimaryKey, c.signals.toIsUniqueKey, c.signals.fromIndexed,
                c.signals.sameModule, c.signals.sharedNameSegments,
            ),
            sources = c.evidences.map { it.type.name }.distinct().sorted(),
            locations = c.locations,
            orientedBy = c.orientedBy.sorted(),
            evidences = c.evidences.map { toEvidence(it) },
        )
    }

    private fun toEvidence(e: Evidence) = EvidenceDto(
        type = e.type.name,
        weight = e.weight,
        schemaAligned = e.schemaAligned,
        from = toRef(e.from.table, e.from.column, e.from.entityFqn, e.from.file, e.from.line, e.from.refText),
        to = toRef(e.to.table, e.to.column, e.to.entityFqn, e.to.file, e.to.line, e.to.refText),
        joinType = e.joinType,
        callName = e.callName,
        enclosingMethod = e.enclosingMethod,
        scenario = e.scenario,
        notes = e.notes,
    )

    private fun toRef(table: String, column: String, fqn: String?, file: String?, line: Int, refText: String?) =
        Ref(table, column, fqn, file?.substringAfterLast('/'), line.takeIf { it > 0 }, refText)

    private fun toConflict(c: Conflict) = ConflictDto(
        type = c.type.name,
        severity = c.severity.name,
        subject = c.subject,
        detail = c.detail,
        refs = c.refs,
        resolution = c.resolution,
        needsReview = c.needsReview,
    )

    private fun toTable(t: SchemaTable, entityDocs: Map<String, String>) = Table(
        name = t.name,
        module = t.module,
        comment = t.comment,
        /** 实体类 Javadoc 首行（代码侧事实，原样）。表注释缺失时图上的中文名就是它 */
        entityDoc = entityDocs[t.name.lowercase()],
        engine = t.engine,
        columns = t.columns.map {
            Column(it.name, it.position, it.type, it.fullType, it.nullable, it.key, it.defaultValue, it.extra, it.comment)
        },
        indexes = t.indexes.map { Index(it.name, it.unique, it.columns) },
        foreignKeys = t.foreignKeys.map {
            ForeignKey(it.name, it.columns, it.refTable, it.refColumns, it.onDelete, it.onUpdate)
        },
    )

    private data class Root(
        val format: String,
        val project: String,
        val generatedAt: String,
        val source: Source,
        val stats: Stats,
        val scoring: Scoring,
        val modules: List<Module>,
        val excludedTables: List<String>,
        val tables: List<Table>,
        val relations: List<Relation>,
        val adjacency: Map<String, Adjacency>,
        val conflicts: List<ConflictDto>,
    )

    private data class Source(
        val provider: String,
        val dbms: String,
        val dbmsVersion: String?,
        val catalog: String,
        val server: String?,
    )

    private data class Stats(
        val tables: Int,
        val columns: Int,
        val indexes: Int,
        val foreignKeys: Int,
        val foreignKeyMappings: Int,
        val commentedTables: Int,
        val commentedColumns: Int,
        val tablesMissingLabel: Int,
        val excludedTables: Int,
        val views: Int,
        val relations: Int,
        val relationsByBand: Map<String, Int>,
        val relationsNeedingReview: Int,
        val relationsUnaligned: Int,
        val evidences: Int,
        val conflicts: Int,
        val conflictsBySeverity: Map<String, Int>,
        val conflictsNeedingReview: Int,
    )

    private data class Scoring(
        val formula: String,
        val base: String,
        val cap: Double,
        val bands: Map<String, Double>,
        val solidLineMin: Double,
        val sourceLabels: Map<String, String>,
        val penalties: List<PenaltyDoc>,
        val notScored: List<String>,
    )

    private data class PenaltyDoc(val trigger: String, val severity: String?, val factor: Double, val why: String)

    private data class Module(val name: String, val tables: List<String>)

    private data class Table(
        val name: String,
        val module: String,
        val comment: String?,
        val entityDoc: String?,
        val engine: String?,
        val columns: List<Column>,
        val indexes: List<Index>,
        val foreignKeys: List<ForeignKey>,
    )

    private data class Column(
        val name: String,
        val position: Int,
        val type: String,
        val fullType: String,
        val nullable: Boolean,
        val key: String?,
        val defaultValue: String?,
        val extra: String?,
        val comment: String?,
    )

    private data class Index(val name: String, val unique: Boolean, val columns: List<String>)

    private data class ForeignKey(
        val name: String,
        val columns: List<String>,
        val refTable: String,
        val refColumns: List<String>,
        val onDelete: String?,
        val onUpdate: String?,
    )

    private data class Relation(
        val text: String,
        val key: Key,
        val confidence: Double,
        val band: String,
        val solid: Boolean,
        val baseScore: Double,
        val tier: String,
        val schemaAligned: Boolean,
        val needsReview: Boolean,
        val reviewReasons: List<String>,
        val explain: String,
        val penalties: List<Penalty>,
        val ambiguity: AmbiguityDto?,
        val signals: Signals,
        val sources: List<String>,
        val locations: List<String>,
        val orientedBy: List<String>,
        val evidences: List<EvidenceDto>,
    )

    private data class Key(val fromTable: String, val fromColumn: String, val toTable: String, val toColumn: String)

    /**
     * 一张表的两个邻接桶。`in` 是 Kotlin 硬关键字，所以字段名叫 [incoming]、JSON 键名靠注解钉成 `in`
     * —— 读产物的人（含 LLM）看到的是 `out`/`in` 这对最直白的方向词。
     */
    private data class Adjacency(
        val out: List<Neighbor>,
        @SerializedName("in") val incoming: List<Neighbor>,
    )

    /**
     * 一个邻居。[table] 是**另一端**的表名；[fromColumn]/[toColumn] 沿用关系自己的方向
     * （from = 持外键的一侧），与所在桶无关。[cardinality] 是 mermaid 的基数记号
     * （如 `||--o{`，左 = 落点侧、右 = 外键侧），口径见 [Cardinality]，与 `.mmd` 产物里画的完全一致。
     */
    private data class Neighbor(
        val table: String,
        val fromColumn: String,
        val toColumn: String,
        val confidence: Double,
        val band: String,
        val solid: Boolean,
        val cardinality: String,
    )

    private data class Penalty(val trigger: String, val factor: Double, val reason: String)

    private data class AmbiguityDto(val targets: List<String>, val outcome: String, val reason: String?)

    private data class Signals(
        val fromTableInSchema: Boolean,
        val toTableInSchema: Boolean,
        val fromColumnInSchema: Boolean,
        val toColumnInSchema: Boolean,
        val fromType: String?,
        val toType: String?,
        val typeMatch: String,
        val toIsPrimaryKey: Boolean,
        val toIsUniqueKey: Boolean,
        val fromIndexed: Boolean,
        val sameModule: Boolean,
        val sharedNameSegments: Int,
    )

    private data class EvidenceDto(
        val type: String,
        val weight: Double,
        val schemaAligned: Boolean,
        val from: Ref,
        val to: Ref,
        val joinType: String?,
        val callName: String?,
        val enclosingMethod: String?,
        val scenario: String?,
        val notes: List<String>,
    )

    private data class Ref(
        val table: String,
        val column: String,
        val entityFqn: String?,
        val file: String?,
        val line: Int?,
        val refText: String?,
    )

    private data class ConflictDto(
        val type: String,
        val severity: String,
        val subject: String,
        val detail: String,
        val refs: List<String>,
        val resolution: String?,
        val needsReview: Boolean,
    )
}
