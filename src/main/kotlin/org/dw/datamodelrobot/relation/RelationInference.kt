package org.dw.datamodelrobot.relation

import org.dw.datamodelrobot.evidence.Evidence
import org.dw.datamodelrobot.evidence.EvidenceType
import org.dw.datamodelrobot.schema.DbSchema
import org.dw.datamodelrobot.schema.SchemaTable

/**
 * 关系聚合：把 Step 1-2 的证据流按 `(左表.列 → 右表.列)` 聚成候选关系，并补上 schema 侧信号。
 *
 * 只做聚合与候选生成，**不算置信度**（Step 1-5 才把证据权重和冲突惩罚合成一个分数）。
 * 这里产出的是「一条关系有哪些证据、schema 说得通吗、还有谁在争这个列」，
 * 每一项都能追溯到具体证据的 `文件:行号`，供 explain_relation 回答「凭什么这么连」。
 *
 * [tables] 默认取业务表；要拿显式外键当 ground truth 验证推断机制时传 `schema.tables`
 * （实测项目的显式外键全部落在 `QRTZ_*` 框架表里，见 [ForeignKeyCheck]）。
 */
object RelationInference {

    private val CODE_SOURCES = setOf(EvidenceType.MPJ_JOIN, EvidenceType.LAMBDA_EQ, EvidenceType.SELECT_ASSOCIATION)
    private val STRONG_NAMING = setOf("naming_match=exact", "naming_match=module_prefix")

    fun infer(
        evidences: List<Evidence>,
        schema: DbSchema,
        tables: List<SchemaTable> = schema.includedTables,
    ): RelationGraph {
        val byTable = tables.associateBy { it.name.lowercase() }
        val deduped = evidences.distinctBy { signature(it) }
        val usable = deduped.filterNot { it.from.table.equals(it.to.table, ignoreCase = true) }
        val grouped = usable.groupBy { RelationKey.of(it.from, it.to) }
            .mapValues { (_, evs) -> evs.sortedWith(compareBy({ it.type.ordinal }, { it.from.file ?: "" }, { it.from.line })) }

        val rivals = grouped.keys.groupBy { it.fromEnd }
            .mapValues { (_, keys) -> keys.map { it.toTable }.distinct().sorted() }

        val relations = grouped.map { (key, evs) ->
            val signals = signals(key, byTable)
            RelationCandidate(
                key = key,
                evidences = evs,
                signals = signals,
                tier = tier(evs, signals),
                competingTargets = rivals.getValue(key.fromEnd).filterNot { it == key.toTable },
            )
        }.sortedBy { it.key.text }

        return RelationGraph(relations, stats(evidences, deduped, usable, relations, tables))
    }

    private fun signature(e: Evidence) = listOf(
        e.type,
        e.from.table.lowercase(), e.from.column.lowercase(),
        e.to.table.lowercase(), e.to.column.lowercase(),
        e.from.file, e.from.line, e.callName, e.enclosingMethod, e.notes,
    )

    private fun stats(
        input: List<Evidence>,
        deduped: List<Evidence>,
        usable: List<Evidence>,
        relations: List<RelationCandidate>,
        tables: List<SchemaTable>,
    ): GraphStats {
        val keys = relations.map { it.key }.toSet()
        val fkEnds = tables.flatMap { t ->
            t.columns.filter { it.name.endsWith("_id", ignoreCase = true) }.map { "${t.name.lowercase()}.${it.name.lowercase()}" }
        }.toSet()
        val fromEnds = relations.map { it.key.fromEnd }
        val withCandidate = fkEnds.count { it in fromEnds }
        return GraphStats(
            evidenceIn = input.size,
            duplicateEvidence = input.size - deduped.size,
            selfPairsSkipped = deduped.size - usable.size,
            relations = relations.size,
            tablePairs = relations.map { it.key.tablePair }.distinct().size,
            bySource = EvidenceType.entries.associateWith { t -> relations.count { t in it.sources } },
            byTier = SupportTier.entries.associateWith { t -> relations.count { it.tier == t } },
            reciprocalPairs = keys.count { it.reversed in keys } / 2,
            fkColumns = fkEnds.size,
            fkColumnsWithCandidate = withCandidate,
            fkColumnsAmbiguous = relations.filter { it.ambiguous }.map { it.key.fromEnd }.distinct().count { it in fkEnds },
            fkColumnsWithoutCandidate = fkEnds.size - withCandidate,
            relationsPerFkColumn = if (fkEnds.isEmpty()) 0.0 else relations.size.toDouble() / fkEnds.size,
            typeMismatch = relations.count { it.signals.typeMatch == TypeMatch.DIFFERENT },
            baseTypeMismatch = relations.count { it.signals.typeMatch == TypeMatch.BASE_SAME },
            unaligned = relations.count { !it.schemaAligned },
            codeBackedUnaligned = relations.count { it.tier == SupportTier.CODE_BACKED && !it.schemaAligned },
            unresolvedDirection = relations.count { it.directionUnresolved },
            ambiguousFromColumns = relations.filter { it.ambiguous }.map { it.key.fromEnd }.distinct().size,
        )
    }

