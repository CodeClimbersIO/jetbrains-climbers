/* ==========================================================
File:        Dependencies.kt
Description: Manages plugin dependencies.
Maintainer:  CodeClimbers <support@codeclimbers.io>
License:     BSD, see LICENSE for more details.
Website:     https://codeclimbers.io/
===========================================================*/

package io.codeclimbers.jetbrains

import org.jetbrains.annotations.Nullable
import java.io.*
import java.math.BigInteger
import java.net.*
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import java.util.*
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

class Response(
    var statusCode: Int,
    var body: String,
    var lastModified: String?
) {
    constructor(statusCode: Int, body: String) : this(statusCode, body, null)
}

object Dependencies {
    private var resourcesLocation: String? = null
    private var originalProxyHost: String? = null
    private var originalProxyPort: String? = null
    private const val githubReleasesUrl = "https://api.github.com/repos/wakatime/wakatime-cli/releases/latest"
    private const val githubDownloadUrl = "https://github.com/wakatime/wakatime-cli/releases/latest/download"

    fun is64bit(): Boolean {
        return System.getProperty("os.arch").contains("64")
    }

    fun isWindows(): Boolean {
        return System.getProperty("os.name").contains("Windows")
    }


    fun getResourcesLocation(): String {
        resourcesLocation?.let { return it }

        val userHomeDir = File(System.getProperty("user.home"))
        val resourcesFolder = File(userHomeDir, ".codeclimbers")

        if (System.getenv("CODE_CLIMBERS_HOME") != null && System.getenv("CODE_CLIMBERS_HOME").trim().isNotEmpty()) {
            resourcesFolder.takeIf { it.exists() }?.let {
                resourcesLocation = it.absolutePath
                CodeClimbers.log.debug("Using \$CODE_CLIMBERS_HOME for resources folder: $resourcesLocation")
                return resourcesLocation!!
            }
        }

        resourcesLocation = resourcesFolder.absolutePath
        return resourcesLocation!!
    }

    fun isCLIInstalled(): Boolean {
        val cli = File(getCLILocation())
        return cli.exists()
    }

    fun isCLIOld(): Boolean {
        if (!isCLIInstalled()) {
            return false
        }
        val cmds = mutableListOf<String>().apply {
            getCLILocation()?.let { add(it) }
            add("--version")
        }
        try {
            val p = Runtime.getRuntime().exec(cmds.toTypedArray())
            val stdInput = BufferedReader(InputStreamReader(p.inputStream))
            val stdError = BufferedReader(InputStreamReader(p.errorStream))
            p.waitFor()
            var output = ""
            var s: String?
            while (stdInput.readLine().also { s = it } != null) {
                output += s
            }
            while (stdError.readLine().also { s = it } != null) {
                output += s
            }
            CodeClimbers.log.debug("codeclimbers-cli local version output: \"$output\"")
            CodeClimbers.log.debug("codeclimbers-cli local version exit code: ${p.exitValue()}")

            if (p.exitValue() != 0) return true

            // disable updating codeclimbers-cli when it was built from source
            if (output.trim() == "<local-build>") {
                return false
            }

            val accessed = ConfigFile.get("internal", "cli_version_last_accessed", true)
            val now = CodeClimbers.getCurrentTimestamp().toBigInteger()
            accessed?.let {
                try {
                    val lastAccessed = BigInteger(accessed.trim())
                    val fourHours = BigInteger.valueOf(4 * 3600)
                    if (lastAccessed != null && lastAccessed.add(fourHours).compareTo(now) > 0) {
                        CodeClimbers.log.debug("Skip checking for codeclimbers-cli updates because recently checked ${now.subtract(lastAccessed).toString()} seconds ago")
                        return false
                    }
                } catch (e2: NumberFormatException) {
                    CodeClimbers.warnException(e2)
                }
            }

            val cliVersion = latestCliVersion()
            CodeClimbers.log.debug("Latest codeclimbers-cli version: $cliVersion")
            if (output.trim() == cliVersion) return false
        } catch (e: Exception) {
            CodeClimbers.warnException(e)
        }
        return true
    }

