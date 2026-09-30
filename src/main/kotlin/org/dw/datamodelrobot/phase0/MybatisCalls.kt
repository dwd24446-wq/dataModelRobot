package org.dw.datamodelrobot.phase0

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiMethodReferenceExpression

/**
 * MyBatis-Plus / mybatis-plus-join 的调用名词表 + 列引用角色判定。
 * 只按**引用名**分流，不做 resolve（一次全量扫描要过成千上万个引用，resolve 太贵）。
 */
object MybatisCalls {

    /**
     * 不收裸 `join`：实测项目里几十处 `.join(` 全是 `String.join` / `CollUtil.join`，
     * 收进来只会污染统计（MPJ 的实际写法就是 leftJoin/innerJoin/rightJoin 三个）。
     */
    val JOIN = setOf("leftJoin", "innerJoin", "rightJoin")

    val FILTER = setOf(
        "eq", "ne", "gt", "ge", "lt", "le", "like", "notLike", "likeLeft", "likeRight",
        "in", "notIn", "between", "notBetween", "isNull", "isNotNull", "exists", "notExists", "apply",
        "eqIfPresent", "neIfPresent", "gtIfPresent", "geIfPresent", "ltIfPresent", "leIfPresent",
        "likeIfPresent", "inIfPresent", "betweenIfPresent", "allEq",
        // BaseMapperX 的按条件查删：形参就是 (SFunction, 值)，语义等同 filter。
        // 刻意不收 selectCount —— 它在 MPJ 里是投影（selectCount(列, "别名")），与 MP 的按条件计数同名不同义。
        "selectOne", "selectList", "selectPage", "selectByIds", "selectBatchIds", "delete", "deleteOne",
    )

    /** 只有等值/不等值两侧是列时才构成关系证据；gt/lt/like 即使两侧都是列也不算 */
    val EQUALITY = setOf("eq", "ne", "eqIfPresent", "neIfPresent")

    val SELECT = setOf(
        "select", "selectAs", "selectCount", "selectSum", "selectMax", "selectMin", "selectAvg",
        "selectAll", "selectCollection", "selectAssociation", "selectSub",
    )

    val ORDER = setOf("orderBy", "orderByAsc", "orderByDesc")
    val GROUP = setOf("groupBy", "having")
    val SET = setOf("set", "setSql")

    fun joinTypeOf(callName: String?): String? = when (callName) {
        "leftJoin" -> "LEFT"
        "innerJoin" -> "INNER"
        "rightJoin" -> "RIGHT"
        else -> null
    }

    fun usageOf(ref: PsiMethodReferenceExpression, call: PsiMethodCallExpression?): ColumnUsage {
        val name = call?.methodExpression?.referenceName ?: return ColumnUsage.OTHER
        if (name in JOIN) return ColumnUsage.JOIN_ON
        if (enclosingJoinCall(ref) != null) return ColumnUsage.JOIN_ON
        return when (name) {
            in FILTER -> ColumnUsage.FILTER
            in SELECT -> ColumnUsage.SELECT
            in ORDER -> ColumnUsage.ORDER
            in GROUP -> ColumnUsage.GROUP
            in SET -> ColumnUsage.SET
            else -> ColumnUsage.OTHER
        }
    }

    /**
     * 最近的 JOIN 调用（用于识别 `join(X.class, on -> on.eq(A::a, B::b))` 的 on 语境）。
     * 撞到不属于 join 实参的 lambda 就停，避免把 `selectList(x -> ...)` 里的 eq 误判成 JOIN 条件。
     */
    fun enclosingJoinCall(el: PsiElement): PsiMethodCallExpression? {
        var cur: PsiElement? = el.parent
        while (cur != null) {
            if (cur is PsiMethodCallExpression && cur.methodExpression.referenceName in JOIN) return cur
            if (cur is PsiLambdaExpression) {
                val call = (cur.parent as? PsiExpressionList)?.parent as? PsiMethodCallExpression
                return call?.takeIf { it.methodExpression.referenceName in JOIN }
            }
            cur = cur.parent
        }
        return null
    }
}
