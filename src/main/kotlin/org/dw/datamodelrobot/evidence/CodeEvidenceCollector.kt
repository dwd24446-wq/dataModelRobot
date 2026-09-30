package org.dw.datamodelrobot.evidence

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.dw.datamodelrobot.phase0.ColumnUsage
import org.dw.datamodelrobot.phase0.EntityIndex
import org.dw.datamodelrobot.phase0.EntityInfo
import org.dw.datamodelrobot.phase0.MybatisCalls
import org.dw.datamodelrobot.phase0.RefRecord
import org.dw.datamodelrobot.phase0.SFunctionScan
import org.dw.datamodelrobot.schema.DbSchema
import org.dw.datamodelrobot.schema.TableNaming

/**
 * 代码侧证据采集。三种形态（前两种是「一次调用里出现两个列引用」，第三种是「同一方法内两侧都被摸过」）：
 *
 * - `leftJoin(X.class, X::getId, Y::getXId)` → MPJ_JOIN(.95)。实测项目里整个模块的 Mapper 全是这个模板
 * - `leftJoin(X.class, on -> on.eq(X::getId, Y::getXId))` → LAMBDA_EQ(.85) + JOIN 语境。
 *   on-lambda 里 JOIN 调用本身没有 SFunction 实参，证据由内层 eq 产出（实测项目里有整片模块是这种写法）
 * - Service 层组装 → SELECT_ASSOCIATION(.75)：方法内读了 A.x_id，又按主键查了 B。两种形态都要认：
 *   `userMapper.selectList(User::getId, ids)`（B 侧有方法引用）与 `userMapper.selectByIds(ids)`
 *   （B 侧只能从 receiver 类型认出来 —— 实测项目上百处批量查全是这种，只按方法引用配对会全漏）
 *
 * 传了 schema 就顺手做对齐（[SchemaAligner]）：引用了库里不存在的表/列 → 降权 + 记下原因。
 *
 * 性能纪律：先按**调用名**筛（JOIN ∪ EQUALITY）、按**引用名**筛（`*Id` 结尾），才去 resolve。
 * 单侧的 `eq(列, 值)` 在真实项目里有几千处，一处 resolve 都不做。
 */
object CodeEvidenceCollector {

    /** 读取类调用的名字前缀（组装证据只认读侧，写侧的巧合不算关系） */
    private val LOOKUP_NAME_PREFIXES = listOf("select", "list", "find", "query", "count", "exists", "get")

    /** receiver 名字后缀：先按名字筛再解析类型，避免给每个读取调用都推一次 receiver 类型 */
    private val MAPPER_RECEIVER_SUFFIXES = listOf("Mapper", "Dao", "Repository")

    private fun isMapperName(name: String): Boolean = MAPPER_RECEIVER_SUFFIXES.any { name.endsWith(it) }

    data class Stats(
        val joinCalls: Int,
        val joinWithTwoColumns: Int,
        val joinOnLambda: Int,
        /** JOIN 实参是变量而非方法引用：框架里的 pass-through 定义（泛型未具体化），产不出证据 */
        val joinPassThrough: Int,
        val equalityTwoSided: Int,
        val selectAssociation: Int,
        val unhandled: List<String>,
    )

    data class Result(val evidences: List<Evidence>, val stats: Stats) {
        fun byRelation(): Map<String, List<Evidence>> = evidences.groupBy { it.key }
        fun byType(): Map<EvidenceType, Int> = evidences.groupingBy { it.type }.eachCount()
    }

