package org.dw.datamodelrobot.relation

import org.dw.datamodelrobot.schema.SchemaTable

/**
 * 拿 schema 里**声明的外键**当 ground truth 核对推断结果。
 *
 * 只用来验证推断机制本身：实测项目的显式外键约束全部落在 `QRTZ_*` 框架表内部，
 * 业务表一条外键都没有，所以业务侧真值走人工核对样本（见 Step 1-3 报告），不能拿这里的结果当质量指标。
 *
 * 按列序逐条核对：第 i 列必须配到被指向表的第 i 列，配到别的列上算 [Mapping.orderMismatch]
 * （复合键顺序错了，JOIN 出来的结果是错的，比漏掉更危险）。
 */
object ForeignKeyCheck {

    data class Mapping(
        val constraint: String,
        val position: Int,
        val fromTable: String,
        val fromColumn: String,
        val toTable: String,
        val toColumn: String,
        val hit: Boolean,
        /** 这一列确实指向了目标表，但配到了别的列上 */
        val orderMismatch: Boolean,
    ) {
        val text: String get() = "$fromTable.$fromColumn->$toTable.$toColumn"
    }

    data class Result(
        val mappings: List<Mapping>,
        /** 推断出来、至少一端落在有声明外键的表上、但自己没有声明的表对 */
        val extraTablePairs: List<String>,
    ) {
        val constraints: List<String> get() = mappings.map { it.constraint }.distinct()
        val constraintCount: Int get() = constraints.size
        val mappingCount: Int get() = mappings.size
        val mappingHits: Int get() = mappings.count { it.hit }
        val missed: List<Mapping> get() = mappings.filterNot { it.hit }
        val orderMismatches: List<Mapping> get() = mappings.filter { it.orderMismatch }

        /** 全部列映射都命中的约束 */
        val constraintsHit: List<String>
            get() = constraints.filter { c -> mappings.filter { it.constraint == c }.all { it.hit } }

        val declaredTablePairs: Set<String> get() = mappings.map { "${it.fromTable}->${it.toTable}" }.toSet()
    }

    fun check(graph: RelationGraph, tables: List<SchemaTable>): Result {
        val inferred = graph.relations.map { it.key }.toSet()
        val declared = tables.flatMap { t -> t.foreignKeys.map { fk -> t to fk } }

        val mappings = declared.flatMap { (t, fk) ->
            fk.columns.mapIndexed { i, col ->
                val toColumn = fk.refColumns.getOrElse(i) { "" }
                val key = RelationKey(t.name.lowercase(), col.lowercase(), fk.refTable.lowercase(), toColumn.lowercase())
                Mapping(
                    constraint = fk.name,
                    position = i,
                    fromTable = t.name,
                    fromColumn = col,
                    toTable = fk.refTable,
                    toColumn = toColumn,
                    hit = key in inferred,
                    orderMismatch = key !in inferred &&
                        inferred.any { it.fromEnd == key.fromEnd && it.toTable == key.toTable && it.toColumn != key.toColumn },
                )
            }
        }

        // 精度视角：至少一端是「有声明外键的表」、但这对关系没被声明。
        // Quartz 里这类多半是语义真实、只是官方没建约束的关系，报告要能看出来是哪一种，不能只报一个精度数字
        val declaredTables = (declared.map { it.first.name } + declared.map { it.second.refTable })
            .map { it.lowercase() }.toSet()
        val declaredPairs = mappings.map { "${it.fromTable}->${it.toTable}".lowercase() }.toSet()
        val extra = graph.relations.map { it.key.tablePair }.distinct()
            .filter { it.substringBefore("->") in declaredTables || it.substringAfter("->") in declaredTables }
            .filterNot { it in declaredPairs }
            .sorted()

        return Result(mappings, extra)
    }
}