    fun latestCliVersion(): String {
        try {
            val resp = getUrlAsString(githubReleasesUrl, ConfigFile.get("internal", "cli_version_last_modified", true), true)
            if (resp == null) return "Unknown"
            val p = Pattern.compile(".*\"tag_name\":\\s*\"([^\"]+)\",.*")
            val m = p.matcher(resp.body)
            if (m.find()) {
                val cliVersion = m.group(1)
                resp.lastModified?.let {
                    ConfigFile.set("internal", "cli_version_last_modified", true, it)
                    ConfigFile.set("internal", "cli_version", true, cliVersion)
                }
                val now = CodeClimbers.getCurrentTimestamp().toBigInteger()
                ConfigFile.set("internal", "cli_version_last_accessed", true, now.toString())
                return cliVersion
            }
        } catch (e: Exception) {
            CodeClimbers.log.warn(e)
        }
        return "Unknown"
    }


    fun getCLILocation(): String? {
        System.getenv("WAKATIME_CLI_LOCATION")?.let {
            if (it.trim().isNotEmpty()) {
                val wakatimeCLI = File(it)
                if (wakatimeCLI.exists()) {
                    CodeClimbers.log.debug("Using \$WAKATIME_CLI_LOCATION as CLI Executable: $wakatimeCLI")
                    return it
                }
            }
        }

        val ext = if (isWindows()) ".exe" else ""
        return combinePaths(getResourcesLocation(), "wakatime-cli-${osname()}-${architecture()}$ext")
    }


    fun installCLI() {
        val resourceDir = File(getResourcesLocation())
        if (!resourceDir.exists()) resourceDir.mkdirs()

        checkMissingPlatformSupport()

        val url = getCLIDownloadUrl()
        val zipFile = combinePaths(getResourcesLocation(), "codeclimbers-cli.zip")

        if (zipFile?.let { downloadFile(url, it) } == true) {
            // Delete old codeclimbers-cli if it exists
            File(getCLILocation()).takeIf { it.exists() }?.delete()

            val outputDir = File(getResourcesLocation())
            try {
                unzip(zipFile, outputDir)
                if (!isWindows()) {
                    getCLILocation()?.let { makeExecutable(it) }
                }
                File(zipFile).delete()
            } catch (e: IOException) {
                CodeClimbers.log.warn(e)
            }
        }
    }

    private fun checkMissingPlatformSupport() {
        val osname = osname()
        val arch = architecture()

        val validCombinations = arrayOf(
            "darwin-amd64",
            "darwin-arm64",
            "freebsd-386",
            "freebsd-amd64",
            "freebsd-arm",
            "linux-386",
            "linux-amd64",
            "linux-arm",
            "linux-arm64",
            "netbsd-386",
            "netbsd-amd64",
            "netbsd-arm",
            "openbsd-386",
            "openbsd-amd64",
            "openbsd-arm",
            "openbsd-arm64",
            "windows-386",
            "windows-amd64",
            "windows-arm64"
        )
        if (!validCombinations.contains("$osname-$arch")) reportMissingPlatformSupport(osname, arch)
    }

    private fun reportMissingPlatformSupport(osname: String, architecture: String) {
        val url = "https://loom.getCodeClimbers.dev/api/v1/cli-missing?osname=$osname&architecture=$architecture&plugin=${CodeClimbers.IDE_NAME}"
        try {
            getUrlAsString(url, null, false)
        } catch (e: Exception) {
            CodeClimbers.log.warn(e)
        }
    }

    private fun getCLIDownloadUrl(): String {
        return "$githubDownloadUrl/wakatime-cli-" + osname() + "-" + architecture() + ".zip"
    }

