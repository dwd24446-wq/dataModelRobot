package org.dw.datamodelrobot.evidence

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.dw.datamodelrobot.phase0.RefRecord

/**
 * 「这个列到底有没有被引用过」的多口径索引 —— 规则⑤（孤儿字段）的地基。
 *
 * Phase 0 只认**方法引用**（`Entity::getX`），而真实代码里更常见的是普通 getter/setter/builder 调用、
 * QueryWrapper 的字符串列名、Mapper XML。只看方法引用的后果是实测过的：Step 1-4a 在真实项目上
 * 报出 85 个「孤儿」，抽样 6 个逐个 grep，**6 个全是假阳性**（`member_user.area_id` 在 member 模块
 * 有 18 次 `getAreaId()`）。加上 B/C/D 三个口径后同一批只剩 1 个。
 *
 * 四个口径，从精确到保守：
 * - **A 方法引用**：Phase 0 解析出的 `表.列`（唯一能定位到表的口径）
 * - **B 访问器调用**：项目里出现过 `getFooId(` / `setFooId(` / `fooId(`（Lombok builder）就算所有表的
 *   `foo_id` 被引用过。**故意不解析 receiver** —— 解析几万次调用的代价换不来价值，
 *   而规则⑤要的是「宁可漏报也不要误报」。写形态必须算：只写不读的列不是孤儿
 * - **C 字符串字面量**：`.eq("user_id", …)` 这种 QueryWrapper 写法（实测项目里有几百个文件用 QueryWrapper）
 * - **D XML 分词**：Mapper XML 里的列名（按非单词字符切分后精确匹配，不做子串匹配）
 *
 * B/C/D 都是**列名级**而不是 `表.列` 级，所以 `getUserId()` 出现一次就会掩护全库所有 `user_id` 列。
 * 这是有意的：⑤的结论是「可以考虑清理这一列」，误报的代价（删掉活列）远高于漏报。
 */
object ColumnReferenceIndex {

    /** 列名形状：小写字母/数字/下划线，且至少含一个下划线或长度 > 2，避免把 `"a"` 这类字面量收进来 */
    private val COLUMN_SHAPE = Regex("[a-z][a-z0-9_]{2,}")

    /**
     * 四个口径的命中集合，**一律小写**（[scan] 存进来时就转好了）。
     * 查询侧把表名/列名也小写后再比，所以 MySQL 的大小写不敏感口径是成立的。
     */
    data class Result(
        /** 口径 A：`表.列`，小写 */
        val methodRefs: Set<String>,
        /** 口径 B：出现过的调用名，小写（`getuserid` / `setuserid`） */
        val accessorNames: Set<String>,
        /** 口径 C：列名形状的字符串字面量，小写 */
        val literals: Set<String>,
        /** 口径 D：XML 里的单词，小写 */
        val xmlTokens: Set<String>,
    ) {
        /** 某列被哪些口径命中过（报告用，空列表 = 四个口径全没有 = 孤儿）。[property] 是实体属性名，有就优先用它拼访问器名 */
        fun referencedBy(table: String, column: String, property: String? = null): List<String> {
            val col = column.lowercase()
            val hit = mutableListOf<String>()
            if ("${table.lowercase()}.$col" in methodRefs) hit += "方法引用"
            if (accessorNamesFor(column, property).any { it.lowercase() in accessorNames }) hit += "getter/setter 调用"
            if (col in literals) hit += "字符串字面量"
            if (col in xmlTokens) hit += "XML"
            return hit
        }

        fun isReferenced(table: String, column: String, property: String? = null): Boolean =
            referencedBy(table, column, property).isNotEmpty()
    }

    /**
     * 列名 → 可能碰到它的调用名。四种形态，每种都在真实项目上实测到过：
     * - `getFooId()` / `isFooId()`：读（`deleted` 这类布尔列 Lombok 生成 `isDeleted()`）
     * - `setFooId()`：只写不读的列（`CouponServiceImpl.java:73` 的 `.setUseOrderId(orderId)`）不是孤儿
     * - `fooId()`：Lombok `@Builder` 的链式设值（`ApiErrorLogServiceImpl.java:77` 的 `.processUserId(id)`）
     *
     * [property] 传实体属性名：`@TableField` 改过名时列名推不出属性名。
     */
    fun accessorNamesFor(column: String, property: String? = null): List<String> {
        val prop = property ?: camelCase(column)
        val cap = prop.replaceFirstChar { it.uppercase() }
        return listOf("get$cap", "set$cap", "is$cap", prop)
    }

    private fun camelCase(column: String): String {
        val parts = column.lowercase().split('_').filter { it.isNotEmpty() }
        return parts.firstOrNull().orEmpty() +
            parts.drop(1).joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    /** 必须在 ReadAction 内调用。[records] 是 Phase 0 的扫描结果，提供口径 A。 */
    fun scan(project: Project, records: List<RefRecord>): Result {
        val methodRefs = records.filter { it.success }
            .mapNotNull { r -> r.table?.let { "$it.${r.column}".lowercase() } }.toSet()

        val accessors = HashSet<String>()
        val literals = HashSet<String>()
        val xml = HashSet<String>()
        val psiManager = PsiManager.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)

        for (vf in FilenameIndex.getAllFilesByExt(project, "java", scope)) {
            val file = psiManager.findFile(vf) as? PsiJavaFile ?: continue
            for (call in PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression::class.java)) {
                call.methodExpression.referenceName?.let { accessors += it.lowercase() }
            }
            for (lit in PsiTreeUtil.findChildrenOfType(file, PsiLiteralExpression::class.java)) {
                val v = lit.value as? String ?: continue
                if (COLUMN_SHAPE.matches(v)) literals += v.lowercase()
            }
        }
        for (vf in FilenameIndex.getAllFilesByExt(project, "xml", scope)) {
            val text = psiManager.findFile(vf)?.text ?: continue
            for (token in text.split(Regex("[^A-Za-z0-9_]+"))) {
                if (token.length > 2) xml += token.lowercase()
            }
        }
        return Result(methodRefs, accessors, literals, xml)
    }
}
