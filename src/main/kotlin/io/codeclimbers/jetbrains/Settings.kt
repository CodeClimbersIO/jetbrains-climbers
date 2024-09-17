/* ==========================================================
File:        Settings.kt
Description: Prompts user for api key if it does not exist.
Maintainer:  CodeClimbers <support@codeclimbers.io>
License:     BSD, see LICENSE for more details.
Website:     https://CodeClimbers.io/
===========================================================*/
package io.codeclimbers.jetbrains

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import org.jetbrains.annotations.Nullable
import javax.swing.*
import java.awt.*
import java.util.UUID

class Settings(@Nullable project: Project?) : DialogWrapper(project, true) {
    private val panel: JPanel
    private val apiKeyLabel: JLabel
    private val apiKey: JTextField
    private val proxyLabel: JLabel
    private val proxy: JTextField
    private val debugLabel: JLabel
    private val debug: JCheckBox
    private val statusBarLabel: JLabel
    private val statusBar: JCheckBox

    init {
        title = "CodeClimbers Settings"
        setOKButtonText("Save")
        panel = JPanel()
        panel.layout = GridLayout(0, 2)

        apiKeyLabel = JLabel("API key:", JLabel.CENTER)
        panel.add(apiKeyLabel)
        apiKey = JTextField(36)
        apiKey.text = ConfigFile.getApiKey()
        panel.add(apiKey)

        proxyLabel = JLabel("Proxy:", JLabel.CENTER)
        panel.add(proxyLabel)
        proxy = JTextField()
        var p = ConfigFile.get("settings", "proxy", false)
        if (p == null) p = ""
        proxy.text = p
        panel.add(proxy)

        statusBarLabel = JLabel("Show CodeClimbers in status bar:", JLabel.CENTER)
        panel.add(statusBarLabel)
        val statusBarValue = ConfigFile.get("settings", "status_bar_enabled", false)
        statusBar = JCheckBox()
        statusBar.isSelected = statusBarValue == null || !statusBarValue.trim().toLowerCase().equals("false")
        panel.add(statusBar)

        debugLabel = JLabel("Debug:", JLabel.CENTER)
        panel.add(debugLabel)
        val debugValue = ConfigFile.get("settings", "debug", false)
        debug = JCheckBox()
        debug.isSelected = debugValue != null && debugValue.trim().toLowerCase().equals("true")
        panel.add(debug)

        init()
    }

    @Nullable
    override fun createCenterPanel(): JComponent {
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        try {
            UUID.fromString(apiKey.text.replaceFirst("^waka_", ""))
        } catch (e: Exception) {
            return ValidationInfo("Invalid api key.")
        }
        return null
    }

    override fun doOKAction() {
        ConfigFile.setApiKey(apiKey.text)
        ConfigFile.set("settings", "proxy", false, proxy.text)
        ConfigFile.set("settings", "debug", false, if (debug.isSelected) "true" else "false")
        ConfigFile.set("settings", "status_bar_enabled", false, if (statusBar.isSelected) "true" else "false")
        CodeClimbers.setupConfigs()
        CodeClimbers.setupStatusBar()
        super.doOKAction()
    }
}
