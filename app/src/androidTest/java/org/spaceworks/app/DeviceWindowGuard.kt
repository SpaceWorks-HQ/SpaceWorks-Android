package org.spaceworks.app

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Test infrastructure only. Never treats a Compose-only capture as proof that the OS window is clear. */
object DeviceWindowGuard {
    fun awaitClear(timeoutMillis: Long = 8_000) {
        require(timeoutMillis in 100..30_000)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val context = instrumentation.targetContext
        val appLabel = context.applicationInfo.loadLabel(context.packageManager).toString()
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        var recovered = false
        while (true) {
            val root = automation.rootInActiveWindow
            if (root == null) {
                if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("No active OS window is available for UI validation.")
                SystemClock.sleep(100); continue
            }
            val titles = mutableListOf<String>()
            val errorAction = booleanArrayOf(false)
            collectSystemFailures(root, titles, errorAction, intArrayOf(0))
            @Suppress("DEPRECATION")
            root.recycle()
            if (titles.isEmpty() && errorAction[0]) titles += "Unrecognized system application error"
            if (titles.isEmpty()) return
            // Read only the window-manager package marker. Never print the accessibility tree,
            // which could contain fixture passwords, OTPs or recovery codes.
            val windows = shell("dumpsys window windows")
            val packages = DeviceWindowPolicy.errorPackages(windows)
            val launcher = DeviceWindowPolicy.recoverableLauncher(titles, packages, appLabel)
                ?: throw AssertionError("An app or unknown system error dialog blocks UI validation. The test must fail.")
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("The emulator launcher ANR did not clear; UI evidence is blocked.")
            if (!recovered) {
                val output = File(context.getExternalFilesDir("validation"), "device-diagnostics").apply { mkdirs() }
                val screen = requireNotNull(automation.takeScreenshot())
                File(output, "launcher-anr-before-recovery.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screen.recycle()
                File(output, "launcher-recovery.txt").appendText("Closed emulator launcher ANR for $launcher; app dialogs are never dismissed.\n")
                recovered = true
            }
            // Only a fixed allowlisted launcher package can reach this command. No data is erased.
            shell("am force-stop $launcher")
            SystemClock.sleep(150)
        }
    }

    @Suppress("DEPRECATION")
    private fun collectSystemFailures(root: AccessibilityNodeInfo, output: MutableList<String>, errorAction: BooleanArray, visited: IntArray, depth: Int = 0) {
        if (depth > 16 || output.size > 8 || visited[0]++ > 512) return
        val text = root.text?.toString().orEmpty()
        if (root.packageName?.toString() in setOf("android", "com.android.systemui") && DeviceWindowPolicy.isFailureTitle(text)) output += text
        if (root.viewIdResourceName in setOf("android:id/aerr_close", "android:id/aerr_wait")) errorAction[0] = true
        for (index in 0 until minOf(root.childCount, 64)) {
            val child = root.getChild(index) ?: continue
            try { collectSystemFailures(child, output, errorAction, visited, depth + 1) } finally { child.recycle() }
        }
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
            val buffer = CharArray(256 * 1024)
            var count = 0
            while (count < buffer.size) {
                val read = reader.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            String(buffer, 0, count)
        }
    }
}

internal object DeviceWindowPolicy {
    private val launchers = setOf("com.google.android.apps.nexuslauncher", "com.android.launcher3", "com.android.launcher")
    private fun normal(value: String) = value.lowercase().replace('\u2019', '\'')
    fun isFailureTitle(text: String): Boolean = normal(text).let { value ->
        listOf("isn't responding", "is not responding", "keeps stopping", "has stopped").any(value::contains)
    }
    // AOSP distinguishes the ANR window title from its crash-dialog title.
    fun errorPackages(windows: String): Set<String> = Regex("(?:Application Error|Application Not Responding): ([A-Za-z0-9_.]+)").findAll(windows).map { it.groupValues[1] }.toSet()
    fun recoverableLauncher(titles: List<String>, packages: Set<String>, appLabel: String): String? {
        if (titles.isEmpty() || packages.size != 1) return null
        val candidate = packages.single()
        if (candidate !in launchers) return null
        val label = normal(appLabel).filter(Char::isLetterOrDigit)
        if (label.isNotEmpty() && titles.any { normal(it).filter(Char::isLetterOrDigit).contains(label) }) return null
        if (titles.any { text ->
                val value = normal(text)
                !(value.startsWith("pixel launcher ") || value.startsWith("launcher3 ") || value.startsWith("launcher ") || value.startsWith("quickstep ")) ||
                    !(value.contains("isn't responding") || value.contains("is not responding"))
            }) return null
        return candidate
    }
}
