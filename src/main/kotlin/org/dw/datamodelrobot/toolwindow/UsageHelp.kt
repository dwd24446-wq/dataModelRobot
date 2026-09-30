package org.dw.datamodelrobot.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.ui.components.JBScrollPane
import java.awt.Dimension
import javax.swing.JEditorPane

/**
 * 工具窗口上的「使用方法」弹窗：功能介绍 + 操作步骤（配数据源、扫描、装 MCP 与 Skill）+ 置信度读法 + 排错。
 *
 * 两条实现取舍：
 * - **正文放 resources 而不是 Kotlin 字符串常量**：内容长、跟着功能走，且能 headless 测
 *   （弹窗本身要 Swing 测不到，但资源能否加载、章节齐不齐可以）
 * - **`DialogBuilder` + `JEditorPane` 而不是 `JBCefBrowser`**：本项目有意不依赖 JCEF（USAGE §3.2 取舍 2），
 *   也不为一个帮助页引入 5MB 的渲染栈。代价是 Swing 的 HTML 渲染器只吃 HTML 3.2 + 部分 CSS，
 *   正文里**不要用 flex / grid / 现代 CSS**，表格与列表最稳
 */
object UsageHelp {

    private const val RESOURCE = "/help/usage.html"

    const val TITLE = "dataModelRobot 使用方法"

    /** 帮助正文（UTF-8）。资源缺失直接抛：那是打包出错，静默弹一个空白框比抛异常难查得多 */
    fun html(): String = UsageHelp::class.java.getResourceAsStream(RESOURCE)
        ?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?: error("插件资源缺失：$RESOURCE")

    fun show(project: Project) {
        val pane = JEditorPane().apply {
            // 顺序要紧：先声明跟随 IDE 字体，再设 contentType（它会换掉 EditorKit 与 Document），最后灌正文
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            isEditable = false
            contentType = "text/html"
            text = html()
            caretPosition = 0
        }
        DialogBuilder(project).apply {
            setTitle(TITLE)
            setCenterPanel(JBScrollPane(pane).apply { preferredSize = Dimension(780, 620) })
            // DialogBuilder 一旦有了显式 action 就不再补默认的 OK/Cancel，所以这里只会出现一个「关闭」
            addCloseButton()
        }.show()
    }
}
