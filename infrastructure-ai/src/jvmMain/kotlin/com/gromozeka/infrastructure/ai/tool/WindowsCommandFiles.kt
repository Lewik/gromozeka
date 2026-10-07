package com.gromozeka.infrastructure.ai.tool

import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinError
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Windows can briefly retain a sharing lock after a process exits or while a reader
 * closes its handle. Retry only those transient errors, never permissions or bad paths.
 * Persistent locks remain errors; process termination is handled separately by the job.
 */
internal fun deleteWindowsCommandArtifact(file: File) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
    while (true) {
        if (Kernel32.INSTANCE.DeleteFile(file.absolutePath)) return
        val error = Native.getLastError()
        if (error == WinError.ERROR_FILE_NOT_FOUND || error == WinError.ERROR_PATH_NOT_FOUND) return
        val sharingConflict = error == WinError.ERROR_SHARING_VIOLATION || error == WinError.ERROR_LOCK_VIOLATION
        check(sharingConflict && System.nanoTime() < deadline) {
            "Cannot delete command output artifact: ${file.absolutePath} (Windows error $error)"
        }
        Thread.sleep(25)
    }
}
