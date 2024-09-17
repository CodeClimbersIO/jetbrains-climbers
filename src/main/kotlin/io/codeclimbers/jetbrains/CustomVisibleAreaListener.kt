package io.codeclimbers.jetbrains

import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener

class CustomVisibleAreaListener : VisibleAreaListener {
    override fun visibleAreaChanged(visibleAreaEvent: VisibleAreaEvent) {
        try {
            if (!didChange(visibleAreaEvent)) return
            if (!CodeClimbers.isAppActive()) return
            val document = visibleAreaEvent.editor.document
            val file = CodeClimbers.getFile(document) ?: return
            val project = visibleAreaEvent.editor.project ?: return
            if (!CodeClimbers.isProjectInitialized(project)) return
            val editor = visibleAreaEvent.editor
            val offset = editor.caretModel.offset
            val lineStats = CodeClimbers.getLineStats(document, offset)
            CodeClimbers.appendHeartbeat(file, project, false, lineStats)
        } catch (e: Exception) {
            CodeClimbers.debugException(e)
        }
    }

    private fun didChange(visibleAreaEvent: VisibleAreaEvent): Boolean {
        val oldRect = visibleAreaEvent.oldRectangle ?: return true
        val newRect = visibleAreaEvent.newRectangle ?: return false
        return newRect.x != oldRect.x || newRect.y != oldRect.y
    }
}
