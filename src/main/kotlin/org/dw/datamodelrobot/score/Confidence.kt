package org.dw.datamodelrobot.score

import org.dw.datamodelrobot.relation.RelationCandidate
import org.dw.datamodelrobot.relation.RelationKey
import org.dw.datamodelrobot.relation.SupportTier
import java.util.Locale

/**
 * 置信度档位。分界线同时是 Mermaid 的虚实线阈值：[LOW] 画虚线（`||..o{`），其余画实线。
 *
 * 0.60 这条线不是随手挑的：Phase 3 的 Skill 用 LLM 兜底推断时**置信度封顶 0.6**，
 * 所以「规则引擎算出来 ≥ 0.6」与「LLM 能给出的最高分」是同一档 —— 低于它的关系，
 * 采信成本与 LLM 现推一条差不多，图上就该画成虚线提醒人去核对。
 */
enum class ConfidenceBand(val min: Double) {
    /** 证据自洽，且有代码证据或强 schema 支撑 */
    HIGH(0.85),

    /** 说得通但只有一侧支撑，或被冲突压过 */
    MEDIUM(0.60),

    /** 只有弱命名证据、或冲突压到了 0.6 以下 —— 别直接采信 */
    LOW(0.0),
    ;

    companion object {
        /** 声明序是从高到低，第一个命中的就是档位 */
        fun of(confidence: Double): ConfidenceBand = entries.first { confidence >= it.min }
    }
}

/**
 * 一次扣分。[factor] 是乘数（0.55 = 打到 55%），[trigger] 是稳定标识（进事实源的统计段），
 * [reason] 是人话 + 出处，`explain_relation` 直接拿它回答「为什么只有这么点分」。
 */
data class Penalty(val trigger: String, val factor: Double, val reason: String)

/** 多义指向（冲突③）在本条关系上的结论 */
enum class AmbiguityOutcome {
    /** 消歧选中了本条 */
    WINNER,

    /** 消歧选中了别的落点，本条是竞争者 */
    LOSER,

    /** [TargetResolver] 的四条依据全并列，选不出来 —— 所有落点都标这个 */
    UNRESOLVED,
}

/**
 * @param targets 这个 from 列的全部落点（含本条自己），按名字排序
 */
data class Ambiguity(
    val targets: List<String>,
    val outcome: AmbiguityOutcome,
    val reason: String?,
)

/**
 * 打好分的关系。[candidate] 原样保留（证据 + schema 信号 + 档位），置信度是**叠加**上去的，
 * 不改动任何证据 —— 事实源要能回答「这个分是怎么来的」，所以 [baseScore] 与 [penalties] 都留着。
 *
 * [needsReview] = 这条关系的结论需要人确认，不能直接采信（消歧不了 / 方向未定 / 双向不一致 /
 * 代码引用的表列不在本库）。注意它**不是**「有冲突」的别名：①类型不匹配是确定的 schema 事实，
 * 走扣分；②隔离缺失挂在 `Conflict.needsReview` 上（那是查询链路的问题，不是关系的问题）。
 */
data class ScoredRelation(
    val candidate: RelationCandidate,
    /** 证据侧的原始分（noisy-OR，已封顶），未扣分 */
    val baseScore: Double,
    val penalties: List<Penalty>,
    /** 官方分数 = baseScore × 全部惩罚系数，保留两位 */
    val confidence: Double,
    val band: ConfidenceBand,
    val ambiguity: Ambiguity?,
    val needsReview: Boolean,
    val reviewReasons: List<String>,
) {
    val key: RelationKey get() = candidate.key
    val tier: SupportTier get() = candidate.tier
    val solid: Boolean get() = band != ConfidenceBand.LOW
    val schemaAligned: Boolean get() = candidate.schemaAligned

    /** 一条可复现的算式，报告与 explain_relation 都用它。定 Locale.ROOT：事实源不能随机器语言变小数点 */
    fun explain(): String = buildString {
        append(num(confidence, 2))
        append(" = base ").append(num(baseScore, 3))
        for (p in penalties) append(" ×").append(num(p.factor, 2)).append("(").append(p.trigger).append(")")
        if (ambiguity != null) append("  多义: ").append(ambiguity.outcome)
        if (needsReview) append("  needs_review")
    }
}

/** 定宽小数。事实源与报告都要可 diff，所以不让默认 Locale 决定小数点长什么样 */
internal fun num(v: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", v)

data class ScoreStats(
    val relations: Int,
    val byBand: Map<ConfidenceBand, Int>,
    /** ≥ 0.6 的关系数（Mermaid 实线） */
    val solidLine: Int,
    val dashedLine: Int,
    val min: Double,
    val max: Double,
    val mean: Double,
    val median: Double,
    /** 每个惩罚触发多少次，按 trigger 名排序（事实源要可 diff） */
    val penaltyHits: Map<String, Int>,
    val needsReview: Int,
    val ambiguousWinner: Int,
    val ambiguousLoser: Int,
    val ambiguousUnresolved: Int,
    val unaligned: Int,
    val byTier: Map<SupportTier, Double>,
)

/** 打完分的关系图。[relations] 按 key 排序，同一份输入两次打分字节一致。 */
data class ScoredGraph(val relations: List<ScoredRelation>, val stats: ScoreStats) {

    fun byKey(): Map<String, ScoredRelation> = relations.associateBy { it.key.text }

    /** 报告用：分数降序，同分按 key */
    fun ranked(): List<ScoredRelation> =
        relations.sortedWith(compareBy({ -it.confidence }, { it.key.text }))

    fun forTable(table: String): List<ScoredRelation> = relations.filter {
        it.key.fromTable == table.lowercase() || it.key.toTable == table.lowercase()
    }

    fun forModule(module: String): List<ScoredRelation> = relations.filter {
        it.key.fromTable.substringBefore('_') == module || it.key.toTable.substringBefore('_') == module
    }
}