    fun downloadFile(url: String, saveAs: String): Boolean {
        val outFile = File(saveAs)

        // create output directory if it does not exist
        val outDir = outFile.parentFile
        if (!outDir.exists()) outDir.mkdirs()

        val downloadUrl = try {
            URL(url)
        } catch (e: MalformedURLException) {
            CodeClimbers.log.error("DownloadFile($url) failed to init new URL")
            CodeClimbers.log.error(e)
            return false
        }

        CodeClimbers.log.debug("DownloadFile(${downloadUrl})")

        setupProxy()

        var fos: FileOutputStream? = null
        try {
            downloadUrl.openStream().use { inputStream ->
                Channels.newChannel(inputStream).use { rbc ->
                    FileOutputStream(saveAs).use { fileOutputStream ->
                        fos = fileOutputStream
                        fileOutputStream.channel.transferFrom(rbc, 0, Long.MAX_VALUE)
                    }
                }
            }
            teardownProxy()
            return true
        } catch (e: RuntimeException) {
            CodeClimbers.log.warn(e)
            // try downloading without verifying SSL cert
            try {
                val sslContext = SSLContext.getInstance("SSL").apply {
                    init(null, arrayOf<TrustManager>(LocalSSLTrustManager()), null)
                }
                (downloadUrl.openConnection() as HttpsURLConnection).apply {
                    sslSocketFactory = sslContext.socketFactory
                    setRequestProperty("User-Agent", "github.com/CodeClimbersIO/jetbrains-climbers")
                    inputStream.use { connInputStream ->
                        FileOutputStream(saveAs).use { fileOutputStream ->
                            fos = fileOutputStream
                            val buffer = ByteArray(4096)
                            var bytesRead: Int
                            while (connInputStream.read(buffer).also { bytesRead = it } != -1) {
                                fileOutputStream.write(buffer, 0, bytesRead)
                            }
                        }
                    }
                }
                teardownProxy()
                return true
            } catch (e: Exception) {
                CodeClimbers.log.warn(e)
            }
        } catch (e: IOException) {
            CodeClimbers.log.warn(e)
        } finally {
            teardownProxy()
            fos?.close()
        }

        return false
    }

    fun getUrlAsString(url: String, lastModified: String?, updateLastModified: Boolean): Response? {
        val text = StringBuilder()

        val downloadUrl = try {
            URL(url)
        } catch (e: MalformedURLException) {
            CodeClimbers.log.error("getUrlAsString($url) failed to init new URL")
            CodeClimbers.log.error(e)
            return null
        }

        CodeClimbers.log.debug("getUrlAsString(${downloadUrl})")

        setupProxy()

        var responseLastModified: String? = null
        var statusCode = -1
        try {
            (downloadUrl.openConnection() as HttpsURLConnection).apply {
                setRequestProperty("User-Agent", "github.com/CodeClimbersIO/jetbrains-climbers")
                if (!lastModified.isNullOrBlank()) {
                    setRequestProperty("If-Modified-Since", lastModified.trim())
                }
                statusCode = responseCode
                if (statusCode == 304) {
                    teardownProxy()
                    return null
                }
                inputStream.use { inputStream ->
                    val buffer = ByteArray(4096)
                    while (inputStream.read(buffer) != -1) {
                        text.append(String(buffer, Charsets.UTF_8))
                    }
                    if (updateLastModified && responseCode == 200) {
                        responseLastModified = getHeaderField("Last-Modified")
                    }
                }
            }
        } catch (e: Exception) {
            CodeClimbers.log.warn(e)
            // Try downloading without verifying SSL cert
            try {
                SSLContext.getInstance("SSL").apply {
                    init(null, arrayOf<TrustManager>(LocalSSLTrustManager()), null)
                }.let { sslContext ->
                    (downloadUrl.openConnection() as HttpsURLConnection).apply {
                        sslSocketFactory = sslContext.socketFactory
                        setRequestProperty("User-Agent", "github.com/CodeClimbersIO/jetbrains-climbers")
                        if (!lastModified.isNullOrBlank()) {
                            setRequestProperty("If-Modified-Since", lastModified.trim())
                        }
                        statusCode = responseCode
                        if (statusCode == 304) {
                            teardownProxy()
                            return null
                        }
                        inputStream.use { inputStream ->
                            val buffer = ByteArray(4096)
                            while (inputStream.read(buffer) != -1) {
                                text.append(String(buffer, Charsets.UTF_8))
                            }
                            if (updateLastModified && responseCode == 200) {
                                responseLastModified = getHeaderField("Last-Modified")
                            }
                        }
                    }
                }
            } catch (e1: Exception) {
                CodeClimbers.log.warn(e1)
            }
        } finally {
            teardownProxy()
        }

        return Response(statusCode, text.toString(), responseLastModified)
    }


