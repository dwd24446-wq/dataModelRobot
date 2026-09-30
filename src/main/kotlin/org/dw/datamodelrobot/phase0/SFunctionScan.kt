package org.dw.datamodelrobot.phase0

import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.util.PsiTreeUtil

/**
 * 解析档位（fixture 场景矩阵的定义）：
 * - A：类级泛型，SFunction<T,?> 的 T 由 wrapper/mapper 声明决定
 * - B：方法级泛型，S 由方法引用自身推断（可与 wrapper 的 T 不同实体，跨实体判例 S6）
 * - R_QUALIFIER_ONLY：外围调用完全无法 resolve，实体由方法引用自身限定符反推（进分母）
 * - C_UNRESOLVED_GENERIC：调用处类型变量未具体化（定义处语境），单列不进分母
 * - D_NON_ENTITY：引用目标不是 @TableName 实体（VO/BO），单列不进分母
 * - NON_SFUNCTION：函数式接口不是 SFunction（如 Stream.map），不进分母
 * - NO_CONTEXT：方法引用不是调用的直接实参（赋值给变量等），单列
 */
enum class RefTier { A, B, R_QUALIFIER_ONLY, C_UNRESOLVED_GENERIC, D_NON_ENTITY, NON_SFUNCTION, NO_CONTEXT }

/** 列引用在查询里扮演的角色。证据采集、孤儿字段、租户隔离缺失三条规则都要靠它分流。 */
enum class ColumnUsage { JOIN_ON, FILTER, SELECT, ORDER, GROUP, SET, OTHER }

data class RefRecord(
    val file: String,
    val line: Int,
    val refText: String,
    /** fixture javadoc 里的 [S#] 编号；真实项目上为 null */
    val scenario: String?,
    /** 所在方法名，失败样本定位用 */
    val enclosingMethod: String?,
    val tier: RefTier,
    val entityFqn: String?,
    val property: String?,
    val table: String?,
    val column: String?,
    /** getter 方法本身是否 resolve 成功——命门 2（Lombok light method）的直接信号 */
    val getterResolved: Boolean,
    /** 实体来源：SAM=SFunction 类型实参推断；QUALIFIER=方法引用限定符兜底 */
    val entitySource: String?,
    val failureReason: String?,
    /** 外围调用名（eq / leftJoin / select / map …），不做 resolve，直接取引用名 */
    val callName: String? = null,
    val usage: ColumnUsage = ColumnUsage.OTHER,
) {
    val success: Boolean get() = failureReason == null && table != null && column != null
    /** 是否进成功率分母 */
    val inDenominator: Boolean
        get() = tier == RefTier.A || tier == RefTier.B || tier == RefTier.R_QUALIFIER_ONLY
}

object SFunctionScan {

    private const val SFUNCTION_FQN = "com.baomidou.mybatisplus.core.toolkit.support.SFunction"

