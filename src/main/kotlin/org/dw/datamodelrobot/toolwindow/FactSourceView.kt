package org.dw.datamodelrobot.toolwindow

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.dw.datamodelrobot.evidence.EvidenceType
import org.dw.datamodelrobot.output.FactSource
import org.dw.datamodelrobot.schema.SchemaTables
import java.util.Locale

/**
 * 事实源 JSON 的**只读视图** —— 工具窗口只显示与跳转，不参与任何推断。
 *
 * 手工从 [JsonObject] 取值、不用 Gson 反射填 data class：Gson 走 Unsafe 实例化、不执行 Kotlin
 * 默认值，而事实源**故意不写 null 字段**（`Ref.line` 缺失就是「这条证据没有代码出处」），
 * 反射进来会静默变成 null 再在后期 NPE。字段缺失在这里必须显式处理成「没有」。
 *
 * 只解析面板要用的那部分字段，其余（`scoring` 全文、列的 `defaultValue`/`extra`）留在 JSON 里给
 * MCP 与 Skill。`tables` 段原先是**故意不解析**的（理由是「表结构 Database 工具窗口看得比这好，
 * 不重复造」）—— Phase 4 要按表搜索、按表出图，这条取舍推翻：表名、注释、字段现在是面板的一等数据。
 */
data class RefView(
    val table: String,
    val column: String,
    val entityFqn: String?,
    /** 事实源只留文件名（绝对路径随机器变就没法 diff），跳转要靠 `FilenameIndex` 反查 */
    val file: String?,
    /** 1-based；null = 这条证据没有代码出处（schema 侧命名/键元组证据就是） */
    val line: Int?,
    val refText: String?,
) {
    val location: String? get() = file?.let { f -> line?.let { "$f:$it" } ?: f }
}

data class EvidenceView(
    val type: String,
    /** 中文展示名：优先用事实源 `scoring.sourceLabels` 里的口径，旧产物缺 legend 时回落枚举 */
    val typeLabel: String,
    val weight: Double,
    val schemaAligned: Boolean,
    val from: RefView,
    val to: RefView,
    val joinType: String?,
    val callName: String?,
    val enclosingMethod: String?,
    val scenario: String?,
    val notes: List<String>,
) {
    /** 能跳的那一侧。from 优先：外键列所在的一侧才是代码可能写错的地方 */
    val jump: RefView? get() = from.takeIf { it.file != null } ?: to.takeIf { it.file != null }
}

data class PenaltyView(val trigger: String, val factor: Double, val reason: String)

data class SignalsView(
    val fromTableInSchema: Boolean,
    val toTableInSchema: Boolean,
    val fromType: String?,
    val toType: String?,
    val typeMatch: String,
    val toIsPrimaryKey: Boolean,
    val toIsUniqueKey: Boolean,
    val fromIndexed: Boolean,
    val sameModule: Boolean,
    val sharedNameSegments: Int,
)

data class RelationView(
    val text: String,
    val fromTable: String,
    val fromColumn: String,
    val toTable: String,
    val toColumn: String,
    val confidence: Double,
    val band: String,
    val solid: Boolean,
    val baseScore: Double,
    val tier: String,
    val schemaAligned: Boolean,
    val needsReview: Boolean,
    val reviewReasons: List<String>,
    val explain: String,
    val penalties: List<PenaltyView>,
    val ambiguityTargets: List<String>,
    val ambiguityOutcome: String?,
    val ambiguityReason: String?,
    val signals: SignalsView?,
    val evidences: List<EvidenceView>,
    /** `adjacency.out[].cardinality` 的原始记号；基数只在生成端推导一次。 */
    val cardinality: String,
) {
    /** 与 [org.dw.datamodelrobot.output.Mermaid.byModule] 同一口径：按 from 端表前缀分组 */
    val module: String get() = SchemaTables.moduleOf(fromTable)

    val label: String
        get() = "${num(confidence)}  $text" + if (needsReview) "  [需复核]" else ""
}

data class ConflictView(
    val type: String,
    val severity: String,
    val subject: String,
    val detail: String,
    val refs: List<String>,
    val resolution: String?,
    val needsReview: Boolean,
) {
    val label: String get() = "$severity  $type  $subject" + if (needsReview) "  [需复核]" else ""
}

/**
 * @param formatMismatch 事实源的 `format` 与当前 [FactSource.FORMAT] 不一致 —— 说明磁盘上是旧版产物，
 *   字段可能对不上。面板据此提示重扫，而不是硬着头皮显示半截数据
 */