    private fun tier(evidences: List<Evidence>, signals: RelationSignals): SupportTier {
        val types = evidences.map { it.type }.toSet()
        if (types.any { it in CODE_SOURCES }) return SupportTier.CODE_BACKED
        if (EvidenceType.KEY_TUPLE in types) return SupportTier.SCHEMA_STRONG
        val strongNaming = evidences.any { e -> e.notes.any { it in STRONG_NAMING } }
        return if (strongNaming && signals.fromColumnInSchema && signals.toIsPrimaryKey) {
            SupportTier.SCHEMA_STRONG
        } else {
            SupportTier.WEAK
        }
    }

    private fun signals(key: RelationKey, byTable: Map<String, SchemaTable>): RelationSignals {
        val fromTable = byTable[key.fromTable]
        val toTable = byTable[key.toTable]
        val from = fromTable?.column(key.fromColumn)
        val to = toTable?.column(key.toColumn)
        return RelationSignals(
            fromTableInSchema = fromTable != null,
            toTableInSchema = toTable != null,
            fromColumnInSchema = from != null,
            toColumnInSchema = to != null,
            fromType = from?.fullType,
            toType = to?.fullType,
            typeMatch = when {
                from == null || to == null -> TypeMatch.UNKNOWN
                from.fullType.equals(to.fullType, ignoreCase = true) -> TypeMatch.SAME
                from.type.equals(to.type, ignoreCase = true) -> TypeMatch.BASE_SAME
                else -> TypeMatch.DIFFERENT
            },
            toIsPrimaryKey = to != null && primaryKey(toTable!!).singleOrNull()?.equals(to.name, ignoreCase = true) == true,
            toIsUniqueKey = to != null && isUniqueKey(toTable!!, to.name),
            fromIndexed = from != null && isLeftmostIndexed(fromTable!!, from.name),
            sameModule = fromTable != null && toTable != null && fromTable.module == toTable.module,
            sharedNameSegments = sharedNameSegments(key.fromTable, key.toTable),
        )
    }

    /** 表名按下划线分段的公共前缀段数：这类脚手架的子表名 = 父表名 + 后缀，这是多义消歧最强的 schema 侧信号 */
    private fun sharedNameSegments(from: String, to: String): Int {
        val a = from.split('_')
        val b = to.split('_')
        var n = 0
        while (n < minOf(a.size, b.size) && a[n] == b[n]) n++
        return n
    }

    private fun primaryKey(t: SchemaTable): List<String> =
        t.indexes.firstOrNull { it.name.equals("PRIMARY", ignoreCase = true) }?.columns
            ?: t.columns.filter { it.key == "PRI" }.map { it.name }

    private fun isUniqueKey(t: SchemaTable, column: String): Boolean =
        t.indexes.any { it.unique && it.columns.singleOrNull()?.equals(column, ignoreCase = true) == true }

    /** MySQL 最左前缀：只有当列是某个索引的第一列时，按它 JOIN 才走得上索引 */
    private fun isLeftmostIndexed(t: SchemaTable, column: String): Boolean =
        t.indexes.any { it.columns.firstOrNull()?.equals(column, ignoreCase = true) == true }
}
