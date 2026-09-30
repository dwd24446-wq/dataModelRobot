package org.dw.datamodelrobot.output

import org.dw.datamodelrobot.schema.DbSchema
import org.dw.datamodelrobot.schema.SchemaTable
import org.dw.datamodelrobot.schema.SchemaTables
import org.dw.datamodelrobot.score.ConfidenceBand
import org.dw.datamodelrobot.score.ScoredRelation
import java.util.Locale

/**
 * Mermaid ER 渲染。**Mermaid 是默认视图**：它的关系线支持实线/虚线，正好用来表达置信度
 * （DBML 的关系语法不支持样式，表达不了置信度，所以 DBML 是可选后置）。
 *
 * 两条约定：
 * - **虚实线** = 置信度是否过 [ConfidenceBand.MEDIUM] 的线（0.60）。虚线不是「错」，是「去核对一眼」
 * - **方向** 写成 `被指向的表 <基数>--<基数> 持有外键的表`，即 ER 语义里「一」的一方在左边
 *
 * 基数从 schema 推导，口径在 [Cardinality]（事实源的 `adjacency` 段共用同一份，别在这里另写一套）。
 *
 * 一张图几百条关系是画不出来的，所以 [byModule] 按 **from 端**表的模块前缀分图（这类脚手架的表前缀
 * 天然是模块边界），跨模块的落点作为裸实体出现在图里；本模块没有任何关系指向的表以空框补齐，
 * 所以**模块图合起来就是全库**，不再单独出一张总览图（全库一张图必然超 [MAX_TEXT_CHARS]）。
 *
 * **表对象带中文注释**：每个有注释的表在图头声明一行 `表名["表名 中文注释"]`（mermaid 的实体别名），
 * 注释优先取数据库表注释、缺失时取实体类 Javadoc 首行。别名走独立声明行而不是写在关系行里，
 * 一张表只花一行字符。图里仍**不画字段**：字段块会把图推过 mermaid 的 `maxTextSize` 硬墙，详见该常量。
 */
object Mermaid {

    /**
     * mermaid 的 `maxTextSize` 默认 **50,000 字符**，超了不是警告也不是截断，而是把整份源码换成
     * `graph TB;a[Maximum text size in diagram exceeded]` —— 一张红框，图全没了。
     *
     * 它同时在 mermaid 的 `secure` 列表里（`secure:["secure","securityLevel","startOnLoad",
     * "maxTextSize","suppressErrorRendering","maxEdges"]`），所以**文件内写 `%%{init: {"maxTextSize": N}}%%`
     * 抬不动它**（mermaid 自己的报错文案：*You cannot set this config via configuration inside the
     * diagram as it is a secure config*），IDE 自带插件也没有 `mermaid.initialize` 的调用点可改。
     * 唯一出路是**产物自己待在限内**：超了就按分数从高到低砍，并把砍了多少写进图头。
     * 留 2% 余量给表名长度与关系数的自然增长。字符数不是字节数（CJK 注释 1 字符 = 3 字节）。
     */
    const val MAX_TEXT_CHARS = 49_000

    /**
     * 表注释在图上的长度上限。真实项目的表注释最长 15 字符（实测），30 足够表达、
     * 又能挡住「注释写成整段说明」的项目把字符预算吃掉。
     */
    const val MAX_ALIAS_CHARS = 30

    data class Options(
        /** 低于这个分数的关系不画。默认全画，靠虚实线区分 */
        val minConfidence: Double = 0.0,
        /**
         * 是否画「代码引用了、库里没有的表」。默认不画：这些表会作为幽灵实体出现在图上，
         * 而 ER 图描述的是**这个库**。它们仍在事实源里（标注 + 压分，见 FactSource 的决策①）
         */
        val includeUnaligned: Boolean = false,
        val title: String? = null,
        /** 单张图最多画多少条，超出的按分数取高的。0 = 不限 */
        val maxRelations: Int = 0,
        /**
         * 单张图的字符预算，超了按分数从高到低砍到装得下。默认就是 mermaid 的硬上限留 2% 余量；
         * 之所以是个参数而不是直接读常量，是为了让「砍」这条路径能被测试覆盖（真库上很难自然超限）
         */
        val maxTextChars: Int = MAX_TEXT_CHARS,
        /** 表名（小写）→ 实体类 Javadoc 首行，来自 [org.dw.datamodelrobot.phase0.EntityIndex.docsByTable] */
        val entityDocs: Map<String, String> = emptyMap(),
        /** 关掉就不声明任何别名，表框里只剩表名 */
        val tableComments: Boolean = true,
    )

