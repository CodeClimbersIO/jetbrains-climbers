package io.codeclimbers.jetbrains

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener

class CustomEditorMouseListener : EditorMouseListener {
    override fun mousePressed(editorMouseEvent: EditorMouseEvent) {
        try {
            if (!CodeClimbers.isAppActive()) return
            val document = editorMouseEvent.editor.document
            val file = CodeClimbers.getFile(document) ?: return
            val project = editorMouseEvent.editor.project ?: return
            if (!CodeClimbers.isProjectInitialized(project)) return
            ApplicationManager.getApplication().invokeLater {
                val lineStats = CodeClimbers.getLineStats(document, editorMouseEvent.editor.caretModel.offset)
                CodeClimbers.appendHeartbeat(file, project, false, lineStats)
            }
        } catch (e: Exception) {
            CodeClimbers.debugException(e)
        }
    }

    override fun mouseClicked(editorMouseEvent: EditorMouseEvent) {
        // Implement if needed
    }

    override fun mouseReleased(editorMouseEvent: EditorMouseEvent) {
        // Implement if needed
    }

    override fun mouseEntered(editorMouseEvent: EditorMouseEvent) {
        // Implement if needed
    }

    override fun mouseExited(editorMouseEvent: EditorMouseEvent) {
        // Implement if needed
    }
}
