package org.dw.datamodelrobot.toolwindow

import org.dw.datamodelrobot.output.ErDiagram

/** 把事实源读模型投影给图渲染器；分数与基数均沿用 JSON，不在 UI 重算。 */
internal object ErDiagramData {
    fun tables(view: FactSourceView): List<ErDiagram.Table> = view.tables.map { table ->
        ErDiagram.Table(
            table.name, table.label,
            table.columns.map { column ->
                val name = column.name.lowercase()
                ErDiagram.Column(
                    column.name, column.position, column.type, column.nullable, column.key,
                    name in table.uniqueColumns, name in table.foreignKeyColumns, column.comment,
                )
            },
        )
    }

    fun relations(view: FactSourceView): List<ErDiagram.Relation> {
        val known = view.tableIndex.knownNames
        return view.relations.filter { relation ->
            relation.schemaAligned && relation.fromTable.lowercase() in known && relation.toTable.lowercase() in known
        }.map { relation ->
            val parts = relation.cardinality.split("--", limit = 2)
            val left = parts.getOrNull(0)?.takeIf { it in setOf("||", "|o", "}o") } ?: "|o"
            val right = parts.getOrNull(1)?.takeIf { it in setOf("||", "|o", "o{", "|{") } ?: "o{"
            ErDiagram.Relation(
                relation.fromTable, relation.fromColumn, relation.toTable, relation.toColumn,
                relation.confidence, relation.solid, left, right,
            )
        }
    }
}