    private fun setupProxy() {
        val proxyConfig = ConfigFile.get("settings", "proxy", false)
        if (!proxyConfig.isNullOrBlank()) {
            originalProxyHost = System.getProperty("https.proxyHost")
            originalProxyPort = System.getProperty("https.proxyPort")
            try {
                val proxyUrl = URI(proxyConfig.trim())
                proxyUrl.userInfo?.let { userInfo ->
                    val (user, pass) = userInfo.split(":")
                    Authenticator.setDefault(object : Authenticator() {
                        override fun getPasswordAuthentication(): PasswordAuthentication {
                            return PasswordAuthentication(user, pass.toCharArray())
                        }
                    })
                }

                if (!proxyUrl.host.isNullOrBlank()) {
                    System.setProperty("https.proxyHost", proxyUrl.host)
                    System.setProperty("https.proxyPort", proxyUrl.port.toString())
                }
            } catch (e: URISyntaxException) {
                CodeClimbers.log.error("Proxy string must follow https://user:pass@host:port format: $proxyConfig")
                CodeClimbers.errorException(e)
            }
        }
    }


    private fun teardownProxy() {
        originalProxyHost?.let {
            System.setProperty("https.proxyHost", it)
        } ?: System.clearProperty("https.proxyHost")
        originalProxyPort?.let {
            System.setProperty("https.proxyPort", it)
        } ?: System.clearProperty("https.proxyPort")
        Authenticator.setDefault(null)
    }

    private fun unzip(zipFile: String, outputDir: File) {
        if (!outputDir.exists()) outputDir.mkdirs()

        val buffer = ByteArray(1024)
        val zis = ZipInputStream(FileInputStream(zipFile))
        var ze: ZipEntry? = zis.nextEntry

        while (ze != null) {
            val fileName = ze.name
            val newFile = File(outputDir, fileName)

            if (ze.isDirectory) {
                newFile.mkdirs()
            } else {
                val fos = FileOutputStream(newFile.absolutePath)
                var len: Int
                while (zis.read(buffer).also { len = it } > 0) {
                    fos.write(buffer, 0, len)
                }
                fos.close()
            }

            ze = zis.nextEntry
        }

        zis.closeEntry()
        zis.close()
    }

    private fun recursiveDelete(path: File) {
        if (path.exists()) {
            if (isDirectory(path)) {
                path.listFiles()?.forEach { file ->
                    if (isDirectory(file)) {
                        recursiveDelete(file)
                    } else {
                        file.delete()
                    }
                }
            }
            path.delete()
        }
    }

    fun osname(): String {
        if (isWindows()) return "windows"
        val os = System.getProperty("os.name").toLowerCase()
        when {
            os.contains("mac") || os.contains("darwin") -> return "darwin"
            os.contains("linux") -> return "linux"
            else -> return os
        }
    }

    fun architecture(): String {
        val arch = System.getProperty("os.arch")
        return when {
            arch.contains("386") || arch.contains("32") -> "386"
            arch == "aarch64" -> "arm64"
            osname() == "darwin" && arch.contains("arm") -> "arm64"
            arch.contains("64") -> "amd64"
            else -> arch
        }
    }

    fun combinePaths(vararg args: String): String? {
        var path: File? = null
        for (arg in args) {
            path = if (path == null) File(arg) else File(path, arg)
        }
        return path?.toString()
    }


    private fun makeExecutable(filePath: String) {
        val file = File(filePath)
        try {
            file.setExecutable(true)
        } catch (e: SecurityException) {
            CodeClimbers.warnException(e)
        }
    }

    private fun isSymLink(filepath: File): Boolean {
        return try {
            Files.isSymbolicLink(filepath.toPath())
        } catch (e: SecurityException) {
            CodeClimbers.warnException(e)
            false
        }
    }

    private fun isDirectory(filepath: File): Boolean {
        return try {
            filepath.isDirectory
        } catch (e: SecurityException) {
            CodeClimbers.warnException(e)
            false
        }
    }

    fun createSymlink(source: String, destination: String) {
        val sourceLink = File(source)
        if (isDirectory(sourceLink)) recursiveDelete(sourceLink)
        if (!isWindows()) {
            if (!isSymLink(sourceLink)) {
                recursiveDelete(sourceLink)
                try {
                    Files.createSymbolicLink(sourceLink.toPath(), File(destination).toPath())
                } catch (e: Exception) {
                    CodeClimbers.warnException(e)
                    try {
                        Files.copy(File(destination).toPath(), sourceLink.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    } catch (ex: Exception) {
                        CodeClimbers.warnException(ex)
                    }
                }
            }
        } else {
            try {
                Files.copy(File(destination).toPath(), sourceLink.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                CodeClimbers.warnException(e)
            }
        }
    }

}
