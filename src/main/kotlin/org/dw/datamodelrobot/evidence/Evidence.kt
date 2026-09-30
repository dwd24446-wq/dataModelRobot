package org.dw.datamodelrobot.evidence

/**
 * 证据类型与基础权重。权重表达「这种写法多大概率真的是一条外键关系」，
 * 最终置信度还要叠加冲突惩罚（Step 1-5），**不让 LLM 直接吐分数**。
 */
/**
 * [labelZh] 是给**人看的展示口径**（工具窗口、MCP 输出）；`name` 仍是事实源与 MCP 过滤参数的
 * 唯一取值，两者不要混用 —— 改中文不会破坏已生成的产物与过滤约定。
 */
enum class EvidenceType(val baseWeight: Double, val labelZh: String) {
    /** MPJ 显式 JOIN：`leftJoin(X.class, X::getId, Y::getXId)` —— 最高权重 */
    MPJ_JOIN(0.95, "MPJ 显式 JOIN"),

    /** 键元组同名同序：A 的索引列元组 == B 的主键列元组（复合自然键、扩展表共享父表主键） */
    KEY_TUPLE(0.90, "键元组同名配对"),

    /** 等值条件两侧都是列：`eq(A::getXId, B::getId)`，含 on-lambda 里的 JOIN 条件 */
    LAMBDA_EQ(0.85, "Lambda 等值条件"),

    /** Service 层组装：同一方法内既引用了 A.x_id，又调了 B 的 Mapper/Service */
    SELECT_ASSOCIATION(0.75, "Service 层组装"),

    /** 纯命名约定：schema 里 A 表有 x_id 列，且存在 x 表且有 id 列 */
    NAMING_CONVENTION(0.60, "命名约定"),
    ;

    companion object {
        /** 展示用：事实源里的类型名 → 中文标签。未知名字原样回显（旧产物或第三方 parser 不能崩面板） */
        fun labelZhOf(typeName: String): String =
            entries.firstOrNull { it.name == typeName }?.labelZh ?: typeName
    }
}

/** 证据的一端：表.列 + 代码出处（schema 侧推出的证据没有出处，file 为 null） */
data class ColumnRef(
    val table: String,
    val column: String,
    val entityFqn: String? = null,
    val file: String? = null,
    val line: Int = -1,
    val refText: String? = null,
) {
    override fun toString(): String = "$table.$column"
}

/**
 * 一条关系证据。方向固定为 from（外键侧，多的一方）→ to（被指向侧，一的一方）。
 * [notes] 里记着方向是怎么定出来的（oriented_by=…）与降权原因，保证每条证据可解释、可追溯。
 */
data class Evidence(
    val type: EvidenceType,
    val from: ColumnRef,
    val to: ColumnRef,
    val weight: Double = type.baseWeight,
    val schemaAligned: Boolean = true,
    /** LEFT / INNER / RIGHT，仅 JOIN 语境有 */
    val joinType: String? = null,
    val callName: String? = null,
    /** 产出这条证据的方法名；同一方法内同一关系只算一次，避免 JOIN 与 Service 组装重复计分 */
    val enclosingMethod: String? = null,
    /** fixture javadoc 里的 [S#]；真实项目为 null */
    val scenario: String? = null,
    val notes: List<String> = emptyList(),
) {
    val key: String get() = "${from.table}.${from.column}->${to.table}.${to.column}"
    val location: String get() = "${from.file?.substringAfterLast('/') ?: "-"}:${from.line}"
}
