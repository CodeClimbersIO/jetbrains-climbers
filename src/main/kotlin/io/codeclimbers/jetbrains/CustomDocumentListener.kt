package io.codeclimbers.jetbrains

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class CustomDocumentListener : DocumentListener {
    override fun documentChanged(event: DocumentEvent) {
        try {
            if (!CodeClimbers.isAppActive()) return
            val document = event.document
            val file = CodeClimbers.getFile(document)
            if (file == null) return
            val project = CodeClimbers.getProject(document)
            if (!CodeClimbers.isProjectInitialized(project)) return
            val lineStats = CodeClimbers.getLineStats(document, event.offset)
            CodeClimbers.appendHeartbeat(file, project, false, lineStats)
        } catch (e: Exception) {
            CodeClimbers.debugException(e)
        }
    }

    override fun beforeDocumentChange(event: DocumentEvent) {}
}
