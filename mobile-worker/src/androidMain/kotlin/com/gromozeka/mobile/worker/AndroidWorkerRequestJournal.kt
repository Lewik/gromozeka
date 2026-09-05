package com.gromozeka.mobile.worker

import android.content.Context
import com.gromozeka.shared.utils.sha256
import com.gromozeka.worker.runtime.SnapshotWorkerRequestJournal
import com.gromozeka.worker.runtime.WorkerRequestSnapshotStore
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

internal class AndroidWorkerRequestJournal(context: Context, streamId: String) : AutoCloseable {
    private val file = AndroidWorkerEncryptedFile(File(context.noBackupFilesDir, "worker-requests-${streamId.sha256()}.enc").toPath())
    private val lockChannel = FileChannel.open(file.path.resolveSibling("${file.path.fileName}.lock"),
        StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    private val lock = try {
        checkNotNull(lockChannel.tryLock()) { "Another Android Worker owns this request journal" }
    } catch (error: Throwable) {
        lockChannel.close()
        throw error
    }

    val journal = SnapshotWorkerRequestJournal(object : WorkerRequestSnapshotStore {
        override suspend fun read(): String? = file.read()
        override suspend fun write(snapshot: String) = file.write(snapshot)
    })

    override fun close() {
        lock.release()
        lockChannel.close()
    }
}
