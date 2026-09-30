package org.dw.datamodelrobot.score

import org.dw.datamodelrobot.conflict.Conflict
import org.dw.datamodelrobot.conflict.ConflictType
import org.dw.datamodelrobot.conflict.Severity
import org.dw.datamodelrobot.evidence.TypeHint
import org.dw.datamodelrobot.relation.RelationCandidate
import org.dw.datamodelrobot.relation.RelationGraph
import org.dw.datamodelrobot.relation.SupportTier
import org.dw.datamodelrobot.relation.TargetResolver
import kotlin.math.round

/**
 * 置信度公式。**不让 LLM 吐分数**（不可复现、不可追溯），分数只由证据与冲突算出：
 *
 * ```
 * base       = 1 - Π(1 - wᵢ)          wᵢ = 每条证据的权重（已含 schema 未对齐的 ×0.5）
 * confidence = round2( min(base, 0.99) × Π fⱼ )   fⱼ = 每个惩罚系数
 *              再对 schema 未对齐的关系封顶 0.59（见 [UNALIGNED_CAP]）
 * ```
 *
 * base 用 noisy-OR 而不是求和：求和会超过 1，且会把「同一个事实被写了五遍」当成五份独立证据。
 * noisy-OR 是独立性假设下的「或」，天然递减 —— 一条 MPJ_JOIN(.95) 就是 0.95，
 * 再加四处 Service 组装(.75) 只把它推到 0.999（封顶 0.99），不会无限膨胀。
 * 权重按降序相乘，保证浮点结果与证据顺序无关（事实源要可 diff）。
 *
 * 惩罚表达的是 AGENTS.md 的那句话：置信度不只表达「关系存在的把握」，还要表达**证据之间是否自洽**。
 * 代码可能写错，从错误代码推断出的关系就是错的，所以自洽性必须进分数，而不是只在报告里附一条警告。
 *
 * | trigger | 系数 | 为什么 |
 * |---|---|---|
 * | `type_mismatch` HIGH | ×0.55 | `bigint` 连 `varchar` 这种多半根本不是外键关系，是推错了 |
 * | `type_mismatch` MEDIUM | ×0.80 | unsigned/符号差异，MySQL 会隐式转换，关系大概率是真的、JOIN 是坏的 |
 * | `type_mismatch` LOW | ×0.95 | 只有长度/精度差异，或候选本身只有弱命名证据 |
 * | `ambiguous_loser` | ×0.45 | 消歧选中了别的落点，本条是竞争者 |
 * | `ambiguous_unresolved` | ×0.65 | 五条依据全并列，选不出来 —— 每条落点都要扣，不悄悄选一个 |
 * | `ambiguous_winner` | ×0.95 | 胜出但存在竞争落点，「有竞争」本身要留一点痕 |
 * | `reciprocal` | ×0.60 | 反向关系也被推断出来了，两处代码至少有一处写错 |
 * | `direction_unresolved` | ×0.50 | 键元组并列，from/to 可能互换，而事实源断言了方向 |
 * | `target_not_key` | ×0.70 | 落点在库里有、但既不是主键也不是唯一键 —— 外键不会指向非键列 |
 *
 * **方向类惩罚（`reciprocal` 与 `direction_unresolved`）只取最重的一条，不叠乘**：
 * 两者说的是同一件事（哪一端才是外键说不准），叠乘等于为一个问题扣两次分。
 *
 * **②隔离缺失不参与打分**：`deleted` / `tenant_id` 缺失是查询链路的问题，不影响「这条关系是否存在」，
 * 折算成分数只会让关系图失真。它挂 `Conflict.needsReview`（见 [Conflict]）原样进事实源，交给人确认。
 */
object ConfidenceScorer {

    /** 证据侧的分数上限：推断出来的关系不给 1.0，1.0 只属于声明了的外键 */
    const val CAP = 0.99

    /**
     * 四舍五入前加的极小量。IEEE-754 会把 `0.70 × 0.95` 存成 0.6649999999999999，
     * 直接 round 就得到 0.66 —— 而验收要求「置信度与手算一致」，手算是 0.665 → 0.67。
     * 加 1e-9 让落在 .xx5 边界上的值按十进制 HALF_UP 走，其余值不受影响（远小于 0.005 的间隔）。
     */
    private const val EPSILON = 1e-9

