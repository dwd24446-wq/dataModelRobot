package org.dw.datamodelrobot.toolwindow

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.SimpleTextAttributes

/**
 * 把事实源里的 `文件名:行号` 翻成编辑器跳转。
 *
 * 事实源**只存文件名**（绝对路径随机器变就没法 diff），所以要靠 `FilenameIndex` 反查。
 * 同名文件多于一个时不猜，弹列表让人选；一个都没有就如实说找不到 ——
 * 悄悄跳到另一个同名文件比不跳更糟，因为看起来像是「工具确认了这条证据」。
 */
object EvidenceNavigator {

    /** 事实源的 `locations` 与 `conflicts[].refs` 都是 `文件名:行号` 形状 */
    fun parseLocation(ref: String): Pair<String, Int?> {
        val at = ref.lastIndexOf(':')
        if (at <= 0) return ref to null
        val line = ref.substring(at + 1).toIntOrNull() ?: return ref to null
        return ref.substring(0, at) to line
    }

    /** @return 给状态栏的失败原因；null = 已跳转（或已弹出选择框），不必提示 */
    fun navigate(project: Project, ref: String): String? {
        val (fileName, line) = parseLocation(ref)
        return navigate(project, fileName, line)
    }

    fun navigate(project: Project, fileName: String, line: Int?): String? {
        if (fileName.isBlank()) return "这条证据没有代码出处（schema 侧命名/键元组证据）"
        if (DumbService.isDumb(project)) return "索引未就绪，等 IDE 索引完再跳"
        // 用不带 Project 的重载：带 Project 的那几个在 262 已废弃（project 能从 scope 推出来）
        val found = ReadAction.computeBlocking<List<VirtualFile>, RuntimeException> {
            FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project))
                .filter { it.isValid }
                .sortedBy { it.path }
        }
        return when (found.size) {
            0 -> "项目里找不到 $fileName（可能来自未打开的模块，或产物已过期，重新扫一次）"
            1 -> {
                open(project, found[0], line)
                null
            }
            else -> {
                choose(project, found, line)
                null
            }
        }
    }

    private fun choose(project: Project, files: List<VirtualFile>, line: Int?) {
        // 相对路径比文件名长得多，弹窗选择器会截断又拖不宽 → 用可拖宽的对话框
        val dialog = ListChooserDialog(project, "多个同名文件，跳哪一个？", files) { file, renderer ->
            renderer.append(relative(project, file), SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
        if (dialog.showAndGet()) dialog.selected()?.let { open(project, it, line) }
    }

    private fun open(project: Project, file: VirtualFile, line: Int?) {
        // 事实源的 line 是 1-based（PSI 侧 getLineNumber()+1），OpenFileDescriptor 要 0-based
        val descriptor = if (line != null && line > 0) {
            OpenFileDescriptor(project, file, line - 1, 0)
        } else {
            OpenFileDescriptor(project, file)
        }
        descriptor.navigate(true)
    }

    private fun relative(project: Project, file: VirtualFile): String {
        val base = project.basePath ?: return file.path
        val path = file.path
        return if (path.startsWith(base)) path.substring(base.length).removePrefix("/") else path
    }
}
