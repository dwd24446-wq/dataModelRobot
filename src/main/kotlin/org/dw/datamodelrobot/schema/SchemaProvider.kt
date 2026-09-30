package org.dw.datamodelrobot.schema

/** schema 侧的唯一入口：DatabaseTools（最终形态）与 JSON dump（headless 测试）各一个实现。 */
interface SchemaProvider {
    val id: String
    fun load(): DbSchema
}

class SchemaUnavailableException(message: String) : RuntimeException(message)
