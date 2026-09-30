package org.dw.datamodelrobot.evidence

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import org.dw.datamodelrobot.phase0.EntityIndex
import org.dw.datamodelrobot.phase0.RefRecord

/**
 * 某个 from 列的**代码侧类型提示**：哪些落点被「引用过这个列的文件」的 import 支持。
 *
 * [supported] 的 key 是落点表名（小写），value 是支持它的文件名（去掉路径，便于进产物）。
 * 只有 [discriminating] 非 null 才算消歧成功 —— 两个落点都被 import 支持等于什么都没说，
 * 这时候宁可判「消歧不了」，不猜。
 */
data class TypeHint(val supported: Map<String, List<String>>) {

    val discriminating: String? get() = supported.keys.singleOrNull()

    fun describe(): String = supported.toSortedMap()
        .entries.joinToString("；") { (table, files) -> "$table ← ${files.joinToString(", ")}" }
}

/**
 * 消歧的第五条依据，也是唯一能翻**跨模块通用名**的一条。
 *
 * 为什么需要它：schema 侧的四条依据（代码证据 / 表名公共前缀 / 命名强度 / 证据权重）在
 * `trade_order.user_id` 这种列上全部失效 —— `member_user` 与 `system_users` 都是 module_prefix 强命中、
 * 公共前缀都是 0 段，从库里看两个落点一模一样。实测项目上这样的列有二十来个（`user_id` 占大头）。
 * 但代码里写着答案：引用 `TradeOrderDO::getUserId` 的那个文件 import 的是 `MemberUserApi` 还是 `AdminUserApi`。
 *
 * 认 import 而不是认变量类型：这类脚手架跨模块只依赖对方的 `-api` 模块，拿到的是 `MemberUserApi` /
 * `MemberUserRespVO`，不是 DO。所以按**实体简单名去掉 DO 后缀**做前缀匹配（`MemberUserDO` → `MemberUser`，
 * 命中 `MemberUserApi` / `MemberUserService` / `MemberUserRespVO` / `MemberUserDO`），
 * 并要求前缀后一位是大写字母，避免把 `UserGroup…` 之外的东西误认成 `User` 家族。
 *
 * 有意保守的三处：
 * - 只看**引用过这一列**的文件（Phase 0 的解析记录给出 `表.列 → 文件`），不扫全项目
 * - 前缀过匹配会同时支持两个落点（`UserGroupDO` 既像 `User` 又像 `UserGroup`）→ 结果是并列 → 不选边
 * - 落点表没有对应实体（库里没有的表）→ 拿不到家族名 → 不支持也不反对
 *
 * 同包的类不需要 import，所以这条会漏；它是**提示**而不是证据，漏了就退回 schema 侧的四条依据。
 */
object TypeHintIndex {

    /** 家族名短于这个长度就不认：`Id`、`User` 这种前缀会命中一大片无关 import */
    private const val MIN_FAMILY_LENGTH = 4

    /**
     * @param groups from 列（`表.列`，小写）→ 它的候选落点表名，一般直接传
     *   `graph.competing().mapValues { (_, v) -> v.map { it.key.toTable } }`
     * @param records Phase 0 的解析记录，用来找「哪些文件引用了这一列」
     */
    fun collect(
        project: Project,
        records: List<RefRecord>,
        index: EntityIndex,
        groups: Map<String, List<String>>,
    ): Map<String, TypeHint> {
        if (groups.isEmpty()) return emptyMap()
        val familyByTable = index.entitiesByFqn.values
            .associate { it.tableName.lowercase() to familyOf(it.qualifiedName) }
            .filterValues { it.length >= MIN_FAMILY_LENGTH }

        val filesByEnd = LinkedHashMap<String, MutableSet<String>>()
        for (r in records) {
            if (!r.success) continue
            val table = r.table ?: continue
            val end = "$table.${r.column}".lowercase()
            if (end in groups) filesByEnd.getOrPut(end) { LinkedHashSet() } += r.file
        }
        if (filesByEnd.isEmpty()) return emptyMap()

        val wanted = filesByEnd.values.flatten().toHashSet()
        val importsByFile = readImports(project, wanted)

        return filesByEnd.mapValues { (end, files) ->
            val supported = LinkedHashMap<String, MutableList<String>>()
            for (path in files.sorted()) {
                val imports = importsByFile[path].orEmpty()
                if (imports.isEmpty()) continue
                for (table in groups.getValue(end).distinct().sorted()) {
                    val family = familyByTable[table] ?: continue
                    if (imports.any { matchesFamily(it, family) }) {
                        supported.getOrPut(table) { mutableListOf() } += path.substringAfterLast('/')
                    }
                }
            }
            TypeHint(supported.mapValues { (_, v) -> v.distinct().sorted() })
        }
    }

    private fun readImports(project: Project, wanted: Set<String>): Map<String, List<String>> {
        val psiManager = PsiManager.getInstance(project)
        val out = HashMap<String, List<String>>()
        for (vf in FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project))) {
            if (vf.path !in wanted) continue
            val file = psiManager.findFile(vf) as? PsiJavaFile ?: continue
            out[vf.path] = file.importList?.allImportStatements.orEmpty()
                .mapNotNull { it.importReference?.qualifiedName?.substringAfterLast('.') }
        }
        return out
    }

    internal fun familyOf(fqn: String): String = fqn.substringAfterLast('.').removeSuffix("DO")

    /**
     * 家族前缀匹配：`MemberUser` 认 `MemberUserDO` / `MemberUserApi` / `MemberUserRespVO`，
     * 但要求前缀后一位是大写字母，`MemberUsers` 这种复数/拼接不认。
     * 过匹配（`User` 命中 `UserGroupDO`）会同时支持两个落点 → 并列 → 不选边，所以是安全方向。
     */
    internal fun matchesFamily(imported: String, family: String): Boolean =
        imported == family ||
            (imported.length > family.length && imported.startsWith(family) && imported[family.length].isUpperCase())
}
