package org.dw.datamodelrobot.output

import java.util.Locale

/** 带字段的 Mermaid ER 图。输入是事实源的只读投影；不推断关系，也不改置信度。 */
object ErDiagram {

    const val MAX_TEXT_CHARS = 49_000
    const val MAX_SCOPE_TABLES = 25
    const val MAX_NEIGHBORS = 25

    enum class DetailLevel { FULL, WITHOUT_COLUMN_COMMENTS, KEYS_AND_RELATION_COLUMNS }
    enum class Direction { ALL, OUTGOING, INCOMING }

    data class Column(
        val name: String,
        val position: Int,
        val type: String,
        val nullable: Boolean,
        val key: String?,
        val uniqueIndex: Boolean,
        val foreignKey: Boolean,
        val comment: String?,
    )

    data class Table(val name: String, val label: String?, val columns: List<Column>)

    data class Relation(
        val fromTable: String,
        val fromColumn: String,
        val toTable: String,
        val toColumn: String,
        val confidence: Double,
        val solid: Boolean,
        val left: String,
        val right: String,
    )

    data class Options(
        val title: String,
        val minConfidence: Double = 0.0,
        val direction: Direction = Direction.ALL,
        val allowedNeighbors: Set<String>? = null,
        val maxTextChars: Int = MAX_TEXT_CHARS,
        val omittedTables: Int = 0,
    )

    data class Diagram(
        val text: String,
        val tables: List<String>,
        val relationCount: Int,
        val omittedNeighbors: Int = 0,
        val omittedRelations: Int = 0,
        val detailLevel: DetailLevel,
    )

    /** 中心表加置信度最高的至多 25 个不同邻居；一个邻居之间的多条关系会一并保留。 */
    fun neighborhood(
        tableName: String,
        tables: List<Table>,
        relations: List<Relation>,
        options: Options,
    ): Diagram {
        val center = tableName.lowercase()
        val eligible = relations.filter {
            it.confidence >= options.minConfidence && directionMatches(it, center, options.direction)
        }
        val matching = eligible.filter {
            options.allowedNeighbors == null || otherTable(it, center).lowercase() in options.allowedNeighbors
        }
        val neighbors = matching.groupBy { otherTable(it, center).lowercase() }
            .mapValues { (_, edges) -> edges.maxOf { it.confidence } }
            .entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
        val selectedNeighbors = neighbors.take(MAX_NEIGHBORS).map { it.key }
        val selected = (listOf(center) + selectedNeighbors).distinct()
        return render(
            tables = tables,
            relations = matching.filter { otherTable(it, center).lowercase() in selectedNeighbors },
            selectedNames = selected,
            options = options,
            center = center,
            omittedNeighbors = neighbors.size - selectedNeighbors.size,
        )
    }

    /** 渲染选中表诱导出的关系子图。业务范围最多 25 张表，超出时让调用方明确拦截。 */
    fun scope(
        tables: List<Table>,
        relations: List<Relation>,
        selectedNames: Collection<String>,
        options: Options,
    ): Diagram {
        val selected = selectedNames.distinctBy { it.lowercase() }
        require(selected.size <= MAX_SCOPE_TABLES) {
            "业务范围关系图最多 ${MAX_SCOPE_TABLES} 张表，当前选择 ${selected.size} 张"
        }
        val names = selected.map { it.lowercase() }.toSet()
        return render(
            tables = tables,
            relations = relations.filter {
                it.confidence >= options.minConfidence &&
                    it.fromTable.lowercase() in names && it.toTable.lowercase() in names
            },
            selectedNames = selected,
            options = options,
        )
    }

    private fun render(
        tables: List<Table>,
        relations: List<Relation>,
        selectedNames: List<String>,
        options: Options,
        center: String? = null,
        omittedNeighbors: Int = 0,
    ): Diagram {
        require(options.maxTextChars > 0)
        val byName = tables.associateBy { it.name.lowercase() }
        val selectedTables = selectedNames.map { name ->
            byName[name.lowercase()] ?: Table(name, null, emptyList())
        }.distinctBy { it.name.lowercase() }.sortedBy { it.name.lowercase() }
        val selected = selectedTables.map { it.name.lowercase() }.toSet()
        val edges = relations.filter {
            it.fromTable.lowercase() in selected && it.toTable.lowercase() in selected &&
                (center == null || it.fromTable.equals(center, true) || it.toTable.equals(center, true))
        }.distinctBy { listOf(it.fromTable, it.fromColumn, it.toTable, it.toColumn).joinToString("\u0000") }
            .sortedWith(compareByDescending<Relation> { it.confidence }.thenBy { it.fromTable }.thenBy { it.fromColumn }.thenBy { it.toTable }.thenBy { it.toColumn })
        val relatedColumns = relatedColumns(edges)

        for (level in DetailLevel.entries) {
            val full = renderText(selectedTables, edges, options, level, center, omittedNeighbors, null, relatedColumns)
            if (full.length <= options.maxTextChars) {
                return Diagram(full, selectedTables.map { it.name }, edges.size, omittedNeighbors, detailLevel = level)
            }
        }

        // 三级字段降级后仍过预算时，按分数裁关系行并在图头明示，保持 Mermaid 硬限制内。
        val level = DetailLevel.KEYS_AND_RELATION_COLUMNS
        var low = 0
        var high = edges.size
        while (low < high) {
            val mid = (low + high + 1) / 2
            val kept = edges.take(mid)
            val text = renderText(selectedTables, kept, options, level, center, omittedNeighbors, edges.size - mid, relatedColumns(kept))
            if (text.length <= options.maxTextChars) low = mid else high = mid - 1
        }
        val kept = edges.take(low)
        val finalText = renderText(selectedTables, kept, options, level, center, omittedNeighbors, edges.size - low, relatedColumns(kept))
        require(finalText.length <= options.maxTextChars) {
            "即使只保留主键字段，图的表结构也超过 ${options.maxTextChars} 字符预算"
        }
        return Diagram(finalText, selectedTables.map { it.name }, low, omittedNeighbors, edges.size - low, level)
    }