    /** 必须在 ReadAction 内调用。 */
    fun collect(project: Project, index: EntityIndex, schema: DbSchema? = null): Result {
        // 同一个引用可能被 JOIN 通道和组装通道各看一次，按 (文件, 偏移) 缓存解析结果
        val cache = HashMap<Pair<String?, Int>, RefRecord>()
        fun classify(ref: PsiMethodReferenceExpression): RefRecord =
            cache.getOrPut(ref.containingFile?.virtualFile?.path to ref.textOffset) {
                SFunctionScan.classify(ref, index)
            }

        val evidences = mutableListOf<Evidence>()
        val unhandled = mutableListOf<String>()
        var joinCalls = 0
        var joinTwo = 0
        var joinLambda = 0
        var joinPass = 0
        var eqTwo = 0

        for (call in candidateCalls(project)) {
            val name = call.methodExpression.referenceName ?: continue
            val callArgs = call.argumentList.expressions
            val refs = callArgs.filterIsInstance<PsiMethodReferenceExpression>()

            if (name in MybatisCalls.JOIN) {
                joinCalls++
                when {
                    refs.size == 2 -> {
                        val a = classify(refs[0])
                        val b = classify(refs[1])
                        if (a.success && b.success && a.table != b.table) {
                            evidences += build(EvidenceType.MPJ_JOIN, a, b, name, MybatisCalls.joinTypeOf(name), joinClassOf(call), schema, call)
                            joinTwo++
                        } else {
                            unhandled += "JOIN 的两侧没解析成不同表的列: ${loc(call)} ${shortText(call)}"
                        }
                    }
                    refs.isEmpty() && callArgs.any { it is PsiLambdaExpression } -> joinLambda++
                    refs.isEmpty() -> joinPass++
                    else -> unhandled += "JOIN 形态未覆盖(${refs.size} 个方法引用): ${loc(call)} ${shortText(call)}"
                }
                continue
            }

            if (name in MybatisCalls.EQUALITY && refs.size == 2) {
                val a = classify(refs[0])
                val b = classify(refs[1])
                if (!a.success || !b.success || a.table == b.table) continue // 同表两列比较不是关系
                val joinCall = MybatisCalls.enclosingJoinCall(call)
                evidences += build(
                    EvidenceType.LAMBDA_EQ, a, b, name,
                    MybatisCalls.joinTypeOf(joinCall?.methodExpression?.referenceName),
                    joinCall?.let { joinClassOf(it) }, schema, call,
                )
                eqTwo++
            }
        }

        val associations = associationEvidence(project, index, schema, evidences, ::classify)
        evidences += associations

        // 传了 schema 就顺手对齐：引用了库里不存在的表/列 → 降权 + 留痕（Step 1-5 再叠冲突惩罚）
        val aligned = if (schema == null) evidences.toList() else SchemaAligner.align(evidences, schema)

        return Result(
            aligned,
            Stats(joinCalls, joinTwo, joinLambda, joinPass, eqTwo, associations.size, unhandled),
        )
    }

    /**
     * Service 层组装：同一方法内「读了 A.x_id」+「按主键查了 B」+ 命名对得上 → A.x_id → B.pk。
     * 两种形态都要认，否则真实项目会整片漏掉：
     * - 形态一：方法里也把 B 的主键当条件用了（`userMapper.selectList(User::getId, ids)`）
     * - 形态二：只调了 B 的 Mapper（`userMapper.selectByIds(ids)`），**没有**任何指向 B 的方法引用
     *   —— 实测项目里 selectByIds/selectBatchIds 上百处，receiver 全是 xxxMapper，全是这个形态
     *
     * 已经被同方法内 JOIN/eq 证据覆盖的关系不再重复计分（否则同一段代码会被算两次）。
     */
    private fun associationEvidence(
        project: Project,
        index: EntityIndex,
        schema: DbSchema?,
        existing: List<Evidence>,
        classify: (PsiMethodReferenceExpression) -> RefRecord,
    ): List<Evidence> {
        val linked = existing.map { Triple(it.from.file, it.enclosingMethod, it.key) }.toMutableSet()
        val mapperEntities = HashMap<String, EntityInfo?>()
        val out = mutableListOf<Evidence>()

        fun emit(a: RefRecord, to: ColumnRef, callName: String?, method: PsiMethod) {
            val key = "${a.table}.${a.column}->${to.table}.${to.column}"
            if (!linked.add(Triple(a.file, method.name, key))) return
            out += Evidence(
                type = EvidenceType.SELECT_ASSOCIATION,
                from = columnRef(a),
                to = to,
                callName = callName,
                enclosingMethod = method.name,
                scenario = a.scenario,
                notes = listOf("oriented_by=naming_association"),
            )
        }

        for (method in allMethods(project)) {
            // 预筛：只有 getter 名以 Id 结尾的引用才可能是外键列，其余不 resolve
            val refs = PsiTreeUtil.findChildrenOfType(method, PsiMethodReferenceExpression::class.java)
                .filter { (it.referenceName ?: "").endsWith("Id") }
            if (refs.isEmpty()) continue
            val records = refs.map { classify(it) }.filter { it.success }
            val fkSide = records.filter { it.column?.endsWith("_id", ignoreCase = true) == true }
            if (fkSide.isEmpty()) continue

            val keySide = records.filter {
                (it.usage == ColumnUsage.FILTER || it.usage == ColumnUsage.JOIN_ON) && isTargetKey(it, schema)
            }
            val lookups = mapperLookups(method, index, mapperEntities)

            for (a in fkSide) {
                val fkColumn = a.column ?: continue
                for (b in keySide) {
                    if (a.table == b.table) continue
                    if (!TableNaming.matches(fkColumn, b.table ?: continue)) continue
                    emit(a, columnRef(b), b.callName, method)
                }
                for (lk in lookups) {
                    if (a.table == lk.table) continue
                    if (!TableNaming.matches(fkColumn, lk.table)) continue
                    emit(
                        a,
                        ColumnRef(lk.table, pkColumn(lk.table, lk.entity, schema), entityFqn = lk.entityFqn),
                        lk.callName,
                        method,
                    )
                }
            }
        }
        return out
    }

