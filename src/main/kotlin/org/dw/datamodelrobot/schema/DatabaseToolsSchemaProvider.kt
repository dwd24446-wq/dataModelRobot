package org.dw.datamodelrobot.schema

import com.intellij.database.model.DasColumn
import com.intellij.database.model.DasForeignKey
import com.intellij.database.model.DasIndex
import com.intellij.database.model.DasTable
import com.intellij.database.model.ObjectKind
import com.intellij.database.psi.DbDataSource
import com.intellij.database.psi.DbPsiFacade
import com.intellij.openapi.project.Project

/**
 * 最终用户形态：读 IDE 里已配置的 Database Tools 数据源。驱动由 IDE 提供，插件不 bundle JDBC 驱动
 * （Connector/J 是 GPL，分发有法律负担）。
 *
 * 调用方需包在 ReadAction 里。headless 测试无法配置数据源，走 [JsonDumpSchemaProvider]；
 * 本类的联调在 IDE 内做一次即可。
 */
class DatabaseToolsSchemaProvider(
    private val project: Project,
    private val dataSourceName: String? = null,
) : SchemaProvider {

    override val id: String = "database-tools"

    override fun load(): DbSchema {
        val dataSources = DbPsiFacade.getInstance(project).dataSources
        if (dataSources.isEmpty()) {
            throw SchemaUnavailableException("IDE 里没有配置数据源：Database 工具窗口 → + → Data Source")
        }
        val ds = pick(dataSources)
        if (ds.isLoading) {
            throw SchemaUnavailableException("数据源 ${ds.name} 的 introspection 尚未完成")
        }
        val namespaces = ds.getDasChildren(ObjectKind.SCHEMA).toList()
        val tables = namespaces.flatMap { ns ->
            ns.getDasChildren(ObjectKind.TABLE).filter(DasTable::class.java).toList()
        }
        return DbSchema(
            catalog = if (namespaces.size == 1) namespaces[0].name else ds.name,
            dbms = ds.dbms.name,
            dbmsVersion = ds.version?.toString(),
            server = ds.name,
            providerId = id,
            tables = tables.map { toTable(it) },
            views = namespaces.flatMap { ns ->
                ns.getDasChildren(ObjectKind.VIEW).filter(DasTable::class.java).toList().map { it.name }
            },
        )
    }

    private fun pick(dataSources: List<DbDataSource>): DbDataSource {
        if (dataSourceName == null) {
            return dataSources.singleOrNull() ?: throw SchemaUnavailableException(
                "IDE 里配置了 ${dataSources.size} 个数据源，需指定用哪个：${dataSources.joinToString { it.name }}"
            )
        }
        return dataSources.firstOrNull { it.name == dataSourceName }
            ?: throw SchemaUnavailableException("找不到名为 $dataSourceName 的数据源（现有：${dataSources.joinToString { it.name }}）")
    }

    private fun toTable(t: DasTable): SchemaTable = SchemaTable(
        name = t.name,
        module = SchemaTables.moduleOf(t.name),
        comment = t.comment?.takeIf { it.isNotBlank() },
        // DatabaseTools 的 DasTable 不暴露引擎/排序规则，这两项只有 dump 路径有
        engine = null,
        collation = null,
        columns = t.getDasChildren(ObjectKind.COLUMN).filter(DasColumn::class.java).toList().map { c -> toColumn(t, c) },
        indexes = t.getDasChildren(ObjectKind.INDEX).filter(DasIndex::class.java).toList().map { i ->
            SchemaIndex(
                name = i.name,
                unique = i.isUnique,
                type = null,
                columns = i.columnsRef.names().toList(),
            )
        },
        foreignKeys = t.getDasChildren(ObjectKind.FOREIGN_KEY).filter(DasForeignKey::class.java).toList().map { fk ->
            SchemaForeignKey(
                name = fk.name,
                columns = fk.columnsRef.names().toList(),
                refTable = fk.refTableName ?: "",
                refColumns = fk.refColumns.names().toList(),
                onDelete = fk.deleteRule?.name?.replace('_', ' '),
                onUpdate = fk.updateRule?.name?.replace('_', ' '),
            )
        },
    )

    private fun toColumn(t: DasTable, c: DasColumn): SchemaColumn {
        val attrs = t.getColumnAttrs(c)
        // 废弃的 getDataType() 默认实现就是 dasType.toDataType()（javap 实证），逐字等价替换
        val dataType = c.dasType.toDataType()
        return SchemaColumn(
            name = c.name,
            position = c.position.toInt(),
            type = dataType.typeName,
            fullType = dataType.specification,
            nullable = !c.isNotNull,
            key = keyOf(attrs),
            defaultValue = c.default,
            extra = if (DasColumn.Attribute.AUTO_GENERATED in attrs) "auto_increment" else null,
            comment = c.comment?.takeIf { it.isNotBlank() },
        )
    }

    /** 对齐 information_schema.COLUMN_KEY 的取值，让两条 provider 路径产出可比对的同一份事实。 */
    private fun keyOf(attrs: Set<DasColumn.Attribute>): String? = when {
        DasColumn.Attribute.PRIMARY_KEY in attrs -> "PRI"
        DasColumn.Attribute.CANDIDATE_KEY in attrs -> "UNI"
        DasColumn.Attribute.INDEX in attrs || DasColumn.Attribute.FOREIGN_KEY in attrs -> "MUL"
        else -> null
    }
}