    fun classify(ref: PsiMethodReferenceExpression, index: EntityIndex): RefRecord {
        val file = ref.containingFile.virtualFile?.path ?: "<in-memory>"
        val line = lineOf(ref)
        val enclosing = PsiTreeUtil.getParentOfType(ref, PsiMethod::class.java)
        val refText = shortText(ref)
        val scenario = scenarioTag(enclosing)
        val getter = try { ref.resolve() as? PsiMethod } catch (e: Exception) { null }
        val getterResolved = getter != null
        val refName = getter?.name ?: ref.referenceName ?: refText.substringAfterLast("::")
        val property = getterNameToProperty(refName)

        val call = directEnclosingCall(ref)
        val base = RefRecord(
            file, line, refText, scenario, enclosing?.name,
            RefTier.NO_CONTEXT, null, null, null, null,
            getterResolved = false, entitySource = null, failureReason = null,
            callName = call?.methodExpression?.referenceName,
            usage = MybatisCalls.usageOf(ref, call),
        )

        if (call == null) {
            // 无调用语境：仍尝试用限定符反推（如 SFunction 存进变量的场景）
            return qualifierFallback(ref, base, index, getterResolved, null)
                ?: base.copy(failureReason = "方法引用不是调用的直接实参，且限定符反推失败")
        }

        // ---- 外围调用解析：advancedResolve 为空时回退 multiResolve(true) 取最优候选 ----
        val resolveResult = call.methodExpression.advancedResolve(false)
        var targetMethod = resolveResult.element as? PsiMethod
        var substitutor = resolveResult.substitutor
        if (targetMethod == null) {
            val best = pickBestCandidate(call)
            targetMethod = best?.first
            substitutor = best?.second ?: com.intellij.psi.PsiSubstitutor.EMPTY
        }
        if (targetMethod == null) {
            // 调用完全无法解析：限定符兜底，档位 R
            return qualifierFallback(ref, base, index, getterResolved, null)
                ?: base.copy(failureReason = "外围调用 ${callText(call)} 无法 resolve，且限定符反推失败")
        }

        val argIndex = call.argumentList.expressions.indexOf(ref)
        val params = targetMethod.parameterList.parameters
        val param = params.getOrNull(argIndex) ?: params.lastOrNull()?.takeIf { it.isVarArgs }
        if (param == null) {
            return qualifierFallback(ref, base, index, getterResolved, null)
                ?: base.copy(failureReason = "外围调用 ${callText(call)} 无对应形参")
        }

        var paramType = substitutor.substitute(param.type)
        if (paramType is PsiArrayType && argIndex >= params.size) {
            paramType = paramType.componentType // varargs 展开位
        }

        val samClass = (paramType as? PsiClassType)?.resolve()
        if (samClass != null && samClass.qualifiedName != SFUNCTION_FQN) {
            // 非 SFunction（Stream.map / Comparator.comparing / CollUtil.join 等）：不进 Phase 0 分母，
            // 但列引用本身照样能从限定符解析出来，Service 层组装的证据必须用得上，所以这里把表列填上。
            // tier 强制保持 NON_SFUNCTION，保证 Phase 0 的分档统计口径不变。
            val nonS = base.copy(tier = RefTier.NON_SFUNCTION)
            val filled = qualifierFallback(ref, nonS, index, getterResolved, RefTier.NON_SFUNCTION)
            return filled?.copy(tier = RefTier.NON_SFUNCTION)
                ?: nonS.copy(failureReason = "函数式接口为 ${samClass.qualifiedName}，非 SFunction")
        }

        // ---- SAM 类型实参 → 实体；退化（类型变量/Object）则限定符兜底 ----
        val tierFromParam = if (typeArgOwnerIsMethod(param.type)) RefTier.B else RefTier.A
        val typeArgs = (paramType as? PsiClassType)?.parameters
        val entityArg = typeArgs?.firstOrNull()
        val samEntity = if (entityArg != null && !containsTypeVariable(entityArg)) {
            (entityArg as? PsiClassType)?.resolve()?.takeIf { it.qualifiedName != "java.lang.Object" }
        } else null

        if (samEntity != null) {
            val entity = index.find(samEntity)
            if (entity == null) {
                return base.copy(
                    tier = RefTier.D_NON_ENTITY, entityFqn = samEntity.qualifiedName,
                    failureReason = "${samEntity.name} 无 @TableName，非实体引用（VO/BO）",
                )
            }
            return finish(refName, property, entity, samEntity.qualifiedName ?: samEntity.name ?: "?", tierFromParam, "SAM", getterResolved, base)
        }

        // SAM 退化（类型变量未替换 / 推断塌缩为 Object）：从方法引用自身限定符反推
        return qualifierFallback(ref, base, index, getterResolved, tierFromParam)
            ?: base.copy(
                tier = RefTier.C_UNRESOLVED_GENERIC,
                failureReason = "SFunction 类型实参退化（${entityArg?.canonicalText ?: "缺失"}）且限定符反推失败——定义处语境",
            )
    }