data class FactSourceView(
    val format: String,
    val project: String,
    val generatedAt: String,
    val tableCount: Int,
    val relationCount: Int,
    val conflictCount: Int,
    val needsReviewCount: Int,
    val tables: List<TableView>,
    val relations: List<RelationView>,
    val conflicts: List<ConflictView>,
) {
    val formatMismatch: Boolean get() = format != FactSource.FORMAT

    /** 表名/注释/Javadoc 的模糊搜索索引。建一次就够（几百张表），所以懒加载后一直复用 */
    val tableIndex: TableIndex by lazy { TableIndex(tables) }

    /** 模块 → 关系，模块名升序；组内按置信度降序、同分按 text（阅读时先看最可信的） */
    fun relationsByModule(): Map<String, List<RelationView>> = relations
        .groupBy { it.module }
        .toSortedMap()
        .mapValues { (_, list) -> list.sortedWith(compareBy({ -it.confidence }, { it.text })) }

    /** 冲突按严重度 HIGH→MEDIUM→LOW 排，同级按 subject；未知严重度排最后 */
    fun sortedConflicts(): List<ConflictView> = conflicts.sortedWith(
        compareBy({ severityRank(it.severity) }, { it.type }, { it.subject }),
    )

    private fun severityRank(severity: String): Int = SEVERITY_ORDER.indexOf(severity).let {
        if (it < 0) SEVERITY_ORDER.size else it
    }

    companion object {
        private val SEVERITY_ORDER = listOf("HIGH", "MEDIUM", "LOW")

        fun parse(json: String): FactSourceView {
            val root = JsonParser.parseString(json).asJsonObject
            val stats = root.obj("stats")
            val labels = sourceLabels(root)
            val cardinalities = cardinalities(root)
            return FactSourceView(
                format = root.str("format") ?: "",
                project = root.str("project") ?: "",
                generatedAt = root.str("generatedAt") ?: "",
                tableCount = stats?.int("tables") ?: 0,
                relationCount = stats?.int("relations") ?: 0,
                conflictCount = stats?.int("conflicts") ?: 0,
                needsReviewCount = stats?.int("relationsNeedingReview") ?: 0,
                tables = root.arr("tables").map { toTable(it) },
                relations = root.arr("relations").map { toRelation(it, labels, cardinalities) },
                conflicts = root.arr("conflicts").map { toConflict(it) },
            )
        }

        /** 证据源中文标签的 legend（`scoring.sourceLabels`，由插件写入）。缺 legend 就走枚举里的同一份口径 */
        private fun sourceLabels(root: JsonObject): Map<String, String> =
            root.obj("scoring")?.obj("sourceLabels")?.entrySet()
                ?.associate { (k, v) -> k to v.asString }
                .orEmpty()

        private fun label(type: String, labels: Map<String, String>): String =
            labels[type] ?: EvidenceType.labelZhOf(type)

        private fun relationKey(fromTable: String, fromColumn: String, toTable: String, toColumn: String) =
            listOf(fromTable, fromColumn, toTable, toColumn).joinToString("\u0000") { it.lowercase() }

        private fun cardinalities(root: JsonObject): Map<String, String> = buildMap {
            val adjacency = root.obj("adjacency") ?: return@buildMap
            for ((table, buckets) in adjacency.entrySet()) {
                if (!buckets.isJsonObject) continue
                for (neighbor in buckets.asJsonObject.arr("out")) {
                    val key = relationKey(
                        table, neighbor.str("fromColumn") ?: "",
                        neighbor.str("table") ?: "", neighbor.str("toColumn") ?: "",
                    )
                    neighbor.str("cardinality")?.let { put(key, it) }
                }
            }
        }

        private fun toTable(o: JsonObject) = TableView(
            name = o.str("name") ?: "",
            module = o.str("module") ?: "",
            comment = o.str("comment"),
            entityDoc = o.str("entityDoc"),
            columns = o.arr("columns").map { c ->
                ColumnView(
                    name = c.str("name") ?: "",
                    position = c.int("position") ?: 0,
                    type = c.str("type") ?: "",
                    fullType = c.str("fullType") ?: "",
                    nullable = c.bool("nullable"),
                    key = c.str("key"),
                    comment = c.str("comment"),
                )
            },
            uniqueColumns = o.arr("indexes").filter { it.bool("unique") }
                .mapNotNull { it.strArr("columns").singleOrNull()?.lowercase() }.toSet(),
            foreignKeyColumns = o.arr("foreignKeys").flatMap { it.strArr("columns") }
                .map { it.lowercase() }.toSet(),
        )

        private fun toRelation(o: JsonObject, labels: Map<String, String>, cardinalities: Map<String, String>): RelationView {
            val key = o.obj("key")
            val ambiguity = o.obj("ambiguity")
            return RelationView(
                text = o.str("text") ?: "",
                fromTable = key?.str("fromTable") ?: "",
                fromColumn = key?.str("fromColumn") ?: "",
                toTable = key?.str("toTable") ?: "",
                toColumn = key?.str("toColumn") ?: "",
                confidence = o.dbl("confidence"),
                band = o.str("band") ?: "",
                solid = o.bool("solid"),
                baseScore = o.dbl("baseScore"),
                tier = o.str("tier") ?: "",
                schemaAligned = o.bool("schemaAligned"),
                needsReview = o.bool("needsReview"),
                reviewReasons = o.strArr("reviewReasons"),
                explain = o.str("explain") ?: "",
                penalties = o.arr("penalties").map {
                    PenaltyView(it.str("trigger") ?: "", it.dbl("factor"), it.str("reason") ?: "")
                },
                ambiguityTargets = ambiguity?.strArr("targets").orEmpty(),
                ambiguityOutcome = ambiguity?.str("outcome"),
                ambiguityReason = ambiguity?.str("reason"),
                signals = o.obj("signals")?.let { s ->
                    SignalsView(
                        s.bool("fromTableInSchema"), s.bool("toTableInSchema"),
                        s.str("fromType"), s.str("toType"), s.str("typeMatch") ?: "",
                        s.bool("toIsPrimaryKey"), s.bool("toIsUniqueKey"), s.bool("fromIndexed"),
                        s.bool("sameModule"), s.int("sharedNameSegments") ?: 0,
                    )
                },
                evidences = o.arr("evidences").map { toEvidence(it, labels) },
                cardinality = cardinalities[relationKey(
                    key?.str("fromTable") ?: "", key?.str("fromColumn") ?: "",
                    key?.str("toTable") ?: "", key?.str("toColumn") ?: "",
                )] ?: "|o--o{",
            )
        }

        private fun toEvidence(o: JsonObject, labels: Map<String, String>) = EvidenceView(
            type = o.str("type") ?: "",
            typeLabel = label(o.str("type") ?: "", labels),
            weight = o.dbl("weight"),
            schemaAligned = o.bool("schemaAligned"),
            from = toRef(o.obj("from")),
            to = toRef(o.obj("to")),
            joinType = o.str("joinType"),
            callName = o.str("callName"),
            enclosingMethod = o.str("enclosingMethod"),
            scenario = o.str("scenario"),
            notes = o.strArr("notes"),
        )

        private fun toRef(o: JsonObject?) = RefView(
            table = o?.str("table") ?: "",
            column = o?.str("column") ?: "",
            entityFqn = o?.str("entityFqn"),
            file = o?.str("file"),
            line = o?.int("line"),
            refText = o?.str("refText"),
        )

        private fun toConflict(o: JsonObject) = ConflictView(
            type = o.str("type") ?: "",
            severity = o.str("severity") ?: "",
            subject = o.str("subject") ?: "",
            detail = o.str("detail") ?: "",
            refs = o.strArr("refs"),
            resolution = o.str("resolution"),
            needsReview = o.bool("needsReview"),
        )
    }
}

// 事实源不写 null 字段，所以每个取值都要显式区分「缺失」与「值为 null」

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { !it.isJsonNull }?.asString

private fun JsonObject.bool(key: String): Boolean =
    get(key)?.takeIf { !it.isJsonNull }?.asBoolean ?: false

private fun JsonObject.int(key: String): Int? =
    get(key)?.takeIf { !it.isJsonNull }?.asInt

private fun JsonObject.dbl(key: String): Double =
    get(key)?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0

private fun JsonObject.obj(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.arr(key: String): List<JsonObject> =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asJsonObject }.orEmpty()

private fun JsonObject.strArr(key: String): List<String> =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString }.orEmpty()

/** 定 Locale.ROOT：显示口径不随机器语言变小数点，与事实源一致 */
internal fun num(v: Double): String = String.format(Locale.ROOT, "%.2f", v)
