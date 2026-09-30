package org.dw.datamodelrobot.schema

/** 命名匹配强度。分强度而不是二值，是为了让「像」和「很像」在权重上分开。 */
enum class NameMatch {
    /** 表名就是列名基（`spu_id` → `spu` / `spus`） */
    EXACT,

    /** 去掉模块前缀后完全相等（`user_id` → `member_user` / `system_users`）—— 这类脚手架的主流形态 */
    MODULE_PREFIX,

    /** 只有尾段相等（`sku_id` → `stock_item_sku`、`user_id` → `system_dept_user`）。召回用，权重要低一档 */
    TAIL_ONLY,
}

/**
 * 列名 ↔ 表名的命名约定匹配。这类脚手架的 `xxx_id` 绝大多数指向「去掉模块前缀就是 xxx」的表，
 * 但要处理复数（`system_users`）和多模块前缀（`member_user` / `system_users` 都可能是 `user_id` 的目标）。
 *
 * 匹配上多个候选不是错误，而是**多义指向**信号（Step 1-4 的冲突规则③）：全部产出、标出来，
 * 靠 import/包路径消歧或压低置信度，**不在这里悄悄选一个**。
 */
object TableNaming {

    /** `user_id` → `user`；不是 `xxx_id` 形状（含裸 `id`）返回 null */
    fun base(column: String): String? {
        val lower = column.lowercase()
        if (!lower.endsWith("_id") || lower.length <= 3) return null
        return lower.removeSuffix("_id")
    }

    fun match(column: String, tableName: String): NameMatch? {
        val b = base(column) ?: return null
        val t = tableName.lowercase()
        if (sameName(t, b)) return NameMatch.EXACT
        if (t.contains('_') && sameName(t.substringAfter('_'), b)) return NameMatch.MODULE_PREFIX
        if (t.contains('_') && sameName(t.substringAfterLast('_'), b)) return NameMatch.TAIL_ONLY
        return null
    }

    fun matches(column: String, tableName: String): Boolean = match(column, tableName) != null

    fun candidates(column: String, tables: Iterable<SchemaTable>): List<Pair<SchemaTable, NameMatch>> =
        tables.mapNotNull { t -> match(column, t.name)?.let { t to it } }

    private fun sameName(candidate: String, base: String): Boolean =
        candidate == base || candidate == base + "s" || singular(candidate) == base

    private fun singular(s: String): String = when {
        s.endsWith("ies") && s.length > 3 -> s.dropLast(3) + "y"
        s.endsWith("ses") && s.length > 3 -> s.dropLast(2)
        s.endsWith("s") && !s.endsWith("ss") -> s.dropLast(1)
        else -> s
    }
}