    val TYPE_MISMATCH_FACTOR = mapOf(Severity.HIGH to 0.55, Severity.MEDIUM to 0.80, Severity.LOW to 0.95)
    const val AMBIGUOUS_LOSER = 0.45
    const val AMBIGUOUS_UNRESOLVED = 0.65
    const val AMBIGUOUS_WINNER = 0.95
    const val RECIPROCAL = 0.60
    const val DIRECTION_UNRESOLVED = 0.50
    const val TARGET_NOT_KEY = 0.70

    /**
     * schema 核对不了的关系（代码引用的表/列不在本库）分数封顶在这里 —— 刚好压在虚线下面。
     *
     * 证据侧的 ×0.5（[SchemaAligner][org.dw.datamodelrobot.evidence.SchemaAligner]）不足以压住它：
     * 实测项目里一对父子表有三处 MPJ JOIN 写着，noisy-OR 叠加后是 0.855，
     * 会顶着 HIGH 档进事实源。可这条关系在**这个库**里根本不存在，给它「高置信」这个标签是误导。
     * 折扣是证据侧的、封顶是结论侧的，两者目的不同，不算重复扣分。
     */
    const val UNALIGNED_CAP = 0.59

    /**
     * @param conflicts Step 1-4 的冲突清单。打分只从里面取①（类型不匹配）—— 它的严重度分档规则
     *   （含「弱候选一律降 LOW」）住在 [ConflictDetector][org.dw.datamodelrobot.conflict.ConflictDetector]，
     *   不在这重算一遍。③④ 是关系图的纯函数，直接从图里算，不依赖冲突清单里的字符串
     * @param hints 代码侧类型提示（[org.dw.datamodelrobot.evidence.TypeHintIndex.collect]），
     *   ③消歧的第五条依据。**必须与算 [conflicts] 时传的是同一份**，否则事实源里关系的消歧结论
     *   会与冲突段的 resolution 自相矛盾
     */
    fun score(
        graph: RelationGraph,
        conflicts: List<Conflict> = emptyList(),
        hints: Map<String, TypeHint> = emptyMap(),
    ): ScoredGraph {
        val typeMismatch = conflicts.filter { it.type == ConflictType.TYPE_MISMATCH }.associateBy { it.subject }
        val byKey = graph.byKey()
        val resolutions = graph.competing().mapValues { (_, list) -> TargetResolver.resolve(list, hints) }
        val scored = graph.relations
            .map { scoreOne(it, byKey, resolutions, typeMismatch) }
            .sortedBy { it.key.text }
        return ScoredGraph(scored, stats(scored))
    }

    private fun scoreOne(
        candidate: RelationCandidate,
        byKey: Map<String, RelationCandidate>,
        resolutions: Map<String, TargetResolver.Resolution>,
        typeMismatch: Map<String, Conflict>,
    ): ScoredRelation {
        val base = minOf(CAP, noisyOr(candidate.evidences.map { it.weight }))
        val ambiguity = resolutions[candidate.key.fromEnd]?.let { ambiguityOf(candidate, it) }
        val penalties = mutableListOf<Penalty>()

        typeMismatch[candidate.key.text]?.let { c ->
            penalties += Penalty(
                trigger = "type_mismatch",
                factor = TYPE_MISMATCH_FACTOR.getValue(c.severity),
                reason = c.detail,
            )
        }

        ambiguity?.let { a ->
            val (factor, why) = when (a.outcome) {
                AmbiguityOutcome.WINNER ->
                    AMBIGUOUS_WINNER to "多义指向 ${a.targets.size} 个落点，消歧选中本条（${a.reason}）"
                AmbiguityOutcome.LOSER ->
                    AMBIGUOUS_LOSER to "多义指向 ${a.targets.size} 个落点，消歧选中了别的落点（${a.reason}）"
                AmbiguityOutcome.UNRESOLVED ->
                    AMBIGUOUS_UNRESOLVED to "多义指向 ${a.targets.size} 个落点且消歧不了（${a.reason}），每条落点都扣分"
            }
            penalties += Penalty("ambiguous_${a.outcome.name.lowercase()}", factor, why)
        }

        // 方向类惩罚只取最重的一条
        listOfNotNull(
            byKey[candidate.key.reversed.text]?.let { other ->
                Penalty(
                    "reciprocal", RECIPROCAL,
                    "反向关系 ${other.key.text} 也被推断出来了（${other.sources.joinToString { it.name }}），两处至少有一处写错",
                )
            },
            candidate.directionUnresolved.takeIf { it }?.let {
                Penalty("direction_unresolved", DIRECTION_UNRESOLVED, "方向选不出来（键元组并列），from/to 可能互换")
            },
        ).minByOrNull { it.factor }?.let { penalties += it }

        val s = candidate.signals
        if (s.toColumnInSchema && !s.toIsPrimaryKey && !s.toIsUniqueKey) {
            penalties += Penalty(
                "target_not_key", TARGET_NOT_KEY,
                "落点 ${candidate.key.toEnd} 在库里既不是主键也不是唯一键（外键不会指向非键列）",
            )
        }

        val raw = round2(base * penalties.fold(1.0) { acc, p -> acc * p.factor })
        val capped = !candidate.schemaAligned && raw > UNALIGNED_CAP
        val confidence = if (capped) UNALIGNED_CAP else raw
        val reviewReasons = reviewReasons(candidate, ambiguity, penalties, capped)
        return ScoredRelation(
            candidate = candidate,
            baseScore = round3(base),
            penalties = penalties,
            confidence = confidence,
            band = ConfidenceBand.of(confidence),
            ambiguity = ambiguity,
            needsReview = reviewReasons.isNotEmpty(),
            reviewReasons = reviewReasons,
        )
    }

