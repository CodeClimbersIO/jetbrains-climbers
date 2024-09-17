/* ==========================================================
File:        ApiKey.kt
Description: Prompts user for api key if it does not exist.
Maintainer:  CodeClimbers <support@codeclimbers.io>
License:     BSD, see LICENSE for more details.
Website:     https://codeclimbers.io/
===========================================================*/
package io.codeclimbers.jetbrains

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import org.apache.commons.lang.StringEscapeUtils
import org.jetbrains.annotations.Nullable
import io.codeclimbers.jetbrains.ConfigFile
import java.awt.*
import java.awt.GridLayout
import java.io.IOException
import java.net.URISyntaxException
import java.util.*
import javax.swing.*
import javax.swing.event.HyperlinkEvent
import javax.swing.event.HyperlinkListener

class ApiKey(project: Project?) : DialogWrapper(project, true) {
    private val panel: JPanel
    private val label: JLabel
    private val input: JTextField
    private val link: LinkPane

    init {
        title = "Code CLimbers API Key"
        setOKButtonText("Save")
        panel = JPanel()
        panel.layout = GridLayout(0, 1)
        label = JLabel("Enter your Code Climbers API key:", JLabel.CENTER)
        panel.add(label)
        input = JTextField(36)
        panel.add(input)
        link = LinkPane("http://localhost:14400")
        panel.add(link)

        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        val apiKey = input.text
        return try {
            UUID.fromString(apiKey.replaceFirst("^codeclimbers_", ""))
            null
        } catch (e: Exception) {
            ValidationInfo("Invalid api key.")
        }
    }

    override fun doOKAction() {
        ConfigFile.setApiKey(input.text)
        super.doOKAction()
    }

    override fun doCancelAction() {
        CodeClimbers.cancelApiKey = true
        super.doCancelAction()
    }

    fun promptForApiKey(): String {
        input.text = ConfigFile.getApiKey()
        show()
        return input.text
    }
}

class LinkPane(private val url: String) : JTextPane() {

    init {
        isEditable = false
        addHyperlinkListener(UrlHyperlinkListener())
        contentType = "text/html"
        background = Color(0, 0, 0, 0)
        text = url
    }

    private inner class UrlHyperlinkListener : HyperlinkListener {
        override fun hyperlinkUpdate(event: HyperlinkEvent) {
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                try {
                    Desktop.getDesktop().browse(event.url.toURI())
                } catch (e: IOException) {
                    throw RuntimeException("Can't open URL", e)
                } catch (e: URISyntaxException) {
                    throw RuntimeException("Can't open URL", e)
                }
            }
        }
    }

    override fun setText(text: String) {
        super.setText("<html><body style=\"text-align:center;\"><a href=\"$url\">$text</a></body></html>")
    }
}
