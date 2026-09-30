package org.dw.datamodelrobot.output

import org.dw.datamodelrobot.relation.RelationKey
import org.dw.datamodelrobot.schema.DbSchema
import org.dw.datamodelrobot.schema.SchemaColumn
import org.dw.datamodelrobot.schema.SchemaTable

/**
 * ER 基数口径 —— 从 schema 推，不猜。
 *
 * 落点是主键/唯一键 → `||`（可空则 `|o`）；落点不唯一 → `}o`（这时它压根不是一对多，画成一对多会骗人）；
 * 外键列自己就是主键/唯一键 → 右边收成一个（共享主键的扩展表 = 1:1）；其余按可空性给 `o{` / `|{`。
 * 库里查不到的表/列按最保守的画（左 `|o`、右 `o{`）：事实源不过滤未对齐关系，基数也不能装作知道。
 *
 * **全项目只此一份**：Mermaid 的关系行与事实源 `adjacency[].cardinality` 都走这里。
 * 两处各写一遍必然漂移，而基数标错比不标更坏 —— 读图的人会照着它写 JOIN。
 *
 * 别和 `RelationInference` 里的 `toIsUniqueKey` 信号合并：那是**只认单列唯一索引**的窄口径（喂消歧与打分），
 * 这里还要认 `COLUMN_KEY` 的 `PRI`/`UNI`。合并会改掉打分信号的语义，Step 1-3 的断言会跟着变。
 */
object Cardinality {

    /**
     * 一对 mermaid 基数记号：[left] 是落点（被指向、通常是「一」的一方），[right] 是外键列所在表
     * （通常是「多」的一方）。
     *
     * [notation] 固定用实线连接符 `--`：虚实线表达的是**置信度**（见 `ScoredRelation.solid`），
     * 不是基数，所以基数记号里不带它 —— 要画线用 [line]。
     */
    data class Markers(val left: String, val right: String) {

        val notation: String get() = "$left--$right"

        /** 关系行里的那一段：实线 = 可采信，虚线 = 低于 0.60，去核对一眼 */
        fun line(solid: Boolean): String = if (solid) "$left--$right" else "$left..$right"
    }

    fun of(key: RelationKey, schema: DbSchema): Markers {
        val to = endpoint(schema, key.toTable, key.toColumn)
        val from = endpoint(schema, key.fromTable, key.fromColumn)
        return Markers(left = to?.let(::leftOf) ?: "|o", right = from?.let(::rightOf) ?: "o{")
    }

    private fun endpoint(schema: DbSchema, table: String, column: String): Endpoint? =
        schema.table(table)?.let { t -> t.column(column)?.let { c -> Endpoint(t, c) } }

    private data class Endpoint(val table: SchemaTable, val column: SchemaColumn)

    /** 落点侧：唯一就是「一」，不唯一则**不是一对多** —— 画成多对多，别骗人 */
    private fun leftOf(e: Endpoint): String = when {
        isUnique(e.table, e.column) -> if (e.column.nullable) "|o" else "||"
        else -> "}o"
    }

    /** 外键侧：外键列自己就是主键/唯一键 = 共享主键的扩展表，1:1 */
    private fun rightOf(e: Endpoint): String = when {
        isUnique(e.table, e.column) -> if (e.column.nullable) "|o" else "||"
        else -> if (e.column.nullable) "o{" else "|{"
    }

    /** 这一列在这张表里是否唯一：`COLUMN_KEY` 是 PRI/UNI，或它是某个唯一索引的唯一一列 */
    fun isUnique(table: SchemaTable, column: SchemaColumn): Boolean =
        column.key.equals("PRI", ignoreCase = true) ||
            column.key.equals("UNI", ignoreCase = true) ||
            table.indexes.any { it.unique && it.columns.singleOrNull()?.equals(column.name, ignoreCase = true) == true }
}
