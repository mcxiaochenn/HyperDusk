/* HyperDusk keeps only the root probes needed by the log health checker. */
package com.sevtinge.hyperceiler.common.utils

object ShellUtils {
    @JvmStatic
    fun checkRootPermission(): Int = try {
        Runtime.getRuntime().exec(arrayOf("sh", "-c", "id -u")).inputStream.bufferedReader()
            .readLine()?.trim()?.toIntOrNull() ?: 1
    } catch (_: Throwable) { 1 }

    @JvmStatic
    fun rootExecCmd(command: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        return process.inputStream.bufferedReader().readText()
    }
}
