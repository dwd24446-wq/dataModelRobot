package org.dw.datamodelrobot.phase0

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch

/**
 * MyBatis-Plus 全局配置里影响列名推导的两项。
 * 这类脚手架的真实项目（含 fixture）刻意不配这两项，走 MP 默认值：
 * columnUnderline=true（驼峰转下划线）、tablePrefix=""。
 */
data class MpDbConfig(val columnUnderline: Boolean = true, val tablePrefix: String = "")

data class ColumnInfo(val property: String, val column: String, val declaredIn: String)

data class EntityInfo(
    val qualifiedName: String,
    val tableName: String,
    /** key = Java 属性名（含继承链字段），value = 列信息 */
    val columns: Map<String, ColumnInfo>,
    /** 类 Javadoc 首行描述（原样，未清洗）。表注释缺失时的中文名来源 */
    val doc: String? = null,
) {
    /** Javadoc 里只写了块标签（`@author` 之类）等于没写 */
    val docText: String? get() = doc?.takeIf { it.isNotBlank() }
}

/**
 * 实体索引：@TableName 类 → 表名；继承链字段 → 列名。
 * Phase 1 会在此基础上加 schema 侧（DatabaseTools）信息，此处只管代码侧。
 */
class EntityIndex(
    val entitiesByFqn: Map<String, EntityInfo>,
    val config: MpDbConfig,
    val configSource: String,
) {
    /**
     * 表名（小写）→ 类 Javadoc 首行。**同表多个 DO 时按 fqn 取最小的**，
     * 否则产物里的中文名会随 PSI 遍历顺序漂移，事实源就没法进版本库做 diff。
     */
    val docsByTable: Map<String, String> by lazy {
        entitiesByFqn.values
            .filter { it.docText != null }
            .groupBy { it.tableName.lowercase() }
            .mapValues { (_, list) -> list.minBy { it.qualifiedName }.docText!! }
    }

    fun find(psiClass: PsiClass?): EntityInfo? =
        psiClass?.qualifiedName?.let { entitiesByFqn[it] }

    companion object {
        const val ANN_TABLE_NAME = "com.baomidou.mybatisplus.annotation.TableName"
        const val ANN_TABLE_FIELD = "com.baomidou.mybatisplus.annotation.TableField"
        const val ANN_TABLE_ID = "com.baomidou.mybatisplus.annotation.TableId"

        fun build(project: Project): EntityIndex {
            val config = MpConfigReader.read(project)
            val annotationClass = JavaPsiFacade.getInstance(project)
                .findClass(ANN_TABLE_NAME, GlobalSearchScope.allScope(project))
            val byFqn = LinkedHashMap<String, EntityInfo>()
            if (annotationClass != null) {
                val annotated = AnnotatedElementsSearch.searchPsiClasses(
                    annotationClass, GlobalSearchScope.projectScope(project),
                )
                // 不能直接 for-in Query：那会走 Query.iterator()，平台已标 ScheduledForRemoval
                // （iterator/asIterable/spliterator/forEach(Consumer) 一并废弃，只有 findAll 等聚合方法活着）
                for (psiClass in annotated.findAll()) {
                    val fqn = psiClass.qualifiedName ?: continue
                    if (byFqn.containsKey(fqn)) continue
                    byFqn[fqn] = buildEntity(psiClass, config)
                }
            }
            return EntityIndex(byFqn, config, MpConfigReader.describeSource(project))
        }

        private fun buildEntity(psiClass: PsiClass, config: MpDbConfig): EntityInfo {
            val tableName = annotationString(psiClass, ANN_TABLE_NAME)
                ?.takeIf { it.isNotEmpty() }
                ?: camelToUnderscore(psiClass.name ?: "")
            val columns = LinkedHashMap<String, ColumnInfo>()
            // 从最派生类向父类走，子类字段遮蔽父类同名字段
            var current: PsiClass? = psiClass
            while (current != null && current.qualifiedName != "java.lang.Object") {
                val declaring = current.name ?: "?"
                for (field in current.fields) {
                    if (field is PsiEnumConstant) continue
                    if (field.hasModifierProperty(PsiModifier.STATIC)) continue
                    if (isNonColumn(field)) continue
                    val property = field.name
                    if (columns.containsKey(property)) continue
                    val explicit = annotationString(current, field, ANN_TABLE_FIELD)
                        ?: annotationString(current, field, ANN_TABLE_ID)
                    val column = explicit?.takeIf { it.isNotEmpty() }
                        ?: if (config.columnUnderline) camelToUnderscore(property) else property
                    columns[property] = ColumnInfo(property, column, declaring)
                }
                current = (current.superClassType as? PsiClassType)?.resolve() ?: current.superClass
            }
            return EntityInfo(psiClass.qualifiedName ?: "?", config.tablePrefix + tableName, columns, classDoc(psiClass))
        }

        /**
         * 类 Javadoc 的**首段描述**（到第一个空行为止）：这类脚手架的 DO 一律写成「xxx DO / xxx 表」，
         * 中文名就在首段。原样返回（含 `{@link }`、`<p>` 这类杂质也留着）——事实源存代码原话，
         * 清洗只发生在画图时。
         */
        private fun classDoc(psiClass: PsiClass): String? {
            val raw = psiClass.docComment?.text ?: return null
            val paragraph = StringBuilder()
            for (line in raw.lines()) {
                val text = line.trim()
                    .removePrefix("/**").removePrefix("/*").removeSuffix("*/")
                    .trim().removePrefix("*").trim()
                if (paragraph.isEmpty()) {
                    if (text.isEmpty() || text.startsWith("@")) continue
                } else if (text.isEmpty() || text.startsWith("@")) {
                    break
                }
                paragraph.append(text).append(' ')
            }
            return paragraph.toString().trim().takeIf { it.isNotEmpty() }
        }

        private fun isNonColumn(field: PsiField): Boolean {
            if (field.hasModifierProperty(PsiModifier.TRANSIENT)) return true
            val tableField = field.getAnnotation(ANN_TABLE_FIELD) ?: return false
            val exist = tableField.findAttributeValue("exist")
            // exist 默认 true；显式写 false 才是非列字段
            return exist != null && exist.text == "false"
        }

        private fun annotationString(psiClass: PsiClass, fqn: String): String? =
            attrConstantString(psiClass.getAnnotation(fqn))

        private fun annotationString(cls: PsiClass, field: PsiField, fqn: String): String? {
            val ann = field.getAnnotation(fqn) ?: return null
            return attrConstantString(ann)
        }

        private fun attrConstantString(ann: PsiAnnotation?): String? {
            if (ann == null) return null
            val value = ann.findAttributeValue("value") ?: return null
            return (value as? PsiLiteralExpression)?.value as? String
                ?: value.text.removeSurrounding("\"")
        }

        fun camelToUnderscore(name: String): String {
            val sb = StringBuilder()
            for ((i, c) in name.withIndex()) {
                if (c.isUpperCase()) {
                    // 连续大写（如 getHTTPUrl → http_url）只在词首插下划线
                    val prevLower = i > 0 && name[i - 1].isLowerCase()
                    val nextLower = i + 1 < name.length && name[i + 1].isLowerCase()
                    if (i > 0 && (prevLower || nextLower)) sb.append('_')
                    sb.append(c.lowercaseChar())
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}

/**
 * 极简 application.yml 读取：只找 mybatis-plus.global-config.db-config 下的
 * column-underline / table-prefix 两个键。不引 snakeyaml（平台未保证导出），
 * 行级缩进扫描足够——这类脚手架这两项要么不配（走默认），要么就是简单键值。
 */
object MpConfigReader {
    fun read(project: Project): MpDbConfig {
        val yaml = findApplicationYamlText(project) ?: return MpDbConfig()
        var columnUnderline = true
        var tablePrefix = ""
        var inDbConfig = false
        var dbConfigIndent = -1
        for (rawLine in yaml.lines()) {
            val line = rawLine.trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val indent = line.length - line.trimStart().length
            if (inDbConfig && indent <= dbConfigIndent) inDbConfig = false
            val trimmed = line.trim()
            if (!inDbConfig) {
                if (trimmed.startsWith("db-config:")) {
                    inDbConfig = true
                    dbConfigIndent = indent
                }
                continue
            }
            when {
                trimmed.startsWith("column-underline:") ->
                    columnUnderline = trimmed.substringAfter(":").trim().toBooleanStrictOrNull() ?: true
                trimmed.startsWith("table-prefix:") ->
                    tablePrefix = trimmed.substringAfter(":").trim().removeSurrounding("\"")
            }
        }
        return MpDbConfig(columnUnderline, tablePrefix)
    }

    fun describeSource(project: Project): String =
        findApplicationYamlName(project) ?: "未找到 application.yml，使用 MP 默认配置"

    private fun findApplicationYamlText(project: Project): String? =
        findApplicationYaml(project)?.text

    private fun findApplicationYamlName(project: Project): String? =
        findApplicationYaml(project)?.virtualFile?.path

    private fun findApplicationYaml(project: Project): PsiFile? {
        val scope = GlobalSearchScope.projectScope(project)
        for (name in listOf("application.yaml", "application.yml")) {
            // 带 Project 形参的 FilenameIndex.getFilesByName 已废弃，官方路径是先取 VirtualFile 再交给 PsiManager
            for (vf in FilenameIndex.getVirtualFilesByName(name, scope)) {
                val psi = PsiManager.getInstance(project).findFile(vf) ?: continue
                return psi
            }
        }
        return null
    }
}
