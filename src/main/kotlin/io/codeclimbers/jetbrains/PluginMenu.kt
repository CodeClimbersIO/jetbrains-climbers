/* ==========================================================
File:        PluginMenu.kt
Description: Adds a CodeClimbers item to the File menu.
Maintainer:  CodeClimbers <support@codeclimbers.io>
License:     BSD, see LICENSE for more details.
Website:     https://CodeClimbers.io/
===========================================================*/

package io.codeclimbers.jetbrains

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

class PluginMenu : AnAction("CodeClimbers Settings") {
    override fun actionPerformed(e: AnActionEvent) {
        val project: Project? = e.project
        val popup = Settings(project)
        popup.show()
    }
}
