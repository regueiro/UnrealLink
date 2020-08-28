package com.jetbrains.rider.plugins.unreal.toolWindow

import com.intellij.execution.ui.ConsoleViewContentType.*
import com.intellij.find.SearchReplaceComponent
import com.intellij.ide.impl.ContentManagerWatcher
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.impl.FoldingModelImpl
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.DocumentUtil
import com.jetbrains.rd.framework.impl.startAndAdviseSuccess
import com.jetbrains.rd.platform.util.lifetime
import com.jetbrains.rd.util.eol
import com.jetbrains.rdclient.daemon.util.attributeKey
import com.jetbrains.rider.model.*
import com.jetbrains.rider.plugins.unreal.UnrealPane
import com.jetbrains.rider.plugins.unreal.actions.FilterCheckboxAction
import com.jetbrains.rider.plugins.unreal.filters.linkInfo.BlueprintClassHyperLinkInfo
import com.jetbrains.rider.plugins.unreal.filters.linkInfo.MethodReferenceHyperLinkInfo
import com.jetbrains.rider.plugins.unreal.filters.linkInfo.UnrealClassHyperLinkInfo
import com.jetbrains.rider.projectView.solution
import com.jetbrains.rider.ui.toolWindow.RiderOnDemandToolWindowFactory
import icons.RiderIcons

