package io.codeclimbers.jetbrains

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import org.jetbrains.annotations.NotNull

class CodeClimbersStartupActivity : StartupActivity.Background {
    override fun runActivity(@NotNull project: Project) {
        CodeClimbers.checkApiKey()
    }
}
