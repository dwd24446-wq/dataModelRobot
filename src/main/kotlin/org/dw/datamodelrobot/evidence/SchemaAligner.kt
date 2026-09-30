package org.dw.datamodelrobot.evidence

import org.dw.datamodelrobot.schema.DbSchema

/**
 * 代码侧证据与 schema 对齐：引用了库里不存在的表/列，说明代码与库已经漂移
 * （改名、删列、或代码本身就是错的），这种证据必须降权并留下痕迹，不能悄悄丢掉。
 */
object SchemaAligner {

    /** 降权系数。最终置信度公式在 Step 1-5 定，这里只负责「不对齐就打对折 + 记原因」。 */
    const val MISSING_PENALTY = 0.5

    fun align(evidences: List<Evidence>, schema: DbSchema): List<Evidence> =
        evidences.map { align(it, schema) }

    fun align(e: Evidence, schema: DbSchema): Evidence {
        if (!e.schemaAligned) return e // 幂等：重复对齐不会二次打折
        val problems = listOfNotNull(missing(e.from, schema), missing(e.to, schema))
        if (problems.isEmpty()) return e
        return e.copy(
            weight = e.weight * MISSING_PENALTY,
            schemaAligned = false,
            notes = e.notes + problems,
        )
    }

    private fun missing(ref: ColumnRef, schema: DbSchema): String? {
        val table = schema.table(ref.table) ?: return "table_missing_in_schema:${ref.table}"
        if (table.column(ref.column) == null) return "column_missing_in_schema:$ref"
        return null
    }
}
