package org.dw.datamodelrobot.schema

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

/**
 * 读 information_schema 的 JSON dump，让 headless 测试不依赖数据库连接。
 *
 * 手工取值而不是让 Gson 反射绑定 Kotlin data class：Gson 用 Unsafe 实例化、不执行 Kotlin 默认值，
 * dump 里缺失的非空字段会静默变成 null，直到后面某处才 NPE。
 */
class JsonDumpSchemaProvider(private val dumpFile: Path) : SchemaProvider {

    override val id: String = "json-dump"

    override fun load(): DbSchema {
        if (!Files.exists(dumpFile)) {
            throw SchemaUnavailableException("schema dump 不存在: $dumpFile")
        }
        val root = JsonParser.parseString(Files.readString(dumpFile)).asJsonObject
        val format = str(root, "format") ?: ""
        if (!format.startsWith("datamodelrobot-schema-dump/")) {
            throw SchemaUnavailableException("$dumpFile 不是本工具的 schema dump（format=$format）")
        }
        return DbSchema(
            catalog = str(root, "catalog") ?: "",
            dbms = str(root, "dbms") ?: "unknown",
            dbmsVersion = str(root, "dbmsVersion"),
            server = str(root, "server"),
            providerId = id,
            tables = arr(root, "tables").map { parseTable(it.asJsonObject) },
            views = arr(root, "views").map { it.asString },
        )
    }

    private fun parseTable(o: JsonObject): SchemaTable {
        val name = req(o, "name")
        return SchemaTable(
            name = name,
            module = SchemaTables.moduleOf(name),
            comment = str(o, "comment"),
            engine = str(o, "engine"),
            collation = str(o, "collation"),
            columns = arr(o, "columns").map { parseColumn(it.asJsonObject) },
            indexes = arr(o, "indexes").map { parseIndex(it.asJsonObject) },
            foreignKeys = arr(o, "foreignKeys").map { parseFk(it.asJsonObject) },
        )
    }

    private fun parseColumn(o: JsonObject): SchemaColumn {
        val type = str(o, "type") ?: ""
        return SchemaColumn(
            name = req(o, "name"),
            position = int(o, "position"),
            type = type,
            fullType = str(o, "fullType") ?: type,
            nullable = bool(o, "nullable"),
            key = str(o, "key"),
            defaultValue = str(o, "defaultValue"),
            extra = str(o, "extra"),
            comment = str(o, "comment"),
        )
    }

    private fun parseIndex(o: JsonObject) = SchemaIndex(
        name = req(o, "name"),
        unique = bool(o, "unique"),
        type = str(o, "type"),
        columns = strs(o, "columns"),
    )

    private fun parseFk(o: JsonObject) = SchemaForeignKey(
        name = req(o, "name"),
        columns = strs(o, "columns"),
        refTable = str(o, "refTable") ?: "",
        refColumns = strs(o, "refColumns"),
        onDelete = str(o, "onDelete"),
        onUpdate = str(o, "onUpdate"),
    )

    private fun el(o: JsonObject, k: String): JsonElement? = o.get(k)?.takeIf { !it.isJsonNull }

    private fun str(o: JsonObject, k: String): String? = el(o, k)?.asString

    private fun req(o: JsonObject, k: String): String =
        str(o, k) ?: throw SchemaUnavailableException("dump 记录缺字段 \"$k\"（表 ${str(o, "name")}）")

    private fun int(o: JsonObject, k: String): Int = el(o, k)?.asInt ?: 0

    private fun bool(o: JsonObject, k: String): Boolean = el(o, k)?.asBoolean ?: false

    private fun arr(o: JsonObject, k: String): List<JsonElement> = (el(o, k) as? com.google.gson.JsonArray)?.toList() ?: emptyList()

    private fun strs(o: JsonObject, k: String): List<String> = arr(o, k).map { it.asString }
}