    /** 限定符兜底：`Type::getter` 取 qualifierType，`expr::getter` 取 qualifierExpression 的类型 */
    private fun qualifierFallback(
        ref: PsiMethodReferenceExpression,
        base: RefRecord,
        index: EntityIndex,
        getterResolved: Boolean,
        knownTier: RefTier?,
    ): RefRecord? {
        val qualifierClass = resolveQualifierClass(ref) ?: return null
        if (qualifierClass.qualifiedName == "java.lang.Object") return null
        val getter = try { ref.resolve() as? PsiMethod } catch (e: Exception) { null }
        val refName = getter?.name ?: ref.referenceName ?: shortText(ref).substringAfterLast("::")
        val property = getterNameToProperty(refName) ?: return null
        val resolved = getterResolved || getter != null
        val entity = index.find(qualifierClass)
        return if (entity == null) {
            base.copy(
                tier = RefTier.D_NON_ENTITY, entityFqn = qualifierClass.qualifiedName,
                entitySource = "QUALIFIER",
                failureReason = "${qualifierClass.name} 无 @TableName，非实体引用（VO/BO）",
            )
        } else {
            finish(
                refName, property, entity, qualifierClass.qualifiedName ?: qualifierClass.name ?: "?",
                knownTier ?: RefTier.R_QUALIFIER_ONLY, "QUALIFIER", resolved, base,
            )
        }
    }

    private fun finish(
        refName: String,
        property: String?,
        entity: EntityInfo,
        entityFqn: String,
        tier: RefTier,
        entitySource: String,
        getterResolved: Boolean,
        base: RefRecord,
    ): RefRecord {
        if (property == null) {
            return base.copy(
                tier = tier, entityFqn = entityFqn, getterResolved = getterResolved,
                entitySource = entitySource, failureReason = "引用名 $refName 不是 getter 形状",
            )
        }
        val colInfo = entity.columns[property]
        if (colInfo == null) {
            val reason = if (!getterResolved) {
                "getter $refName 未 resolve（Lombok light method 缺失？）且实体 ${entity.tableName} 无属性 $property"
            } else {
                "实体 ${entity.tableName}（$entityFqn）索引中无属性 $property"
            }
            return base.copy(
                tier = tier, entityFqn = entityFqn, property = property,
                table = entity.tableName, getterResolved = getterResolved,
                entitySource = entitySource, failureReason = reason,
            )
        }
        return base.copy(
            tier = tier, entityFqn = entityFqn, property = property,
            table = entity.tableName, column = colInfo.column, getterResolved = getterResolved,
            entitySource = entitySource, failureReason = null,
        )
    }

    /**
     * 取方法引用的限定符类。262 PSI 实测：`TradeOrderDO::getUserId` 的 qualifierType 为 null，
     * 限定符落在 qualifierExpression 上且是解析到类的 PsiReferenceExpression（.type 为 null）；
     * `user::getId` 这类表达式限定符才有 .type。三条路依次兜底。
     */
    private fun resolveQualifierClass(ref: PsiMethodReferenceExpression): PsiClass? {
        (ref.qualifierType?.type as? PsiClassType)?.resolve()?.let { return it }
        val qe = ref.qualifierExpression ?: return null
        ((qe as? com.intellij.psi.PsiReferenceExpression)?.resolve() as? PsiClass)?.let { return it }
        return (qe.type as? PsiClassType)?.resolve()
    }

    /**
     * advancedResolve 拿不到唯一结果时的候选优选：
     * 1) 优先非编译产物（项目源码里的 override，比如 MPJLambdaWrapperX 对 jar 方法的重写）
     * 2) 其次取最派生（containingClass 是其它候选的子类）
     */
    private fun pickBestCandidate(call: PsiMethodCallExpression): Pair<PsiMethod, com.intellij.psi.PsiSubstitutor>? {
        val candidates = call.methodExpression.multiResolve(true)
            .mapNotNull { r -> (r.element as? PsiMethod)?.let { it to r.substitutor } }
        if (candidates.isEmpty()) return null
        val source = candidates.filter { it.first.containingFile !is com.intellij.psi.PsiCompiledFile }
        val pool = source.ifEmpty { candidates }
        val mostDerived = pool.firstOrNull { (m, _) ->
            val cls = m.containingClass ?: return@firstOrNull false
            pool.all { (other, _) ->
                val ocls = other.containingClass ?: return@all true
                cls == ocls || cls.isInheritor(ocls, true)
            }
        }
        return mostDerived ?: pool.firstOrNull()
    }

    fun getterNameToProperty(name: String): String? = when {
        name.startsWith("get") && name.length > 3 -> decapitalize(name.substring(3))
        name.startsWith("is") && name.length > 2 -> decapitalize(name.substring(2))
        else -> null
    }

    private fun decapitalize(s: String): String =
        if (s.length >= 2 && s[0].isUpperCase() && s[1].isUpperCase()) s
        else s.replaceFirstChar { it.lowercaseChar() }