    private fun renderText(
        tables: List<Table>,
        relations: List<Relation>,
        options: Options,
        level: DetailLevel,
        center: String?,
        omittedNeighbors: Int,
        omittedRelations: Int?,
        relatedColumns: Map<String, Set<String>>,
    ): String {
        return buildString {
            appendLine("%% dataModelRobot · ${singleLine(options.title)}")
            appendLine("%% 表 ${tables.size} 张 · 关系 ${relations.size} 条 · 字段标记 PK/UK/FK · 实线 = confidence >= 0.60")
            when (level) {
                DetailLevel.FULL -> appendLine("%% 字段注释完整显示")
                DetailLevel.WITHOUT_COLUMN_COMMENTS -> appendLine("%% 为满足 49,000 字符预算，已省略字段注释")
                DetailLevel.KEYS_AND_RELATION_COLUMNS -> appendLine("%% 为满足 49,000 字符预算，仅显示主键与参与关系的字段")
            }
            if (center != null && omittedNeighbors > 0) {
                appendLine("%% 另有 $omittedNeighbors 个邻居未画，按置信度取前 $MAX_NEIGHBORS")
            }
            if (options.omittedTables > 0) {
                appendLine("%% 范围另有 ${options.omittedTables} 张表未画（已失效或超出 $MAX_SCOPE_TABLES 张上限）")
            }
            if (omittedRelations != null && omittedRelations > 0) {
                appendLine("%% 关系行过多，按置信度保留 ${relations.size} 条，另有 $omittedRelations 条未画")
            }
            appendLine("erDiagram")
            for (table in tables) {
                val name = identifier(table.name)
                val label = cleanTableLabel(table.label)
                val columns = table.columns.sortedWith(compareBy({ it.position }, { it.name }))
                    .filter { c ->
                        level != DetailLevel.KEYS_AND_RELATION_COLUMNS ||
                            c.key.equals("PRI", true) || c.name.lowercase() in relatedColumns[table.name.lowercase()].orEmpty()
                    }
                val declaration = "    $name"
                if (label != null) appendLine("    $name[\"${fieldText(table.name + " " + label)}\"]")
                if (columns.isEmpty()) {
                    if (label == null) appendLine(declaration)
                    continue
                }
                appendLine("$declaration {")
                for (column in columns) {
                    val type = identifier(column.type).ifBlank { "string" }
                    val markers = buildList {
                        if (column.key.equals("PRI", true)) add("PK")
                        else if (column.key.equals("UNI", true) || column.uniqueIndex) add("UK")
                    if (column.foreignKey) add("FK")
                    }.distinct()
                    val mark = markers.takeIf { it.isNotEmpty() }?.joinToString(",", prefix = " ").orEmpty()
                    val comment = if (level == DetailLevel.FULL) cleanFieldComment(column.comment) else null
                    append("      $type ${identifier(column.name)}$mark")
                    if (comment != null) append(" \"$comment\"")
                    appendLine()
                }
                appendLine("    }")
            }
            for (r in relations) {
                val line = if (r.solid) "${r.left}--${r.right}" else "${r.left}..${r.right}"
                appendLine("    ${identifier(r.toTable)} $line ${identifier(r.fromTable)} : \"${fieldText("${r.fromColumn}->${r.toColumn} ${String.format(Locale.ROOT, "%.2f", r.confidence)}")}\"")
            }
        }
    }

    private fun directionMatches(r: Relation, center: String, direction: Direction): Boolean = when (direction) {
        Direction.ALL -> r.fromTable.equals(center, true) || r.toTable.equals(center, true)
        Direction.OUTGOING -> r.fromTable.equals(center, true)
        Direction.INCOMING -> r.toTable.equals(center, true)
    }

    private fun otherTable(r: Relation, center: String): String =
        if (r.fromTable.equals(center, true)) r.toTable else r.fromTable

    private fun relatedColumns(relations: List<Relation>): Map<String, Set<String>> {
        val columns = HashMap<String, MutableSet<String>>()
        for (r in relations) {
            columns.getOrPut(r.fromTable.lowercase()) { mutableSetOf() } += r.fromColumn.lowercase()
            columns.getOrPut(r.toTable.lowercase()) { mutableSetOf() } += r.toColumn.lowercase()
        }
        return columns
    }

    private fun identifier(value: String): String {
        val safe = value.replace(Regex("[^A-Za-z0-9_]"), "_")
        return when {
            safe.isEmpty() -> "unnamed"
            safe.first().isDigit() -> "_${safe}"
            else -> safe
        }
    }

    private fun cleanTableLabel(value: String?): String? =
        Mermaid.cleanAlias(value)?.replace(Regex("[\\r\\n]+"), " ")?.takeIf { it.isNotBlank() }

    /** Mermaid 的字段注释支持标点与 `%`；只剔除双引号和换行，避免破坏字段语法。 */
    private fun cleanFieldComment(value: String?): String? = value
        ?.replace('"', ' ')
        ?.replace(Regex("[\\r\\n]+"), " ")
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private fun fieldText(value: String): String = value
        .replace('"', ' ')
        .replace(Regex("[\\r\\n]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun singleLine(value: String): String = value.replace(Regex("[\\r\\n]+"), " ").trim()
}
