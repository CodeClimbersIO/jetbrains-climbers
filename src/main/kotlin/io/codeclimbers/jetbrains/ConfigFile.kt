/* ==========================================================
File:        ConfigFile.kt
Description: Read and write settings from the INI config file.
Maintainer:  CodeCLimbers <support@codeclimbers.io>
License:     BSD, see LICENSE for more details.
Website:     https://CodeClimbers.io/
===========================================================*/
package io.codeclimbers.jetbrains

import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException

object ConfigFile {
    const val apiUrl = "http://localhost:14400/api/v1"
    private const val fileName = ".wakatime.cfg"
    private const val internalFileName = "wakatime-internal.cfg"
    private var cachedHomeFolder: String? = null
    private var _apiKey = ""
    private var _usingVaultCmd = false

    private fun getConfigFilePath(internal: Boolean): String {
        if (cachedHomeFolder == null) {
            cachedHomeFolder = System.getProperty("user.home")
            if (internal) {
                return File(File(cachedHomeFolder, ".wakatime"), internalFileName).absolutePath
            }
            return File(cachedHomeFolder, fileName).absolutePath
        }
        if (internal) {
            return File(File(cachedHomeFolder, ".wakatime"), internalFileName).absolutePath
        }
        return File(cachedHomeFolder, fileName).absolutePath
    }

    fun get(section: String, key: String, internal: Boolean): String? {
        val file = getConfigFilePath(internal)
        var value: String? = null
        try {
            BufferedReader(FileReader(file)).use { br ->
                var currentSection = ""
                var line: String? = br.readLine()
                while (line != null) {
                    line = line.trim()
                    if (line.startsWith("[") && line.endsWith("]")) {
                        currentSection = line.substring(1, line.length - 1).toLowerCase()
                    } else {
                        if (section.toLowerCase() == currentSection) {
                            val parts = line.split("=")
                            if (parts.size == 2 && parts[0].trim() == key) {
                                value = parts[1].trim()
                                return value?.removeNulls()
                            }
                        }
                    }
                    line = br.readLine()
                }
            }
        } catch (e: IOException) { /* ignored */
        }
        return value?.removeNulls()
    }

    fun set(section: String, key: String, internal: Boolean, value: String) {
        val file = getConfigFilePath(internal)
        val contents = StringBuilder()
        try {
            BufferedReader(FileReader(file)).use { br ->
                var currentSection = ""
                var line = br.readLine()
                var found = false
                while (line != null) {
                    line = line.removeNulls()
                    if (line.trim().startsWith("[") && line.trim().endsWith("]")) {
                        if (section.toLowerCase() == currentSection && !found) {
                            contents.append("$key = $value\n")
                            found = true
                        }
                        currentSection = line.trim().substring(1, line.trim().length - 1).toLowerCase()
                        contents.append("$line\n")
                    } else {
                        if (section.toLowerCase() == currentSection) {
                            val parts = line.split("=")
                            val currentKey = parts[0].trim()
                            if (currentKey == key) {
                                if (!found) {
                                    contents.append("$key = $value\n")
                                    found = true
                                }
                            } else {
                                contents.append("$line\n")
                            }
                        } else {
                            contents.append("$line\n")
                        }
                    }
                    line = br.readLine()
                }
                if (!found) {
                    if (section.toLowerCase() != currentSection) {
                        contents.append("[$section.toLowerCase()]\n")
                    }
                    contents.append("$key = $value\n")
                }
            }
        } catch (e: IOException) { /* ignored */
        }

        val writer = FileWriter(file)
        writer.use { it.write(contents.toString()) }
    }

    fun getApiKey(): String {
        if (_usingVaultCmd) {
            return ""
        }
        if (_apiKey.isNotEmpty()) {
            return _apiKey
        }

        var apiKey = get("settings", "api_key", false)
        if (apiKey == null) {
            val vaultCmd = get("settings", "api_key_vault_cmd", false)
            if (!vaultCmd.isNullOrBlank()) {
                _usingVaultCmd = true
                return ""
            }
            apiKey = ""
        }

        _apiKey = apiKey ?: ""
        return _apiKey
    }

    fun usingVaultCmd(): Boolean {
        return _usingVaultCmd
    }

    fun setApiKey(apiKey: String) {
        set("settings", "api_key", false, apiKey)
        _apiKey = apiKey
    }

    private fun String?.removeNulls(): String? {
        return this?.replace("\u0000", "")
    }
}
