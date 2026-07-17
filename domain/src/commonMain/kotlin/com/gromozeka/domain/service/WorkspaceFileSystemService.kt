package com.gromozeka.domain.service

import com.gromozeka.domain.model.WorkspaceDirectoryListing

interface WorkspaceFileSystemService {
    suspend fun browse(
        path: String? = null,
        includeFiles: Boolean = true,
    ): WorkspaceDirectoryListing
}