class UnrealToolWindowFactory(val project: Project)
    : RiderOnDemandToolWindowFactory<String>(project, TOOLWINDOW_ID, { it }, ::UnrealPane, { it }) {

    companion object {
        const val TOOLWINDOW_ID = "Unreal"
        const val TITLE_ID = "Unreal Editor Log"
        const val ACTION_PLACE = "unreal"

        fun getInstance(project: Project): UnrealToolWindowFactory = project.service()
    }

    var allCetegoriesSelected: Boolean = true
    var rangeHighlighters: ArrayList<RangeHighlighter> = arrayListOf()

    override fun registerToolWindow(toolWindowManager: ToolWindowManager, project: Project): ToolWindow {
        val toolWindow = toolWindowManager.registerToolWindow(TOOLWINDOW_ID, false, ToolWindowAnchor.BOTTOM, project, true, false)

        ContentManagerWatcher.watchContentManager(toolWindow, toolWindow.contentManager)

        toolWindow.title = "unreal"
        toolWindow.setIcon(RiderIcons.Stacktrace.Stacktrace) //todo change

        return toolWindow
    }

    fun onUnrealPaneCreated() {
        UnrealPane.categoryFilterActionGroup.addItemListener {
            val selected = UnrealPane.categoryFilterActionGroup.selected()
            if (allCetegoriesSelected) {
                if ("All" !in selected) {
                    UnrealPane.categoryFilterActionGroup.selectAll(false)
                    allCetegoriesSelected = false
                } else if (selected.size != UnrealPane.categoryFilterActionGroup.items().size) {
                    allCetegoriesSelected = false
                    for (item in UnrealPane.categoryFilterActionGroup.items()) {
                        if (item.getName() == "All") {
                            item.setSelected(false)
                            break;
                        }
                    }
                }
            } else {
                if ("All" in selected) {
                    UnrealPane.categoryFilterActionGroup.selectAll(true)
                    allCetegoriesSelected = true
                } else if (selected.size == UnrealPane.categoryFilterActionGroup.items().size - 1) {
                    allCetegoriesSelected = true
                    for (item in UnrealPane.categoryFilterActionGroup.items()) {
                        if (item.getName() == "All") {
                            item.setSelected(true)
                            break;
                        }
                    }
                }
            }
            filter()
        }

        UnrealPane.verbosityFilterActionGroup.addItemListener {
            filter()
        }

        UnrealPane.timestampCheckBox.addChangeListener {
            filter()
        }

        UnrealPane.filter.addListener(object : SearchReplaceComponent.Listener {
            override fun searchFieldDocumentChanged() {
                filter()
            }

            override fun replaceFieldDocumentChanged() {
            }

            override fun multilineStateChanged() {
            }

        })
    }

    private fun isMatchingVerbosity(valueToCheck: VerbosityType, currentList: List<String>): Boolean {
        if (currentList.isEmpty()) {
            return false
        }

        if (valueToCheck.compareTo(VerbosityType.Error) <= 0)
            return "Errors" in currentList
        if (valueToCheck == VerbosityType.Warning)
            return "Warnings" in currentList

        return "Messages" in currentList
    }

    private fun isMatchingVerbosity(valueToCheck: String, currentList: List<String>): Boolean {
        if (currentList.isEmpty()) {
            return false
        }

        val value = VerbosityType.valueOf(valueToCheck)
        if (value.compareTo(VerbosityType.Error) <= 0)
            return "Errors" in currentList
        if (value == VerbosityType.Warning)
            return "Warnings" in currentList

        return "Messages" in currentList
    }

    private fun filter() {
        val foldingModel = UnrealPane.currentConsoleView.editor.foldingModel as FoldingModelImpl
        foldingModel.runBatchFoldingOperation {
            for (region in foldingModel.allFoldRegions) {
                foldingModel.removeFoldRegion(region)
            }
            val markupModel = DocumentMarkupModel.forDocument(UnrealPane.currentConsoleView.editor.document, project, false)
            for (highlighter in rangeHighlighters) {
                markupModel.removeHighlighter(highlighter)
            }
            rangeHighlighters.clear()

            val selectedCategories = UnrealPane.categoryFilterActionGroup.selected()
            val selectedVerbosities = UnrealPane.verbosityFilterActionGroup.selected()
            val filterText = UnrealPane.filter.searchTextComponent.text
            if ("All" in selectedCategories && selectedVerbosities.size == 3 && UnrealPane.timestampCheckBox.isSelected && filterText.isEmpty()) {
                return@runBatchFoldingOperation
            }
            val doc = UnrealPane.currentConsoleView.editor.document
            val text = UnrealPane.currentConsoleView.editor.document.text
            var index = 0
            var lastOffset = 0

            val showTimestamp = UnrealPane.timestampCheckBox.isSelected

            while (index < text.length) {
                val lineEndOffset = DocumentUtil.getLineEndOffset(index, doc)
                val verbosity = text.substring(index + TIME_WIDTH + 1, index + TIME_WIDTH + VERBOSITY_WIDTH + 1)
                val category = text.substring(index + TIME_WIDTH + VERBOSITY_WIDTH + 2, index + TIME_WIDTH + VERBOSITY_WIDTH + CATEGORY_WIDTH + 2)

                if (!text.substring(index, lineEndOffset).contains(filterText)) {
                    index = lineEndOffset + 1
                    continue
                }
                if (category.trim() !in selectedCategories) {
                    index = lineEndOffset + 1
                    continue
                }
                if (!isMatchingVerbosity(verbosity.trim(), selectedVerbosities)) {
                    index = lineEndOffset + 1
                    continue
                }
                if (!showTimestamp) {
                    foldingModel.createFoldRegion(lastOffset, index + TIME_WIDTH + 1, "", null, true)
                } else if (lastOffset != index) {
                    foldingModel.createFoldRegion(lastOffset, index, "", null, true)
                }
                if (filterText.isNotEmpty()) {
                    val line = UnrealPane.currentConsoleView.text.substring(lastOffset, lineEndOffset)
                    var filterOffset = line.indexOf(filterText)
                    while (filterOffset != -1) {
                        rangeHighlighters.add(markupModel.addRangeHighlighter(EditorColors.TEXT_SEARCH_RESULT_ATTRIBUTES, lastOffset + filterOffset,
                                lastOffset + filterOffset + filterText.length, HighlighterLayer.ELEMENT_UNDER_CARET,
                                HighlighterTargetArea.EXACT_RANGE))
                        filterOffset = line.indexOf(filterText, filterOffset + filterText.length)
                    }
                }

                lastOffset = lineEndOffset + 1
                index = lastOffset
            }
            if (lastOffset < text.length) {
                val region = foldingModel.createFoldRegion(lastOffset, text.length, "", null, true)
                region!!.isExpanded = false
            }
        }
    }

    private fun printSpaces(n: Int = 1) {
        UnrealPane.currentConsoleView.print(" ".repeat(n), NORMAL_OUTPUT)

    }

    fun print(s: LogMessageInfo) {
        val consoleView = UnrealPane.currentConsoleView
        var timeString = s.time?.toString() ?: " ".repeat(TIME_WIDTH)
        if (timeString.length < TIME_WIDTH)
            timeString += " ".repeat(TIME_WIDTH - timeString.length)
        consoleView.print(timeString, SYSTEM_OUTPUT)
        printSpaces()

        val verbosityContentType = when (s.type) {
            VerbosityType.Fatal -> ERROR_OUTPUT
            VerbosityType.Error -> ERROR_OUTPUT
            VerbosityType.Warning -> LOG_WARNING_OUTPUT
            VerbosityType.Display -> LOG_INFO_OUTPUT
            VerbosityType.Log -> LOG_INFO_OUTPUT
            VerbosityType.Verbose -> LOG_VERBOSE_OUTPUT
            VerbosityType.VeryVerbose -> LOG_DEBUG_OUTPUT
            else -> NORMAL_OUTPUT
        }

        val verbosityString = s.type.toString().take(VERBOSITY_WIDTH)
        consoleView.print(verbosityString, verbosityContentType)
        printSpaces(VERBOSITY_WIDTH - verbosityString.length + 1)

        val category = s.category.data.take(CATEGORY_WIDTH)
        var exists: Boolean = false
        var allSelected: Boolean = true
        for (item in UnrealPane.categoryFilterActionGroup.items()) {
            if (item.getName() == "All") {
                allSelected = item.isSelected()
            }
            if (item.getName() == category) {
                exists = true
                break;
            }
        }
        if (!exists) {
            UnrealPane.categoryFilterActionGroup.addItem(FilterCheckboxAction(category, allSelected))
        }

        consoleView.print(category, SYSTEM_OUTPUT)
        printSpaces(CATEGORY_WIDTH - category.length + 1)
    }

    internal val model = project.solution.rdRiderModel
    private val stackTraceContentType = LOG_ERROR_OUTPUT

    private fun print(message: FString) {
        with(UnrealPane.currentConsoleView) {
            print(message.data, NORMAL_OUTPUT)
        }
    }

/*
    private fun print(scriptMsg: IScriptMsg) {
        with(UnrealPane.publicConsoleView) {
            print(IScriptMsg.header, NORMAL_OUTPUT)
            println()
            when (scriptMsg) {
                is ScriptMsgException -> {
                    print(scriptMsg.message)
                }
                is ScriptMsgCallStack -> {
                    print(scriptMsg.message)
                    println()
                    print(scriptMsg.scriptCallStack)
                }
            }

        }
    }
*/

    fun print(unrealLogEvent: UnrealLogEvent) {
        val consoleView = UnrealPane.currentConsoleView
        print(unrealLogEvent.info)
        print(unrealLogEvent.text)

        consoleView.flushDeferredText()
        val startOffset = consoleView.contentSize - unrealLogEvent.text.data.length
        var startOfLineOffset = startOffset - (TIME_WIDTH + VERBOSITY_WIDTH + CATEGORY_WIDTH + 3)
        if (unrealLogEvent.bpPathRanges.isNotEmpty() || unrealLogEvent.methodRanges.isNotEmpty()) {
            for (range in unrealLogEvent.bpPathRanges) {
                val match = unrealLogEvent.text.data.substring(range.first, range.last)
                val hyperLinkInfo = BlueprintClassHyperLinkInfo(model.openBlueprint, BlueprintReference(FString(match)))
                consoleView.hyperlinks.createHyperlink(startOffset + range.first, startOffset + range.last, null, hyperLinkInfo)
            }
            for (range in unrealLogEvent.methodRanges) {
                val match = unrealLogEvent.text.data.substring(range.first, range.last)
                val (`class`, method) = match.split(MethodReference.separator)
                val methodReference = MethodReference(UClass(FString(`class`)), FString(method))
                model.isMethodReference.startAndAdviseSuccess(methodReference) {
                    if (it) {
                        run {
                            val first = startOffset + range.first
                            val last = startOffset + range.first + `class`.length
                            val linkInfo = UnrealClassHyperLinkInfo(model.navigateToClass, UClass(FString(`class`)))
                            consoleView.hyperlinks.createHyperlink(first, last, null, linkInfo)
                        }
                        run {
                            val linkInfo = MethodReferenceHyperLinkInfo(model.navigateToMethod, methodReference)
                            val first = startOffset + range.last - method.length
                            val last = startOffset + range.last
                            consoleView.hyperlinks.createHyperlink(first, last, null, linkInfo)
                        }
                    }
                }
            }
        }

        val foldingModel = UnrealPane.currentConsoleView.editor.foldingModel as FoldingModelImpl
        var existingRegion = foldingModel.getCollapsedRegionAtOffset(startOfLineOffset - 1)
        if (existingRegion == null)
            existingRegion = foldingModel.getCollapsedRegionAtOffset(startOfLineOffset - 2)

        val selectedCategories = UnrealPane.categoryFilterActionGroup.selected()
        val selectedVerbosities = UnrealPane.verbosityFilterActionGroup.selected()
        val filterText = UnrealPane.filter.searchTextComponent.text
        val showTimestamp = UnrealPane.timestampCheckBox.isSelected

        val filterTextMatches = filterText.isEmpty() ||
                consoleView.text.substring(startOfLineOffset, consoleView.contentSize).contains(filterText)

        if (!isMatchingVerbosity(unrealLogEvent.info.type, selectedVerbosities) ||
                unrealLogEvent.info.category.data !in selectedCategories ||
                !filterTextMatches) {
            foldingModel.runBatchFoldingOperation {
                if (existingRegion != null) {
                    startOfLineOffset = existingRegion.startOffset
                    foldingModel.removeFoldRegion(existingRegion)
                }
                foldingModel.createFoldRegion(startOfLineOffset, consoleView.contentSize, "", null, true)
            }
        }
        else {
            // expand region to cover the whole line with EOL
            foldingModel.runBatchFoldingOperation {
                var start = startOfLineOffset
                if (existingRegion != null) {
                    start = existingRegion.startOffset
                    foldingModel.removeFoldRegion(existingRegion)
                }
                if (showTimestamp) {
                    if (start != startOfLineOffset)
                        foldingModel.createFoldRegion(start, startOfLineOffset, "", null, true)
                } else {
                    foldingModel.createFoldRegion(start, startOfLineOffset + TIME_WIDTH + 1, "", null, true)
                }

                if (filterText.isNotEmpty()) {
                    val markupModel = DocumentMarkupModel.forDocument(consoleView.editor.document, project, false)
                    val line = consoleView.text.substring(startOfLineOffset, consoleView.contentSize)
                    var filterOffset = line.indexOf(filterText)
                    while (filterOffset != -1) {
                        rangeHighlighters.add(markupModel.addRangeHighlighter(EditorColors.TEXT_SEARCH_RESULT_ATTRIBUTES,
                                startOfLineOffset + filterOffset, startOfLineOffset + filterOffset + filterText.length,
                                HighlighterLayer.ELEMENT_UNDER_CARET, HighlighterTargetArea.EXACT_RANGE))
                        filterOffset = line.indexOf(filterText, filterOffset + filterText.length)
                    }
                }
            }
        }
    }

    fun showTabForNewSession() {
        showTab("$TITLE_ID", project.lifetime)
    }

    private fun println() {
        with(UnrealPane.currentConsoleView) {
            print(eol, NORMAL_OUTPUT)
        }
    }

    fun flush() {
        println()
//        UnrealPane.publicConsoleView.flushDeferredText()
    }
}
