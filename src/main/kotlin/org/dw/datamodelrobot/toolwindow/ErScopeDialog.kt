package org.dw.datamodelrobot.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import org.dw.datamodelrobot.output.ErDiagram
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.ButtonGroup
import javax.swing.DefaultListModel
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.Timer
import javax.swing.event.DocumentEvent

/** 图预览旁的非模态选择器。所有选择只影响 ad-hoc 图，不修改持久化业务范围。 */
internal class ErScopeDialog(
    project: Project,
    title: String,
    private val tables: List<ErDiagram.Table>,
    initialTables: Collection<String>,
    private val center: String?,
    private val onChange: (Set<String>, Double, ErDiagram.Direction) -> Unit,
) : DialogWrapper(project, false) {

    private val selected = initialTables.map { it.lowercase() }.toMutableSet()
    private val maxTables = if (center == null) ErDiagram.MAX_SCOPE_TABLES else ErDiagram.MAX_NEIGHBORS + 1
    private val listModel = DefaultListModel<ErDiagram.Table>()
    private val list = JBList(listModel)
    private val search = SearchTextField(false)
    private val count = JBLabel()
    private val threshold = JSpinner(SpinnerNumberModel(0.0, 0.0, 1.0, 0.05))
    private var direction = ErDiagram.Direction.ALL
    private val refreshTimer = Timer(300) { onChange(selected.toSet(), threshold.value as Double, direction) }
        .apply { isRepeats = false }

    init {
        this.title = title
        setModal(false)
        setOKButtonText("关闭")
        center?.let { selected += it.lowercase() }
        init()
    }

    override fun createCenterPanel(): JComponent {
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refreshList()
        })
        list.cellRenderer = javax.swing.ListCellRenderer { _: JList<out ErDiagram.Table>, table, _, isSelected, _ ->
            JCheckBox(table.name + (table.label?.let { "  $it" } ?: ""), table.name.lowercase() in selected).apply {
                isOpaque = true
                background = if (isSelected) list.selectionBackground else list.background
                foreground = if (isSelected) list.selectionForeground else list.foreground
                isEnabled = !table.name.equals(center, true)
            }
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = list.locationToIndex(e.point)
                if (index >= 0 && list.getCellBounds(index, index)?.contains(e.point) == true) toggle(listModel[index])
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_SPACE) list.selectedValue?.let(::toggle)
            }
        })
        threshold.addChangeListener { refreshTimer.restart() }
        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(JBLabel("最低置信度"))
            add(threshold)
            if (center != null) {
                val group = ButtonGroup()
                for ((label, value) in listOf(
                    "全部" to ErDiagram.Direction.ALL,
                    "出边" to ErDiagram.Direction.OUTGOING,
                    "入边" to ErDiagram.Direction.INCOMING,
                )) {
                    val button = JRadioButton(label, value == direction)
                    group.add(button)
                    add(button)
                    button.addActionListener { direction = value; refreshTimer.restart() }
                }
            }
        }
        refreshList()
        return JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(560, 540)
            add(search, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                add(controls, BorderLayout.NORTH)
                add(count, BorderLayout.SOUTH)
            }, BorderLayout.SOUTH)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = search

    override fun dispose() {
        if (refreshTimer.isRunning) onChange(selected.toSet(), threshold.value as Double, direction)
        refreshTimer.stop()
        super.dispose()
    }

    private fun refreshList() {
        val query = search.text.trim().lowercase()
        listModel.clear()
        tables.filter { query.isEmpty() || it.name.lowercase().contains(query) || it.label?.lowercase()?.contains(query) == true }
            .sortedBy { it.name.lowercase() }.forEach(listModel::addElement)
        count.text = "已选 ${selected.size} / $maxTables 张表"
        list.repaint()
    }

    private fun toggle(table: ErDiagram.Table) {
        val name = table.name.lowercase()
        if (name == center?.lowercase()) return
        if (name in selected) {
            selected -= name
        } else {
            if (selected.size >= maxTables) {
                count.text = "最多 $maxTables 张表，请先取消一张"
                return
            }
            selected += name
        }
        count.text = "已选 ${selected.size} / $maxTables 张表"
        list.repaint()
        refreshTimer.restart()
    }
}
