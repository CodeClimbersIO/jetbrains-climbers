package io.codeclimbers.jetbrains

import com.intellij.AppTopics
import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.PluginManager
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.components.ApplicationComponent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.net.HttpConfigurable
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import java.awt.KeyboardFocusManager
import java.io.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.ArrayList
import java.util.Arrays
import java.util.concurrent.*

class CodeClimbers : ApplicationComponent {

    companion object {
        val FREQUENCY = BigDecimal(2 * 60) // max secs between heartbeats for continuous coding
        val log = Logger.getInstance("CodeClimbers")

        var VERSION: String? = null
        var IDE_NAME: String? = null
        var IDE_VERSION: String? = null
        var connection: MessageBusConnection? = null
        var DEBUG = false
        var METRICS = false
        var DEBUG_CHECKED = false
        var STATUS_BAR = false
        var READY = false
        var lastFile: String? = null
        var lastTime = BigDecimal(0)
        var isBuilding = false
        var lineStats = LineStats()
        var cancelApiKey = false

        private const val queueTimeoutSeconds = 30
        private val heartbeatsQueue = ConcurrentLinkedQueue<Heartbeat>()
        private val scheduler = Executors.newScheduledThreadPool(1)
        private var scheduledFixture: ScheduledFuture<*>? = null

        private fun checkDebug() {
            if (DEBUG_CHECKED) return
            DEBUG_CHECKED = true
            if (!DEBUG) return
            ApplicationManager.getApplication().invokeLater {
                Messages.showWarningDialog("Your IDE may respond slower. Disable debug mode from Tools -> CodeClimbers Settings.", "CodeClimbers Debug Mode Enabled")
            }
        }

        fun checkApiKey() {
            if (CodeClimbers.cancelApiKey) return
            ApplicationManager.getApplication().invokeLater {
                // Prompt for apiKey if it does not already exist
                val project = getCurrentProject()
                if (project == null) return@invokeLater
                if (ConfigFile.getApiKey().isEmpty() && !ConfigFile.usingVaultCmd()) {
                    val app = ApplicationManager.getApplication()
                    if (app.isUnitTestMode || !app.isDispatchThread) return@invokeLater
                    try {
                        val apiKey = ApiKey(project)
                        apiKey.promptForApiKey()
                    } catch (e: Exception) {
                        warnException(e)
                    } catch (throwable: Throwable) {
                        log.warn("Unable to prompt for api key because UI not ready.")
                    }
                }
            }
        }

        fun getCurrentTimestamp(): BigDecimal {
            return BigDecimal((System.currentTimeMillis() / 1000.0).toString()).setScale(4, RoundingMode.HALF_UP)
        }

        fun appendHeartbeat(file: VirtualFile, project: Project?, isWrite: Boolean, lineStats: LineStats?) {
            checkDebug()

            if (READY) {
                updateStatusBarText()
                if (project != null) {
                    val statusbar = WindowManager.getInstance().getStatusBar(project)
                    statusbar?.updateWidget("CodeClimbers")
                }
            }

            if (!shouldLogFile(file)) return

            val time = getCurrentTimestamp()

            if (!isWrite && file.path == lastFile && !enoughTimePassed(time)) {
                return
            }

            lastFile = file.path
            lastTime = time

            val projectName = project?.name
            val language = getLanguage(file)

            ApplicationManager.getApplication().executeOnPooledThread {
                val h = Heartbeat().apply {
                    entity = file.path
                    timestamp = time
                    this.isWrite = isWrite
                    isUnsavedFile = !file.exists()
                    this.project = projectName
                    this.language = language
                    isBuilding = CodeClimbers.isBuilding
                    lineStats?.let {
                        lineCount = it.lineCount
                        lineNumber = it.lineNumber
                        cursorPosition = it.cursorPosition
                    }
                }

                heartbeatsQueue.add(h)

                if (isBuilding) setBuildTimeout()
            }
        }

        private fun setBuildTimeout() {
            AppExecutorUtil.getAppScheduledExecutorService().schedule({
                if (!isBuilding) return@schedule
                val project = getCurrentProject() ?: return@schedule
                if (!isProjectInitialized(project)) return@schedule
                val file = getCurrentFile(project) ?: return@schedule
                val document = getCurrentDocument(project)
                appendHeartbeat(file, project, false, null)
            }, 10, TimeUnit.SECONDS)
        }

        private fun processHeartbeatQueue() {
            if (!READY) return

            checkApiKey()

            // get single heartbeat from queue
            val heartbeat = heartbeatsQueue.poll() ?: return

            // get all extra heartbeats from queue
            val extraHeartbeats = ArrayList<Heartbeat>()
            while (true) {
                val h = heartbeatsQueue.poll() ?: break
                extraHeartbeats.add(h)
            }

            sendHeartbeat(heartbeat, extraHeartbeats)
        }


        private fun sendHeartbeat(heartbeat: Heartbeat, extraHeartbeats: ArrayList<Heartbeat>) {
            val cmds = buildCliCommand(heartbeat, extraHeartbeats)
            if (cmds.isEmpty()) {
                return
            }
            log.debug("Executing CLI: ${obfuscateKey(cmds)}")
            try {
                val proc = Runtime.getRuntime().exec(cmds)
                if (extraHeartbeats.isNotEmpty()) {
                    val json = toJSON(extraHeartbeats)
                    log.debug(json)
                    try {
                        val stdin = BufferedWriter(OutputStreamWriter(proc.outputStream))
                        stdin.write(json)
                        stdin.write("\n")
                        try {
                            stdin.flush()
                            stdin.close()
                        } catch (e: IOException) { /* ignored because wakatime-cli closes pipe after receiving \n */ }
                    } catch (e: IOException) {
                        warnException(e)
                    }
                }
                if (DEBUG) {
                    val stdout = BufferedReader(InputStreamReader(proc.inputStream))
                    val stderr = BufferedReader(InputStreamReader(proc.errorStream))
                    proc.waitFor()
                    var s: String?
                    while (stdout.readLine().also { s = it } != null) {
                        log.debug(s)
                    }
                    while (stderr.readLine().also { s = it } != null) {
                        log.debug(s)
                    }
                    log.debug("Command finished with return value: ${proc.exitValue()}")
                }
            } catch (e: Exception) {
                warnException(e)
                if (Dependencies.isWindows() && e.toString().contains("Access is denied")) {
                    try {
                        Messages.showWarningDialog("Microsoft Defender is blocking CodeClimbers. Please allow ${Dependencies.getCLILocation()} to run so CodeClimbers can upload code stats to your dashboard.", "Error")
                    } catch (ex: Exception) { }
                }
            }
        }

        private fun toJSON(extraHeartbeats: ArrayList<Heartbeat>): String {
            val json = StringBuffer()
            json.append("[")
            var first = true
            for (heartbeat in extraHeartbeats) {
                val h = StringBuffer()
                h.append("{\"entity\":\"")
                h.append(jsonEscape(heartbeat.entity))
                h.append("\",\"timestamp\":")
                h.append(heartbeat.timestamp!!.toPlainString())
                h.append(",\"is_write\":")
                h.append(heartbeat.isWrite.toString())
                if (heartbeat.lineCount != null) {
                    h.append(",\"lines\":")
                    h.append(heartbeat.lineCount)
                }
                if (heartbeat.lineNumber != null) {
                    h.append(",\"lineno\":")
                    h.append(heartbeat.lineNumber)
                }
                if (heartbeat.cursorPosition != null) {
                    h.append(",\"cursorpos\":")
                    h.append(heartbeat.cursorPosition)
                }
                if (heartbeat.isUnsavedFile == true) {
                    h.append(",\"is_unsaved_entity\":true")
                }
                if (heartbeat.isBuilding == true) {
                    h.append(",\"category\":\"building\"")
                }
                if (heartbeat.project != null) {
                    h.append(",\"alternate_project\":\"")
                    h.append(jsonEscape(heartbeat.project))
                    h.append("\"")
                }
                if (heartbeat.language != null) {
                    h.append(",\"language\":\"")
                    h.append(jsonEscape(heartbeat.language))
                    h.append("\"")
                }
                h.append("}")
                if (!first) {
                    json.append(",")
                }
                json.append(h)
                first = false
            }
            json.append("]")
            return json.toString()
        }


        private fun jsonEscape(s: String?): String? {
            if (s == null) return null
            val escaped = StringBuilder()
            for (c in s) {
                when (c) {
                    '\\' -> escaped.append("\\\\")
                    '"' -> escaped.append("\\\"")
                    '\b' -> escaped.append("\\b")
                    '\u000C' -> escaped.append("\\f")
                    '\n' -> escaped.append("\\n")
                    '\r' -> escaped.append("\\r")
                    '\t' -> escaped.append("\\t")
                    else -> {
                        val isUnicode = c in '\u0000'..'\u001F' || c in '\u007F'..'\u009F' || c in '\u2000'..'\u20FF'
                        if (isUnicode) {
                            escaped.append("\\u${"%04x".format(c.toInt()).toUpperCase()}")
                        } else {
                            escaped.append(c)
                        }
                    }
                }
            }
            return escaped.toString()
        }



        private fun buildCliCommand(heartbeat: Heartbeat, extraHeartbeats: ArrayList<Heartbeat>): Array<String> {
            val cmds = ArrayList<String>()
            Dependencies.getCLILocation()?.let { cmds.add(it) }
            cmds.add("--plugin")
            val plugin = pluginString()
            if (plugin == null) {
                return emptyArray()
            }
            pluginString()?.let { cmds.add(it) }
            cmds.add("--entity")
            heartbeat.entity?.let { cmds.add(it) }
            cmds.add("--time")
            heartbeat.timestamp?.let { cmds.add(it.toPlainString()) }
            val apiKey = ConfigFile.getApiKey()
            if (apiKey.isNotEmpty()) {
                cmds.add("--key")
                cmds.add(apiKey)
            }
            if (heartbeat.lineCount != null) {
                cmds.add("--lines-in-file")
                cmds.add(heartbeat.lineCount.toString())
            }
            if (heartbeat.lineNumber != null && false) {
                cmds.add("--lineno")
                cmds.add(heartbeat.lineNumber.toString())
            }
            if (heartbeat.cursorPosition != null && false) {
                cmds.add("--cursorpos")
                cmds.add(heartbeat.cursorPosition.toString())
            }
            if (heartbeat.project != null) {
                cmds.add("--alternate-project")
                cmds.add(heartbeat.project!!)
            }
            if (heartbeat.language != null) {
                cmds.add("--alternate-language")
                cmds.add(heartbeat.language!!)
            }
            if (heartbeat.isWrite == true)
                cmds.add("--write")
            if (heartbeat.isUnsavedFile == true)
                cmds.add("--is-unsaved-entity")
            if (heartbeat.isBuilding == true) {
                cmds.add("--category")
                cmds.add("building")
            }
            if (METRICS)
                cmds.add("--metrics")

            val proxy = getBuiltinProxy()
            if (proxy != null) {
                log.info("built-in proxy will be used: $proxy")
                cmds.add("--proxy")
                cmds.add(proxy)
            }

            if (extraHeartbeats.isNotEmpty())
                cmds.add("--extra-heartbeats")
            return cmds.toTypedArray()
        }

        private fun pluginString(): String? {
            if (IDE_NAME.isNullOrEmpty()) {
                return null
            }

            return "$IDE_NAME/$IDE_VERSION $IDE_NAME-CodeClimbers/$VERSION"
        }

        private fun getBuiltinProxy(): String? {
            val config = HttpConfigurable.getInstance()

            if (!config.isHttpProxyEnabledForUrl("https://loom.getCodeClimbers.dev")) return null

            val host = config.PROXY_HOST
            if (host != null) {
                var auth = ""
                val protocol = if (config.PROXY_TYPE_IS_SOCKS) "socks5://" else "https://"

                val user = try {
                    config.getProxyLogin()?.also {
                        auth = "$it:${config.getPlainProxyPassword()}@"
                    }
                } catch (e: NoSuchMethodError) {
                    // Handle the case where the method does not exist
                }

                var url = protocol + auth + host
                if (config.PROXY_PORT > 0) {
                    url += ":%d".format(config.PROXY_PORT)
                }

                return url
            }

            return null
        }


        fun enoughTimePassed(currentTime: BigDecimal): Boolean {
            return lastTime.add(FREQUENCY).compareTo(currentTime) < 0
        }


        fun shouldLogFile(file: VirtualFile?): Boolean {
            if (file == null || file.url.startsWith("mock://")) {
                return false
            }
            val filePath = file.path
            return !(filePath == "atlassian-ide-plugin.xml" || filePath.contains("/.idea/workspace.xml"))
        }

        fun isAppActive(): Boolean {
            return KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow != null
        }

        fun isProjectInitialized(project: Project?): Boolean {
            return project?.isInitialized ?: true
        }

        fun setupConfigs() {
            val debug = ConfigFile.get("settings", "debug", false)
            DEBUG = debug?.trim() == "true"
            val metrics = ConfigFile.get("settings", "metrics", false)
            METRICS = metrics?.trim() == "true"
            val url = ConfigFile.get("settings", "api_url", false)
            if(url == null){
                ConfigFile.set("settings", "api_url", false, ConfigFile.apiUrl)
            }
        }

        fun setupStatusBar() {
            val statusBarVal = ConfigFile.get("settings", "status_bar_enabled", false)
            STATUS_BAR = statusBarVal == null || statusBarVal.trim() != "false"
            if (READY) {
                try {
                    updateStatusBarText()
                    val project = getCurrentProject()
                    if (project == null) return
                    val statusbar = WindowManager.getInstance().getStatusBar(project)
                    if (statusbar == null) return
                    statusbar.updateWidget("CodeClimbers")
                } catch (e: Exception) {
                    warnException(e)
                }
            }
        }

        private fun getLanguage(file: VirtualFile?): String? {
            val type = file?.fileType
            return type?.name
        }

        @Nullable
        fun getFile(document: Document): VirtualFile? {
            if (document == null) return null
            val instance = FileDocumentManager.getInstance() ?: return null
            return instance.getFile(document)
        }

        @Nullable
        fun getCurrentFile(project: Project?): VirtualFile? {
            if (project == null) return null
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
            val document = editor.document
            return getFile(document)
        }

        fun getProject(document: Document): Project? {
            val editors = EditorFactory.getInstance().getEditors(document)
            if (editors.isNotEmpty()) {
                return editors[0].project
            }
            return null
        }

        @Nullable
        fun getCurrentDocument(project: Project?): Document? {
            if (project == null) return null
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
            return editor.document
        }

        @Nullable
        fun getCurrentProject(): Project? {
            return try {
                ProjectManager.getInstance().defaultProject
            } catch (e: Exception) {
                null
            }
        }

        fun getLineStats(document: Document?, offset: Int): LineStats {
            val lineStats = LineStats()
            try {
                document?.let {
                    lineStats.lineCount = it.lineCount
                    lineStats.lineNumber = it.getLineNumber(offset) + 1
                    lineStats.cursorPosition = offset - it.getLineStartOffset(lineStats.lineNumber!! - 1) + 1
                }
            } catch (e: Exception) {
                debugException(e)
            }
            return lineStats
        }

        fun openDashboardWebsite() {
            BrowserUtil.browse("https://app.CodeClimbers.xyz/")
        }

        private var todayText = "initialized"
        private var todayTextTime = BigDecimal.ZERO


        fun getStatusBarText(): String {
            if (!READY || !STATUS_BAR) return ""
            return todayText
        }

        fun updateStatusBarText() {
            val now = getCurrentTimestamp()
            if (todayTextTime.add(BigDecimal(60)).compareTo(now) > 0) return
            todayTextTime = getCurrentTimestamp()

            ApplicationManager.getApplication().executeOnPooledThread {
                val cmds = ArrayList<String>()
                Dependencies.getCLILocation()?.let { cmds.add(it) }
                cmds.add("--today")

                val apiKey = ConfigFile.getApiKey()
                if (apiKey.isNotEmpty()) {
                    cmds.add("--key")
                    cmds.add(apiKey)
                }

                log.debug("Executing CLI: " + Arrays.toString(obfuscateKey(cmds.toTypedArray())))

                try {
                    val proc = Runtime.getRuntime().exec(cmds.toTypedArray())
                    val stdout = BufferedReader(InputStreamReader(proc.inputStream))
                    val stderr = BufferedReader(InputStreamReader(proc.errorStream))
                    proc.waitFor()
                    val output = ArrayList<String>()
                    var s: String?
                    while (stdout.readLine().also { s = it } != null) {
                        output.add(s!!)
                    }
                    while (stderr.readLine().also { s = it } != null) {
                        output.add(s!!)
                    }
                    log.debug("Command finished with return value: " + proc.exitValue())
                    todayText = " " + output.joinToString("")
                    todayTextTime = getCurrentTimestamp()
                } catch (interruptedException: InterruptedException) {
                    warnException(interruptedException)
                } catch (e: Exception) {
                    warnException(e)
                    if (Dependencies.isWindows() && e.toString().contains("Access is denied")) {
                        try {
                            Messages.showWarningDialog("Microsoft Defender is blocking CodeClimbers. Please allow " + Dependencies.getCLILocation() + " to run so CodeClimbers can upload code stats to your dashboard.", "Error")
                        } catch (ex: Exception) {
                        }
                    }
                }
            }
        }
        fun obfuscateKey(key: String?): String? {
            var newKey: String? = null
            if (key != null) {
                newKey = key
                if (key.length > 4) {
                    newKey = "XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXX" + key.substring(key.length - 4)
                }
            }
            return newKey
        }

        fun obfuscateKey(cmds: Array<String>): Array<String> {
            val newCmds = ArrayList<String>()
            var lastCmd = ""
            for (cmd in cmds) {
                if (lastCmd == "--key") {
                    newCmds.add(obfuscateKey(cmd)!!)
                } else {
                    newCmds.add(cmd)
                }
                lastCmd = cmd
            }
            return newCmds.toTypedArray()
        }

        fun debugException(e: Exception) {
            if (!log.isDebugEnabled) return
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val str = e.message + "\n" + sw.toString()
            log.debug(str)
        }

        fun warnException(e: Exception) {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val str = e.message + "\n" + sw.toString()
            log.warn(str)
        }

        fun errorException(e: Exception) {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val str = e.message + "\n" + sw.toString()
            log.error(str)
        }

        //STATIC FUNCTIONS
    }


