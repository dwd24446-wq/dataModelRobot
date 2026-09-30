package org.dw.datamodelrobot.evidence

import org.dw.datamodelrobot.schema.DbSchema
import org.dw.datamodelrobot.schema.SchemaIndex
import org.dw.datamodelrobot.schema.SchemaTable

/**
 * schema 侧证据「同名列配对」：A 的**索引**列元组与 B 的**主键/唯一键**列元组同名同序 → 逐列配对。
 * 抓的是命名约定抓不到的那批：复合自然键（Quartz 的 `SCHED_NAME/JOB_NAME/JOB_GROUP`）、
 * 非 `xxx_id` 形状的引用（`order_no` → 唯一键 `order_no`）、以及共享父表主键的扩展表。
 *
 * 三条收窄规则，每条都是实测出来的（上百张表的真实库）：
 * 1. **A 侧必须有索引** —— MySQL 本来就要求外键列有索引；无索引的同名列多半是巧合。
 *    放开这条会多出几十条候选，全是唯一索引列撞同名外键列这类。
 * 2. **单列且 A 侧就是自己的 PRIMARY 时不产证据** —— 全库每张表都有 `id`，不排就变成
 *    否则就是上百张表两两配对的完全图（实测上万条噪声），排掉后只剩几十条原始命中。
 * 3. **多表共享同一键元组时选一个父表**，不产对称的两条。方向靠出度（普通索引的条数）：
 *    扩展表（子类型表）的键完全继承父表、自己不再有别的引用，所以「还有别的普通索引指向别表键」的
 *    那一方是父表。选不出来（并列）就按表名取一个并标 `direction_unresolved`，不装作知道方向。
 *
 * B 侧是主键用基础权重 .90；只是唯一键（1:1 关联表那种）降到 [UNIQUE_TARGET_WEIGHT] —— 能连，
 * 但它不是外键，不该压过命名证据指向的真父表。
 */
object KeyTupleEvidenceCollector {

    const val UNIQUE_TARGET_WEIGHT = 0.70

    private const val PRIMARY = "PRIMARY"

    fun collect(schema: DbSchema, tables: List<SchemaTable> = schema.includedTables): List<Evidence> {
        val keys = tables.flatMap { t -> keyIndexes(t).map { idx -> Tuple(t, idx) } }
        val keysByTuple = keys.groupBy { it.lowered }
        val matches = rawMatches(tables, keysByTuple)
        if (matches.isEmpty()) return emptyList()

        val outDegree = matches.filter { !it.fromIndex.unique }.groupingBy { it.fromTable.name }.eachCount()
        val parents = keysByTuple.mapValues { (tuple, owners) -> electParent(owners, outDegree) }

        val out = mutableListOf<Evidence>()
        // 同一对 (A, 元组) 可能被 A 的多个索引命中（PRIMARY 之外还有冗余唯一键），只产一条
        for ((_, group) in matches.groupBy { Triple(it.fromTable.name, it.tuple, parents.getValue(it.tuple).owner.table.name) }) {
            val first = group.first()
            val target = parents.getValue(first.tuple)
            val to = target.owner
            if (to.table.name == first.fromTable.name) continue // 自己就是选出来的父表，不产自环
            val primaryTarget = isPrimary(to.index)
            val weight = if (primaryTarget) EvidenceType.KEY_TUPLE.baseWeight else UNIQUE_TARGET_WEIGHT
            val notes = buildList {
                // 方向依据：from 侧是普通索引 → 唯一性就能定；from 侧自己也是键 → 只能靠等价类选父表
                add("oriented_by=" + if (first.fromIndex.unique) "key_class_parent" else "key_uniqueness")
                add(
                    "key_tuple=${first.fromTable.name}.${first.fromIndex.name}" +
                        "(${first.fromIndex.columns.joinToString(",")})=${to.table.name}.${to.index.name}",
                )
                add("key_kind=" + if (primaryTarget) "primary" else "unique")
                if (target.classSize > 1) {
                    add("key_class_size=${target.classSize}")
                    if (target.unresolved && first.fromIndex.unique) add("direction_unresolved")
                }
                if (group.size > 1) add("matched_indexes=${group.size}")
            }
            first.tuple.indices.forEach { i ->
                out += Evidence(
                    type = EvidenceType.KEY_TUPLE,
                    from = ColumnRef(first.fromTable.name, first.fromIndex.columns[i]),
                    to = ColumnRef(to.table.name, to.index.columns[i]),
                    weight = weight,
                    notes = notes,
                )
            }
        }
        return out.sortedBy { it.key }
    }

    private data class Tuple(val table: SchemaTable, val index: SchemaIndex) {
        val lowered: List<String> = index.columns.map { it.lowercase() }
    }

    private data class Match(val fromTable: SchemaTable, val fromIndex: SchemaIndex, val tuple: List<String>)

    private data class Parent(val owner: Tuple, val classSize: Int, val unresolved: Boolean)

    private fun keyIndexes(t: SchemaTable): List<SchemaIndex> =
        t.indexes.filter { isPrimary(it) || it.unique }.sortedByDescending { isPrimary(it) }

    private fun isPrimary(i: SchemaIndex): Boolean = i.name.equals(PRIMARY, ignoreCase = true)

    private fun rawMatches(tables: List<SchemaTable>, keysByTuple: Map<List<String>, List<Tuple>>): List<Match> {
        val out = mutableListOf<Match>()
        for (t in tables) {
            for (idx in t.indexes) {
                val tuple = idx.columns.map { it.lowercase() }
                val owners = keysByTuple[tuple] ?: continue
                // 规则 2：单列 + 自己的主键 = 每张表都有的 `id`，排掉否则全库互联
                if (tuple.size == 1 && isPrimary(idx)) continue
                if (owners.none { it.table.name != t.name }) continue
                out += Match(t, idx, tuple)
            }
        }
        return out
    }

    private fun electParent(owners: List<Tuple>, outDegree: Map<String, Int>): Parent {
        fun deg(name: String) = outDegree[name] ?: 0

        val distinct = owners.distinctBy { it.table.name }
        if (distinct.size == 1) return Parent(distinct.first(), 1, unresolved = false)
        val ranked = distinct.sortedWith(compareBy({ -deg(it.table.name) }, { it.table.name }))
        return Parent(ranked[0], distinct.size, unresolved = ranked.count { deg(it.table.name) == deg(ranked[0].table.name) } > 1)
    }
}
