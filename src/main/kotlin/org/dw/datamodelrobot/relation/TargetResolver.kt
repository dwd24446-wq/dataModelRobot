package org.dw.datamodelrobot.relation

import org.dw.datamodelrobot.evidence.TypeHint
import org.dw.datamodelrobot.schema.NameMatch

/**
 * 多义指向消歧：同一个 `from` 列有多个落点时，给出「最可能是哪一个」和「凭什么」。
 *
 * 排序依据从硬到软：
 * 1. **有代码证据** —— 代码里写死了是哪个实体（`leftJoin(MemberUserDO.class, …)`），比任何命名猜测都硬。
 * 2. **代码侧类型提示**（[TypeHintIndex][org.dw.datamodelrobot.evidence.TypeHintIndex]）—— 引用过这一列的文件
 *    import 的是 `MemberUserApi` 还是 `AdminUserApi`。这是唯一能翻**跨模块通用名**的依据：
 *    `trade_order.user_id` 的两个落点在 schema 侧完全对称，只有代码里写着答案。
 * 3. **表名公共前缀段数（超出模块前缀的部分）** —— 这类脚手架的子表名就是父表名加后缀
 *    （`promotion_combination_product` 的 `activity_id` 指向 `promotion_combination_activity`，
 *    而不是同模块的 `promotion_bargain_activity`）。**必须减掉模块前缀那一段**：同模块的表全都共享它，
 *    只共享模块前缀不构成父子关系的证据。
 * 4. **命名命中强度** —— `system_user_post.user_id` 在 `system_users`（module_prefix）与
 *    `system_social_user`（tail_only）之间选前者。
 * 5. **同模块** —— 命名强度也打平时的弱偏好（`system_user_post.user_id` 在 `system_users` 与
 *    `member_user` 之间选前者）。**必须排在命名强度之后**：模块前缀是最弱的结构信号，
 *    让它压过命名强度就会重演下面那条 `trade_order.user_id` 的反例。
 * 6. 证据最高权重，最后按 key 兜底保证可复现。
 *
 * 全部并列时判定为**消歧不了**（[Resolution.resolved] = false），交给 [ConfidenceScorer][org.dw.datamodelrobot.score.ConfidenceScorer]
 * 压分 + 标 needs_review，**不在这里悄悄选一个**。
 *
 * 两条实测出来的反例（都来自真实项目）：
 * - `promotion_combination_product.activity_id` 的 module_prefix 强命中落在 `market_activity`（错），
 *   真父表只拿到 tail_only —— 靠第 3 条翻案
 * - `trade_order.user_id` 曾被第 3 条判给 `trade_brokerage_user`，唯一依据是两者都以 `trade_` 开头
 *   （Step 1-5 打分时才暴露：真答案 `member_user` 被压到 0.22、错的那个反而 0.41）—— 把模块前缀那一段
 *   减掉后这一列退回「消歧不了」，再由第 2 条的代码侧提示去翻案
 */
object TargetResolver {

    data class Resolution(
        val ordered: List<RelationCandidate>,
        /** 消歧不了时为 null */
        val winner: RelationCandidate?,
        val reason: String?,
        val resolved: Boolean,
        /** 与「只按证据权重排」的结论不一致 —— 说明照着权重挑会挑错，报告要点出来 */
        val contradictsWeightOrder: Boolean,
        /** 这个 from 列的代码侧类型提示；没扫过就是 null */
        val typeHint: TypeHint? = null,
    )

    private val WEIGHT_ORDER: Comparator<RelationCandidate> = compareByDescending<RelationCandidate> { it.maxWeight }
        .thenByDescending { it.evidences.size }
        .thenBy { it.key.text }

    private fun order(hints: Map<String, TypeHint>): Comparator<RelationCandidate> =
        compareByDescending<RelationCandidate> { it.codeBacked }
            .thenByDescending { if (hinted(it, hints)) 1 else 0 }
            .thenByDescending { beyondModulePrefix(it) }
            .thenByDescending { namingStrength(it) }
            .thenByDescending { if (it.signals.sameModule) 1 else 0 }
            .thenByDescending { it.maxWeight }

    /** [NameMatch] 的声明序是 EXACT 在前，直接比 ordinal 会把最弱的挑出来，所以单独给强度值 */
    private fun namingStrength(c: RelationCandidate): Int = when (c.namingMatch) {
        NameMatch.EXACT -> 3
        NameMatch.MODULE_PREFIX -> 2
        NameMatch.TAIL_ONLY -> 1
        null -> 0
    }

    /**
     * 公共前缀里**超出模块前缀**的段数。这类脚手架的表名第一段就是模块（`trade_order` 的 `trade`），
     * 同模块所有表都共享这一段，它不含任何消歧信息。
     */
    private fun beyondModulePrefix(c: RelationCandidate): Int = (c.signals.sharedNameSegments - 1).coerceAtLeast(0)

    private fun hinted(c: RelationCandidate, hints: Map<String, TypeHint>): Boolean =
        hints[c.key.fromEnd]?.supported?.get(c.key.toTable)?.isNotEmpty() == true

    fun resolve(candidates: List<RelationCandidate>, hints: Map<String, TypeHint> = emptyMap()): Resolution {
        if (candidates.size == 1) {
            return Resolution(candidates, candidates.single(), "唯一候选", resolved = true, contradictsWeightOrder = false)
        }
        val order = order(hints)
        val ordered = candidates.sortedWith(order.thenBy { it.key.text })
        val top = ordered.takeWhile { order.compare(it, ordered.first()) == 0 }
        val winner = if (top.size == 1) ordered.first() else null
        return Resolution(
            ordered = ordered,
            winner = winner,
            reason = if (winner == null) "前 ${top.size} 个并列，消歧不了" else reason(ordered[0], ordered[1], hints),
            resolved = winner != null,
            contradictsWeightOrder = winner != null && winner.key != candidates.sortedWith(WEIGHT_ORDER).first().key,
            typeHint = hints[candidates.first().key.fromEnd],
        )
    }

    private fun reason(winner: RelationCandidate, runnerUp: RelationCandidate, hints: Map<String, TypeHint>): String = when {
        winner.codeBacked && !runnerUp.codeBacked -> "有代码证据"
        hinted(winner, hints) && !hinted(runnerUp, hints) -> {
            val files = hints[winner.key.fromEnd]?.supported?.get(winner.key.toTable).orEmpty()
            "代码侧类型提示 ${files.size} 个引用文件（${files.take(3).joinToString(", ")}）"
        }
        beyondModulePrefix(winner) > beyondModulePrefix(runnerUp) ->
            "表名公共前缀 ${winner.signals.sharedNameSegments} 段、超出模块前缀 ${beyondModulePrefix(winner)} 段" +
                "（${winner.key.toTable} vs ${runnerUp.key.toTable}）"
        namingStrength(winner) > namingStrength(runnerUp) ->
            if (runnerUp.namingMatch == null) {
                "命名命中更强（${winner.namingMatch?.name?.lowercase()}，${runnerUp.key.toTable} 那个候选没有命名证据）"
            } else {
                "命名命中更强（${winner.namingMatch?.name?.lowercase()} > ${runnerUp.namingMatch?.name?.lowercase()}）"
            }
        winner.signals.sameModule && !runnerUp.signals.sameModule ->
            "同模块 ${winner.key.fromTable.substringBefore('_')}（命名强度打平时的弱偏好）"
        else -> "证据权重更高"
    }
}