    fun render(relations: List<ScoredRelation>, schema: DbSchema, options: Options = Options()): String =
        fitted(relations, schema, options, emptyList(), options.title ?: schema.catalog)

    /**
     * 按 from 端表的模块前缀分图。返回的 map 按模块名排序，每个模块一张 `erDiagram`，
     * 图里含本模块的全部表（没被任何关系指向的以空框补齐）+ 跨模块的落点。
     * 一条关系都画不出来的模块不出图（实测项目就有整片这样的模块：表全不在本库），
     * 否则只会产出一堆只有表头的空文件。
     */
    fun byModule(
        relations: List<ScoredRelation>,
        schema: DbSchema,
        options: Options = Options(),
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val byModule = schema.includedTables.groupBy { it.module }
        for ((module, list) in relations.groupBy { SchemaTables.moduleOf(it.key.fromTable) }.toSortedMap()) {
            if (pick(list, schema, options).isEmpty()) continue
            out[module] = fitted(
                list, schema, options, byModule[module].orEmpty(),
                options.title?.let { "$it · $module 模块" } ?: "$module 模块",
            )
        }
        return out
    }

    /**
     * 渲染一张图，并保证它不超过 [Options.maxTextChars]：超了就二分找出「分数最高的多少条能装下」，
     * 用 [Options.maxRelations] 这条既有机制重画。**不静默截断**——图头会写明砍到了多少条、为什么。
     */
    private fun fitted(
        relations: List<ScoredRelation>,
        schema: DbSchema,
        options: Options,
        allTables: List<SchemaTable>,
        title: String,
    ): String {
        val all = pick(relations, schema, options)
        // 裸实体名单按**未截断**的关系算一次就固定下来：截断会让本来被关系提到的表掉出来，
        // 若跟着重算，砍掉一条关系反而可能加长文本，二分就不单调了
        val mentioned = all.flatMap { listOf(it.key.toTable, it.key.fromTable) }.toSet()
        val orphans = allTables.filter { it.name.lowercase() !in mentioned }.sortedBy { it.name }
        val total = all.size
        fun build(limit: Int): String {
            val opts = if (limit in 1 until total) options.copy(maxRelations = limit) else options
            return diagram(pick(relations, schema, opts), relations.size, orphans, schema, opts, title, limit in 1 until total)
        }
        val full = build(0)
        if (full.length <= options.maxTextChars) return full
        var lo = 1
        var hi = total
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (build(mid).length <= options.maxTextChars) lo = mid else hi = mid - 1
        }
        return build(lo)
    }

    private fun diagram(
        picked: List<ScoredRelation>,
        inputCount: Int,
        orphans: List<SchemaTable>,
        schema: DbSchema,
        options: Options,
        title: String,
        trimmed: Boolean,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("%% dataModelRobot · $title")
        sb.appendLine("%% 关系 ${picked.size} 条（输入 $inputCount 条） · 实线 = confidence >= ${num(ConfidenceBand.MEDIUM.min)}（可采信）" +
            " · 虚线 = 低于该线，去核对")
        sb.appendLine("%% 写法：被指向的表 ||--o{ 持有外键的表 : \"外键列 置信度\"；基数由 schema 推导")
        if (options.tableComments) {
            sb.appendLine("%% 表框里的中文注释：优先数据库表注释，缺失时用实体类 Javadoc 首行")
        }
        if (trimmed) {
            sb.appendLine("%% ⚠ 原图超出 mermaid 的 maxTextSize（50,000 字符，secure 项，文件内抬不动），" +
                "已按分数从高到低砍到 ${picked.size} 条；完整关系见事实源 JSON 与模块图")
        }
        if (picked.size < inputCount && !trimmed) {
            sb.appendLine("%% 已过滤：minConfidence=${num(options.minConfidence)}" +
                (if (options.maxRelations > 0) " / maxRelations=${options.maxRelations}" else "") +
                (if (options.includeUnaligned) "" else " / 不含库里没有的表"))
        }
        sb.appendLine("erDiagram")
        val drawn = drawnTables(picked, orphans)
        val labels =
            if (options.tableComments) drawn.mapNotNull { n -> tableLabel(n, schema, options)?.let { n to it } }.toMap()
            else emptyMap()
        for (name in drawn) {
            val alias = labels[name.lowercase()] ?: continue
            sb.appendLine("    $name[\"$name $alias\"]")
        }
        for (r in picked) {
            val line = Cardinality.of(r.key, schema).line(r.solid)
            sb.appendLine("    ${r.key.toTable} $line ${r.key.fromTable} : \"${label(r)}\"")
        }
        val unnamed = orphans.filter { it.name.lowercase() !in labels }
        if (unnamed.isNotEmpty()) {
            sb.appendLine("    %% 下面 ${unnamed.size} 张表在库里、但没推断出任何关系（空框）")
            for (t in unnamed) sb.appendLine("    ${t.name}")
        }
        return sb.toString()
    }

    /** 图里会出现的表：关系两端的表 + 本模块补齐的空框表，按表名排序去重 */
    private fun drawnTables(picked: List<ScoredRelation>, orphans: List<SchemaTable>): List<String> =
        (picked.flatMap { listOf(it.key.toTable, it.key.fromTable) } + orphans.map { it.name })
            .distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

    /**
     * 表框上显示的中文注释：数据库表注释优先（库里的是什么就是什么），
     * 缺失时用实体类 Javadoc 首行。两边都拿不到或清洗后为空 → null，图里只画表名。
     */
    fun tableLabel(name: String, schema: DbSchema, options: Options): String? =
        cleanAlias(schema.table(name)?.comment) ?: cleanAlias(options.entityDocs[name.lowercase()])

    /**
     * 把注释洗成能安全放进 `["..."]` 的一段文本：`{@link X}` 取 X、去掉 HTML 标签与 mermaid
     * 词法里的结构字符（引号、方括号、花括号、尖括号、竖线、冒号、反引号），折行成一行，
     * 去掉 Javadoc 惯用的结尾「DO」（`学生 DO` → `学生`），超 [MAX_ALIAS_CHARS] 截断加省略号。
     */
    fun cleanAlias(raw: String?): String? {
        val text = raw
            ?.replace(INLINE_TAG) { it.groupValues[1].ifBlank { " " } }
            ?.replace(HTML_TAG, " ")
            ?.replace(BAD_CHARS, " ")
            ?.replace(WHITESPACE, " ")
            ?.trim()
            ?.let { TRAILING_DO.replace(it, "").trim() }
            ?.takeIf { it.isNotEmpty() && !NO_INFO.contains(it.lowercase()) }
            ?: return null
        return if (text.length <= MAX_ALIAS_CHARS) text else text.take(MAX_ALIAS_CHARS - 1).trimEnd() + "…"
    }

    private val INLINE_TAG = Regex("\\{@\\w+\\s+([^}]*)}")
    private val HTML_TAG = Regex("<[^>]*>")
    private val BAD_CHARS = Regex("[\"\\[\\]{}<>|&`:*#%\\\\]")
    private val WHITESPACE = Regex("\\s+")
    private val TRAILING_DO = Regex("(?<![A-Za-z])DOs?$", RegexOption.IGNORE_CASE)

    /** 只有这些字样的注释等于没写，别占图上一行 */
    private val NO_INFO = setOf("null", "todo", "tbd")

    /** 图上的阅读顺序按表名走，不随分数变 —— 同一批关系两次渲染要能逐行 diff */
    private val BY_POSITION = compareBy<ScoredRelation>({ it.key.fromTable }, { it.key.text })

    private fun pick(relations: List<ScoredRelation>, schema: DbSchema, options: Options): List<ScoredRelation> {
        val drawable = relations
            .filter { it.confidence >= options.minConfidence }
            .filter { options.includeUnaligned || (it.schemaAligned && bothTablesInSchema(it, schema)) }
        // 超出上限时留分数最高的几条，但仍按表名排布
        val picked = if (options.maxRelations in 1 until drawable.size) {
            drawable.sortedWith(compareBy({ -it.confidence }, { it.key.text })).take(options.maxRelations)
        } else {
            drawable
        }
        return picked.sortedWith(BY_POSITION)
    }

    private fun bothTablesInSchema(r: ScoredRelation, schema: DbSchema): Boolean =
        schema.table(r.key.fromTable) != null && schema.table(r.key.toTable) != null

    private fun label(r: ScoredRelation): String {
        val column = if (r.key.toColumn.equals("id", ignoreCase = true)) {
            r.key.fromColumn
        } else {
            "${r.key.fromColumn}->${r.key.toColumn}"
        }
        return "$column ${num(r.confidence)}"
    }

    private fun num(v: Double): String = String.format(Locale.ROOT, "%.2f", v)
}
