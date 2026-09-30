package org.dw.datamodelrobot.conflict

/**
 * 冲突类型，按 AGENTS.md 的价值排序。**冲突不等于「这条关系不存在」**，它表达的是「证据之间是否自洽」——
 * 代码可能写错，从错误代码推断出的关系就是错的，所以冲突必须留在事实源里而不是被过滤掉。
 */
enum class ConflictType {
    /** ① JOIN 两侧类型不匹配：隐式转换 + 索引失效 */
    TYPE_MISMATCH,

    /** ② 多表查询链路里缺租户条件。拦截器通常会自动补，所以是提示级 */
    MISSING_TENANT_SCOPE,

    /** ② 多表查询链路里缺逻辑删除条件。@TableLogic 不覆盖被 JOIN 的表，这一条更可能是真 bug */
    MISSING_LOGICAL_DELETE,

    /** ③ 多义指向：一个外键列有多个可能的落点 */
    AMBIGUOUS_TARGET,

    /** ④ 双向不一致：A.x→B.y 与 B.y→A.x 同时被推断出来 */
    RECIPROCAL_INCONSISTENT,

    /** ⑤ 孤儿字段：schema 有 `xxx_id`，但四个引用口径都没出现过。口径见 [ColumnReferenceIndex] */
    ORPHAN_COLUMN,
}

enum class Severity { HIGH, MEDIUM, LOW }

/**
 * 一条冲突。[subject] 是关系 key（①③）或 `表.列`（⑤）；[refs] 是代码出处，schema 侧冲突为空。
 * [resolution] 只用于③：消歧结论 + 凭什么；消歧不了就是 null（**不悄悄选一个**，交给 Step 1-5 压分）。
 */
data class Conflict(
    val type: ConflictType,
    val severity: Severity,
    val subject: String,
    val detail: String,
    val refs: List<String> = emptyList(),
    val resolution: String? = null,
) {
    /**
     * 工具**下不了结论**、要人确认的那批冲突。判定标准是「结论确定吗」而不是「要不要动手修」：
     * - ② 隔离缺失：被 JOIN 的表该不该带 `deleted` 条件依 mybatis-plus-join 版本行为而定 → 不确定
     * - ③ 消歧不了（[resolution] == null）：四条依据全并列，选不出落点 → 不确定
     * - ④ 双向不一致：两处代码至少一处写错，但工具不猜是哪处 → 不确定
     * - ⑤ 孤儿字段：扫描口径是**下限**（src/test 看不见、B/C/D 是列名级会互相掩护）→ 不确定
     * - ① 类型不匹配：两侧 `column_type` 摆在 schema 里，是确定事实 → 不标
     *
     * 这一批**不折算进置信度**（②影响的是查询链路健康度，不是关系是否存在；③④⑤的扣分已各自体现在
     * 关系或严重度上），只作为标记原样进事实源，让 MCP / Skill 能单独过滤出来给人看。
     */
    val needsReview: Boolean
        get() = type != ConflictType.TYPE_MISMATCH &&
            (type != ConflictType.AMBIGUOUS_TARGET || resolution == null)
}
