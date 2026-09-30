package org.dw.datamodelrobot.toolwindow

import org.dw.datamodelrobot.output.Mermaid

/**
 * 表名模糊搜索。**不能用 `TreeSpeedSearch`** —— 它只搜树里已经渲染出来的节点，而搜索的全部意义
 * 正是找到当前没展开（甚至还没建出来）的表，所以要一份独立索引 + 独立搜索框。
 *
 * 三个字段都做大小写不敏感的**子串**匹配：表名、库注释、实体 Javadoc。中文项目里用户记得住「订单」
 * 记不住 `trade_order`，只匹配表名的话这个搜索框基本没用。
 *
 * 命中排序从硬到软：表名精确 > 表名前缀 > 表名子串 > 库注释 > 实体 Javadoc，同级按表名升序。
 * 表名整体排在注释之前，是因为它更可能正是用户要找的那张；反过来会让一批「注释里也带订单」的表
 * 把真正的 `trade_order` 挤下去。
 */
class TableIndex(tables: List<TableView>) {

    private val byName: Map<String, TableView> = tables.associateBy { it.name.lowercase() }

    val size: Int get() = byName.size

    /** 小写表名集合，给业务范围的「失效表」判定用（范围里的表可能已在最新事实源里消失） */
    val knownNames: Set<String> get() = byName.keys

    /** 大小写不敏感取单张表 */
    operator fun get(name: String): TableView? = byName[name.lowercase()]

    /**
     * 空白查询返回**空列表**而不是全表：搜索框刚打开时不该糊一脸几百张表。
     *
     * **不截断** —— 命中多少条交给调用方显示（「共 N 张表匹配」）。静默砍掉会让人以为库里没有那张表，
     * 与事实源「不过滤，只标记」的立场一致。
     */
    fun search(query: String): List<TableView> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return byName.values.mapNotNull { t -> rank(t, q)?.let { it to t } }
            .sortedWith(compareBy({ it.first }, { it.second.name }))
            .map { it.second }
    }

    /** 命中档位，越小越靠前；null = 没命中 */
    private fun rank(t: TableView, q: String): Int? {
        val name = t.name.lowercase()
        return when {
            name == q -> 0
            name.startsWith(q) -> 1
            name.contains(q) -> 2
            contains(t.comment, q) -> 3
            contains(t.entityDoc, q) -> 4
            else -> null
        }
    }

    private fun contains(haystack: String?, needle: String): Boolean =
        haystack?.lowercase()?.contains(needle) == true
}

/** 一张表的列。只取面板与 ER 图要用的字段，`defaultValue`/`extra` 留在 JSON 里给 MCP 与 Skill。 */
data class ColumnView(
    val name: String,
    val position: Int,
    /** `data_type`，如 `bigint`。ER 图的类型位置**只能放它** —— mermaid 的类型词法不容空格与逗号 */
    val type: String,
    /** `column_type`，如 `bigint unsigned`。精度与符号信息只在这里 */
    val fullType: String,
    val nullable: Boolean,
    /** information_schema 的 `COLUMN_KEY`：`PRI` / `UNI` / `MUL` / null */
    val key: String?,
    val comment: String?,
)

data class TableView(
    val name: String,
    val module: String,
    /** 数据库表注释 */
    val comment: String?,
    /** 实体类 Javadoc 首行（代码侧事实，原样） */
    val entityDoc: String?,
    val columns: List<ColumnView>,
    val uniqueColumns: Set<String> = emptySet(),
    val foreignKeyColumns: Set<String> = emptySet(),
) {
    /**
     * 树与搜索结果里显示的一行中文名：库注释优先、实体 Javadoc 兜底。
     *
     * 走 [Mermaid.cleanAlias]（剔 HTML 标签与 `{@link}`、去掉结尾的「DO」、超 30 字符截断），
     * **与图上表框里的中文名是同一个口径** —— 否则同一张表在树里叫一个名、在图里叫另一个名。
     * 要看未清洗的原文用 [comment] / [entityDoc]（详情面板用）。
     */
    val label: String? get() = Mermaid.cleanAlias(comment) ?: Mermaid.cleanAlias(entityDoc)
}