    private fun ambiguityOf(candidate: RelationCandidate, r: TargetResolver.Resolution): Ambiguity {
        val targets = r.ordered.map { it.key.toTable }.distinct().sorted()
        val outcome = when {
            !r.resolved -> AmbiguityOutcome.UNRESOLVED
            r.winner?.key == candidate.key -> AmbiguityOutcome.WINNER
            else -> AmbiguityOutcome.LOSER
        }
        return Ambiguity(targets, outcome, r.reason)
    }

    private fun reviewReasons(
        candidate: RelationCandidate,
        ambiguity: Ambiguity?,
        penalties: List<Penalty>,
        capped: Boolean,
    ): List<String> = penalties.mapNotNull { p ->
        when (p.trigger) {
            "ambiguous_unresolved" -> "多义指向消歧不了：${ambiguity?.targets?.joinToString(", ")}"
            "direction_unresolved" -> "方向未定：from/to 可能互换"
            "reciprocal" -> "双向不一致：反向关系也被推断出来了"
            else -> null
        }
    } + if (!candidate.schemaAligned) {
        listOf(
            "代码引用的表/列不在本库：代码与 schema 已漂移，要人确认哪边是对的" +
                if (capped) "（置信度封顶 $UNALIGNED_CAP：schema 核对不了的关系不给实线）" else "",
        )
    } else {
        emptyList()
    }

    /** noisy-OR：`1 - Π(1 - wᵢ)`。权重降序相乘，浮点结果与输入顺序无关 */
    fun noisyOr(weights: List<Double>): Double {
        var miss = 1.0
        for (w in weights.sortedDescending()) miss *= 1.0 - w.coerceIn(0.0, CAP)
        return 1.0 - miss
    }

    private fun round2(v: Double): Double = round(v * 100 + EPSILON) / 100.0
    private fun round3(v: Double): Double = round(v * 1000 + EPSILON) / 1000.0

    private fun stats(scored: List<ScoredRelation>): ScoreStats {
        val confidences = scored.map { it.confidence }.sorted()
        return ScoreStats(
            relations = scored.size,
            byBand = ConfidenceBand.entries.associateWith { b -> scored.count { it.band == b } },
            solidLine = scored.count { it.solid },
            dashedLine = scored.count { !it.solid },
            min = confidences.firstOrNull() ?: 0.0,
            max = confidences.lastOrNull() ?: 0.0,
            mean = if (scored.isEmpty()) 0.0 else round3(confidences.average()),
            median = median(confidences),
            penaltyHits = scored.flatMap { it.penalties }.groupingBy { it.trigger }.eachCount().toSortedMap(),
            needsReview = scored.count { it.needsReview },
            ambiguousWinner = scored.count { it.ambiguity?.outcome == AmbiguityOutcome.WINNER },
            ambiguousLoser = scored.count { it.ambiguity?.outcome == AmbiguityOutcome.LOSER },
            ambiguousUnresolved = scored.count { it.ambiguity?.outcome == AmbiguityOutcome.UNRESOLVED },
            unaligned = scored.count { !it.schemaAligned },
            byTier = SupportTier.entries.associateWith { t ->
                val list = scored.filter { it.tier == t }.map { it.confidence }
                if (list.isEmpty()) 0.0 else round3(list.average())
            },
        )
    }

    private fun median(sorted: List<Double>): Double = when {
        sorted.isEmpty() -> 0.0
        sorted.size % 2 == 1 -> sorted[sorted.size / 2]
        else -> round3((sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2)
    }
}