    private data class MapperLookup(val entity: EntityInfo, val entityFqn: String, val table: String, val callName: String)

    /**
     * 方法内「按主键查另一张表」的调用。receiver 先按名字筛（xxxMapper / xxxDao / xxxRepository），
     * 再解析类型找实体 —— 给每个读取调用都推一次 receiver 类型，在几千个文件的项目上太贵。
     */
    private fun mapperLookups(
        method: PsiMethod,
        index: EntityIndex,
        cache: MutableMap<String, EntityInfo?>,
    ): List<MapperLookup> {
        val out = mutableListOf<MapperLookup>()
        val enclosingClass = method.containingClass
        for (call in PsiTreeUtil.findChildrenOfType(method, PsiMethodCallExpression::class.java)) {
            val name = call.methodExpression.referenceName ?: continue
            if (LOOKUP_NAME_PREFIXES.none { name.startsWith(it) }) continue
            val qualifier = call.methodExpression.qualifierExpression
            val receiverClass = when {
                // mapper 接口自己的 default 方法里省略 receiver
                qualifier == null -> enclosingClass?.takeIf { isMapperName(it.name ?: "") }
                qualifier is PsiReferenceExpression && isMapperName(qualifier.referenceName ?: "") ->
                    (qualifier.type as? PsiClassType)?.resolve()
                else -> null
            } ?: continue
            val fqn = receiverClass.qualifiedName ?: continue
            val entity = if (cache.containsKey(fqn)) cache[fqn] else entityOfMapper(receiverClass, index).also { cache[fqn] = it }
            if (entity != null) out += MapperLookup(entity, entity.qualifiedName, entity.tableName, name)
        }
        return out
    }