    override fun initComponent() {
        VERSION = try {
            // support older IDE versions with deprecated PluginManager
            PluginManager.getPlugin(PluginId.getId("xyz.CodeClimbers.jetbrains"))?.version ?: ""
        } catch (e: Exception) {
            // use PluginManagerCore if PluginManager deprecated
            PluginManagerCore.getPlugin(PluginId.getId("xyz.CodeClimbers.jetbrains"))?.version ?: ""
        }
        log.info("Initializing CodeClimbers plugin v$VERSION (https://CodeClimbers.xyz/)")

        // Set runtime constants
        IDE_NAME = ApplicationNamesInfo.getInstance().fullProductName.replace(" ", "").toLowerCase()
        IDE_VERSION = ApplicationInfo.getInstance().fullVersion

        setupConfigs()
        setupStatusBar()
        checkCli()
        setupEventListeners()
        setupQueueProcessor()
    }


    private fun checkCli() {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!Dependencies.isCLIInstalled()) {
                log.info("Downloading and installing wakatime-cli...")
                Dependencies.installCLI()
                CodeClimbers.READY = true
                log.info("Finished downloading and installing wakatime-cli.")
            } else if (Dependencies.isCLIOld()) {
                if (System.getenv("WAKATIME_CLI_LOCATION") != null && !System.getenv("WAKATIME_CLI_LOCATION").trim().isEmpty()) {
                    val CodeClimbersCLI = File(System.getenv("WAKATIME_CLI_LOCATION"))
                    if (CodeClimbersCLI.exists()) {
                        log.warn("\$WAKATIME_CLI_LOCATION is out of date, please update it.")
                    }
                } else {
                    log.info("Upgrading wakatime-cli ...")
                    Dependencies.installCLI()
                    CodeClimbers.READY = true
                    log.info("Finished upgrading wakatime-cli.")
                }
            } else {
                CodeClimbers.READY = true
                log.info("wakatime-cli is up to date.")
            }
            Dependencies.combinePaths(Dependencies.getResourcesLocation(), "wakatime-cli")?.let {
                Dependencies.getCLILocation()?.let { it1 ->
                    Dependencies.createSymlink(
                        it,
                        it1
                    )
                }
            }
            log.debug("wakatime-cli location: " + Dependencies.getCLILocation())
        }
    }

    private fun setupEventListeners() {
        ApplicationManager.getApplication().invokeLater {
//            val disposable = Disposer.newDisposable("CodeClimbersListener")
            val parentDisposable = Disposer.newDisposable("ParentDisposable")
            val disposable = Disposer.newDisposable("CodeClimbersListener")
            Disposer.register(parentDisposable, disposable)
            val connection = ApplicationManager.getApplication().messageBus.connect()

            // Save file
            connection.subscribe(AppTopics.FILE_DOCUMENT_SYNC, CustomSaveListener())

            // Edit document
            EditorFactory.getInstance().eventMulticaster.addDocumentListener(CustomDocumentListener(), disposable)

            // Mouse press
            EditorFactory.getInstance().eventMulticaster.addEditorMouseListener(CustomEditorMouseListener(), disposable)

            // Scroll document
            EditorFactory.getInstance().eventMulticaster.addVisibleAreaListener(CustomVisibleAreaListener(), disposable)

            // Compiling
            // connection.subscribe(BuildManagerListener.TOPIC, CustomBuildManagerListener())
            // connection.subscribe(CompilerTopics.COMPILATION_STATUS, CustomBuildManagerListener())
        }
    }

    private fun setupQueueProcessor() {
        val handler = Runnable {
            processHeartbeatQueue()
        }
        val delay = queueTimeoutSeconds.toLong()
        scheduledFixture = scheduler.scheduleAtFixedRate(handler, delay, delay, TimeUnit.SECONDS)
    }



    override fun disposeComponent() {
        try {
            connection?.disconnect()
        } catch (e: Exception) {
            // Handle exception
        }
        try {
            scheduledFixture?.cancel(true)
        } catch (e: Exception) {
            // Handle exception
        }

        // Make sure to send all heartbeats before exiting
        processHeartbeatQueue()
    }

    @NotNull
    override fun getComponentName(): String {
        return "CodeClimbers"
    }

}