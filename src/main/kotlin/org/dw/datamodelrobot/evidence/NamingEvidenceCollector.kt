package org.dw.datamodelrobot.evidence

import org.dw.datamodelrobot.schema.DbSchema
import org.dw.datamodelrobot.schema.NameMatch
import org.dw.datamodelrobot.schema.SchemaTable
import org.dw.datamodelrobot.schema.TableNaming

/**
 * schema 侧证据：`xxx_id` 列 ↔ 名字对得上的表的单列主键。
 * 这是唯一不依赖代码的证据，兜住「代码里从来没 JOIN 过、但业务上就是外键」的关系。
 *
 * 命中强度决定权重：EXACT / MODULE_PREFIX 用基础权重 .60，TAIL_ONLY（只有尾段像，
 * 如 `sku_id` → `stock_item_sku`、`user_id` → `system_dept_user`）降到 [TAIL_ONLY_WEIGHT]。
 * 宁可给低分也不要漏，但低分必须看得出来是低分。
 */
object NamingEvidenceCollector {

    const val TAIL_ONLY_WEIGHT = 0.45

    /** 命中强度写进 notes 的前缀；关系层靠它恢复 [NameMatch]（多义消歧要用） */
    const val NAMING_NOTE = "naming_match="

    fun collect(schema: DbSchema, tables: List<SchemaTable> = schema.includedTables): List<Evidence> {
        val out = mutableListOf<Evidence>()
        for (t in tables) {
            for (c in t.columns) {
                TableNaming.base(c.name) ?: continue
                val matches = TableNaming.candidates(c.name, tables)
                if (matches.isEmpty()) continue
                // 多义指向只按「强命中」算：尾段撞名的表不该把 user_id 的歧义从 2 个夸大到 4 个
                val strong = matches.filter { it.second != NameMatch.TAIL_ONLY }
                for ((target, strength) in matches) {
                    val pk = singleColumnPk(target) ?: continue
                    out += Evidence(
                        type = EvidenceType.NAMING_CONVENTION,
                        from = ColumnRef(t.name, c.name),
                        to = ColumnRef(target.name, pk),
                        weight = if (strength == NameMatch.TAIL_ONLY) {
                            TAIL_ONLY_WEIGHT
                        } else {
                            EvidenceType.NAMING_CONVENTION.baseWeight
                        },
                        notes = buildList {
                            add("oriented_by=naming")
                            add(NAMING_NOTE + strength.name.lowercase())
                            if (strong.size > 1) {
                                add("ambiguous_target=${strong.size}:${strong.joinToString(",") { it.first.name }}")
                            }
                        },
                    )
                }
            }
        }
        return out
    }

    /** 只有单列主键才当命名约定的落点；复合主键（QRTZ 那种）猜不出来，直接不产证据 */
    private fun singleColumnPk(t: SchemaTable): String? {
        val primary = t.indexes.firstOrNull { it.name.equals("PRIMARY", ignoreCase = true) }?.columns
        if (primary != null) return primary.singleOrNull()
        return t.columns.filter { it.key == "PRI" }.singleOrNull()?.name
    }
}