    /** 沿继承链找「某个父类型被一个已索引实体具体化」，不依赖 BaseMapperX 这种脚手架专属类名 */
    private fun entityOfMapper(psiClass: PsiClass, index: EntityIndex): EntityInfo? {
        val queue = ArrayDeque<PsiClassType>()
        psiClass.superTypes.forEach { queue.add(it) }
        val seen = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            val type = queue.removeFirst()
            val cls = type.resolve() ?: continue
            val fqn = cls.qualifiedName ?: continue
            if (!seen.add(fqn)) continue
            for (arg in type.parameters) {
                val argClass = (arg as? PsiClassType)?.resolve() ?: continue
                index.find(argClass)?.let { return it }
            }
            cls.superTypes.forEach { queue.add(it) }
        }
        return null
    }

    private fun pkColumn(table: String, entity: EntityInfo?, schema: DbSchema?): String {
        val primary = schema?.table(table)?.indexes?.firstOrNull { it.name.equals("PRIMARY", ignoreCase = true) }?.columns
        if (primary != null && primary.size == 1) return primary[0]
        return entity?.columns?.get("id")?.column ?: "id"
    }

    private fun build(
        type: EvidenceType,
        a: RefRecord,
        b: RefRecord,
        callName: String,
        joinType: String?,
        joinClassFqn: String?,
        schema: DbSchema?,
        call: PsiMethodCallExpression,
    ): Evidence {
        val (from, to, orientedBy) = orient(a, b, joinClassFqn, schema)
        return Evidence(
            type = type,
            from = columnRef(from),
            to = columnRef(to),
            joinType = joinType,
            callName = callName,
            enclosingMethod = PsiTreeUtil.getParentOfType(call, PsiMethod::class.java)?.name,
            scenario = a.scenario ?: b.scenario,
            notes = listOf("oriented_by=$orientedBy"),
        )
    }

    /**
     * 定方向：from = 外键侧（多的一方），to = 被指向侧。
     * 依据强度依次是 schema 主键 → 列名就叫 id → 只有一个是 xxx_id → JOIN 的 Class 实参 → 实参顺序。
     * 用了哪条写进 notes，保证 explain_relation 能回答「凭什么这么连」。
     */
    private fun orient(
        a: RefRecord,
        b: RefRecord,
        joinClassFqn: String?,
        schema: DbSchema?,
    ): Triple<RefRecord, RefRecord, String> {
        // 只有两张表都在 schema 里才敢说「按主键定的方向」；否则 isTargetKey 会退化成「列名就叫 id」，
        // 那时候标 schema_pk 就是假的（notes 是 explain_relation 的依据，不能骗人）
        if (schema != null && schema.table(a.table ?: "") != null && schema.table(b.table ?: "") != null) {
            val aPk = isTargetKey(a, schema)
            val bPk = isTargetKey(b, schema)
            if (aPk != bPk) return if (aPk) Triple(b, a, "schema_pk") else Triple(a, b, "schema_pk")
        }
        val aId = a.column.equals("id", ignoreCase = true)
        val bId = b.column.equals("id", ignoreCase = true)
        if (aId != bId) return if (aId) Triple(b, a, "id_name") else Triple(a, b, "id_name")

        val aFk = a.column?.endsWith("_id", ignoreCase = true) == true
        val bFk = b.column?.endsWith("_id", ignoreCase = true) == true
        if (aFk != bFk) return if (aFk) Triple(a, b, "fk_suffix") else Triple(b, a, "fk_suffix")

        if (joinClassFqn != null) {
            if (a.entityFqn == joinClassFqn) return Triple(b, a, "join_class")
            if (b.entityFqn == joinClassFqn) return Triple(a, b, "join_class")
        }
        // MPJ 的 leftJoin(Class<T>, SFunction<T,?> left, SFunction<X,?> right)：第一个 SFunction 属于被 JOIN 的表
        return Triple(b, a, "arg_order")
    }

    /** r 指向的列是不是它那张表的单列主键（没有 schema 时退化成「列名就叫 id」） */
    private fun isTargetKey(r: RefRecord, schema: DbSchema?): Boolean {
        val column = r.column ?: return false
        val table = schema?.table(r.table ?: return false)
            ?: return column.equals("id", ignoreCase = true)
        val primary = table.indexes.firstOrNull { it.name.equals("PRIMARY", ignoreCase = true) }?.columns
            ?: table.columns.filter { it.key == "PRI" }.map { it.name }
        return primary.size == 1 && primary[0].equals(column, ignoreCase = true)
    }

    private fun columnRef(r: RefRecord) = ColumnRef(
        table = r.table!!,
        column = r.column!!,
        entityFqn = r.entityFqn,
        file = r.file,
        line = r.line,
        refText = r.refText,
    )

    private fun candidateCalls(project: Project): List<PsiMethodCallExpression> {
        val names = MybatisCalls.JOIN + MybatisCalls.EQUALITY
        val result = mutableListOf<PsiMethodCallExpression>()
        for (psiFile in javaFiles(project)) {
            for (call in PsiTreeUtil.findChildrenOfType(psiFile, PsiMethodCallExpression::class.java)) {
                if (call.methodExpression.referenceName in names) result.add(call)
            }
        }
        return result
    }

    private fun allMethods(project: Project): List<PsiMethod> =
        javaFiles(project).flatMap { PsiTreeUtil.findChildrenOfType(it, PsiMethod::class.java).toList() }

    private fun javaFiles(project: Project): List<PsiJavaFile> {
        val psiManager = PsiManager.getInstance(project)
        return FilenameIndex.getAllFilesByExt(project, "java", GlobalSearchScope.projectScope(project))
            .mapNotNull { psiManager.findFile(it) as? PsiJavaFile }
    }

    private fun joinClassOf(call: PsiMethodCallExpression): String? =
        call.argumentList.expressions
            .mapNotNull { (it as? PsiClassObjectAccessExpression)?.operand?.type as? PsiClassType }
            .firstOrNull()?.resolve()?.qualifiedName

    private fun loc(el: PsiElement): String {
        val file = el.containingFile ?: return "<unknown>:-1"
        val doc = PsiDocumentManager.getInstance(el.project).getDocument(file)
        val line = doc?.getLineNumber(el.textOffset)?.plus(1) ?: -1
        return "${file.virtualFile?.path?.substringAfterLast('/') ?: "<in-memory>"}:$line"
    }

    private fun shortText(call: PsiMethodCallExpression): String =
        call.text.replace(Regex("\\s+"), " ").take(90)
}
