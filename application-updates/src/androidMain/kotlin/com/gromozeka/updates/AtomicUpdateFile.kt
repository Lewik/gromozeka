package com.gromozeka.updates

import android.system.Os
import java.io.File

internal fun moveUpdateFile(source: File, destination: File) {
    Os.rename(source.path, destination.path)
}