    /** ref 的直接外围调用：ref 必须是 argumentList 的直接子节点 */
    private fun directEnclosingCall(ref: PsiMethodReferenceExpression): PsiMethodCallExpression? {
        val argList = ref.parent as? PsiExpressionList ?: return null
        return argList.parent as? PsiMethodCallExpression
    }

    private fun typeArgOwnerIsMethod(originalParamType: PsiType): Boolean {
        val classType = originalParamType as? PsiClassType ?: return false
        val typeVar = classType.parameters.firstOrNull()?.let { asTypeVariable(it) } ?: return false
        return typeVar.owner is PsiMethod
    }

    /** 平台 PSI 里类型变量没有独立类型类：就是 resolve 到 PsiTypeParameter 的 PsiClassType */
    private fun asTypeVariable(type: PsiType): PsiTypeParameter? =
        (type as? PsiClassType)?.resolve() as? PsiTypeParameter

    private fun containsTypeVariable(type: PsiType): Boolean = when {
        asTypeVariable(type) != null -> true
        type is PsiWildcardType -> type.bound?.let { containsTypeVariable(it) } ?: false
        type is PsiClassType -> type.parameters.any { containsTypeVariable(it) }
        type is PsiArrayType -> containsTypeVariable(type.componentType)
        else -> false
    }

    private fun scenarioTag(enclosing: PsiMethod?): String? {
        val doc = enclosing?.docComment?.text ?: return null
        return Regex("\\[(S\\d+)]").find(doc)?.groupValues?.get(1)
    }

    private fun lineOf(ref: PsiMethodReferenceExpression): Int {
        val file = ref.containingFile ?: return -1
        val document = com.intellij.psi.PsiDocumentManager.getInstance(file.project).getDocument(file)
        return document?.getLineNumber(ref.textOffset)?.plus(1) ?: -1
    }

    private fun shortText(ref: PsiMethodReferenceExpression): String =
        ref.text.replace(Regex("\\s+"), "")

    private fun callText(call: PsiMethodCallExpression): String =
        call.methodExpression.referenceName ?: call.methodExpression.text

    /** 收集项目内全部方法引用（只扫 project scope，不含库） */
    fun collectReferences(project: com.intellij.openapi.project.Project): List<PsiMethodReferenceExpression> {
        val result = mutableListOf<PsiMethodReferenceExpression>()
        val psiManager = com.intellij.psi.PsiManager.getInstance(project)
        val files = com.intellij.psi.search.FilenameIndex.getAllFilesByExt(
            project, "java",
            com.intellij.psi.search.GlobalSearchScope.projectScope(project),
        )
        for (vf in files) {
            val psiFile = psiManager.findFile(vf) ?: continue
            if (psiFile !is com.intellij.psi.PsiJavaFile) continue
            PsiTreeUtil.findChildrenOfType(psiFile, PsiMethodReferenceExpression::class.java)
                .forEach { result.add(it) }
        }
        return result
    }

    /** 收集项目内全部 SFunction 类型的形参声明（C 档定义处统计，信息项不进分母） */
    fun collectSFunctionParameterDeclarations(project: com.intellij.openapi.project.Project): Int {
        var count = 0
        val psiManager = com.intellij.psi.PsiManager.getInstance(project)
        val files = com.intellij.psi.search.FilenameIndex.getAllFilesByExt(
            project, "java",
            com.intellij.psi.search.GlobalSearchScope.projectScope(project),
        )
        val javaFacade = com.intellij.psi.JavaPsiFacade.getInstance(project)
        val sfunction = javaFacade.findClass(
            SFUNCTION_FQN, com.intellij.psi.search.GlobalSearchScope.allScope(project),
        ) ?: return 0
        for (vf in files) {
            val psiFile = psiManager.findFile(vf) as? com.intellij.psi.PsiJavaFile ?: continue
            for (cls in PsiTreeUtil.findChildrenOfType(psiFile, PsiClass::class.java)) {
                for (method in cls.methods) {
                    for (p in method.parameterList.parameters) {
                        val t = (p.type as? PsiClassType)?.resolve() ?: continue
                        if (t == sfunction || t.isEquivalentTo(sfunction)) count++
                    }
                }
            }
        }
        return count
    }
}
