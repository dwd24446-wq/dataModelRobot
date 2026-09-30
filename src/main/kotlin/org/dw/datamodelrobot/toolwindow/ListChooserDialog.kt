package org.dw.datamodelrobot.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JList

/**
 * 单选列表对话框：双击某项、或选中后点「打开」即返回。
 *
 * 为什么不用 `JBPopupFactory.createPopupChooserBuilder`：**`JBPopup` 没有 `setResizable`**
 * （262 javap 核实过，只有 `setSize` / `setMinimumSize`），所以弹窗一旦窄了就**既截断文本又拖不宽**。
 * 中文标签（`_overview（总览：全库表与关系）`）与相对路径这两类内容尤其容易撞上。
 * 对话框能拖，宽度也不依赖 preferredSize 算得准不准。
 *
 * [render] 让调用方往渲染器里分段 append（可各自配色），不必各自 subclass `ColoredListCellRenderer`。
 * [okText] 是因为两处调用的语义不同：选关系图是「打开」，选业务范围是「加入」—— 按钮文案说错动作，
 * 用户就得先猜一下点了会发生什么。
 */
internal class ListChooserDialog<T>(
    project: Project,
    title: String,
    items: List<T>,
    okText: String = "打开",
    private val render: (T, ColoredListCellRenderer<T>) -> Unit,
) : DialogWrapper(project, false) {

    private val list = JBList(items).apply { selectedIndex = 0 }

    init {
        this.title = title
        setOKButtonText(okText)
        init()
    }

    override fun createCenterPanel(): JComponent {
        list.cellRenderer = object : ColoredListCellRenderer<T>() {
            override fun customizeCellRenderer(l: JList<out T>, value: T, index: Int, selected: Boolean, hasFocus: Boolean) {
                render(value, this)
            }
        }
        // 双击直接打开，与树里「双击证据跳代码」的交互一致
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && list.selectedValue != null) close(OK_EXIT_CODE)
            }
        })
        return JBScrollPane(list).apply { preferredSize = Dimension(560, 380) }
    }

    override fun getPreferredFocusedComponent(): JComponent = list

    /** 只在 [showAndGet] 返回 true 之后调用 */
    fun selected(): T? = list.selectedValue
}
