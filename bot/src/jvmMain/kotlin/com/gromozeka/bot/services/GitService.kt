package com.gromozeka.bot.services

import org.springframework.stereotype.Service
import java.io.File

@Service
class GitService {

    fun initializeRepository(directory: File): Boolean {
        return try {
            if (!directory.exists()) {
                directory.mkdirs()
            }
            
            val gitDir = File(directory, ".git")
            if (gitDir.exists()) {
                println("[GitService] Git repository already exists in $directory")
                return true
            }
            
            println("[GitService] Initializing git repository in $directory")
            
            val initResult = runGitCommand(directory, "git", "init")
            if (!initResult) {
                println("[GitService] Failed to initialize git repository")
                return false
            }
            
            // Create initial .gitignore if needed
            val gitignore = File(directory, ".gitignore")
            if (!gitignore.exists()) {
                gitignore.writeText("""
                    # Temporary files
                    *.tmp
                    *.log
                    
                    # System files
                    .DS_Store
                    Thumbs.db
                """.trimIndent())
            }
            
            println("[GitService] Git repository initialized successfully")
            true
            
        } catch (e: Exception) {
            println("[GitService] Error initializing git repository: ${e.message}")
            false
        }
    }
    
    fun addAndCommit(directory: File, message: String): Boolean {
        return try {
            // Add all changes
            val addResult = runGitCommand(directory, "git", "add", ".")
            if (!addResult) {
                println("[GitService] Failed to add files to git")
                return false
            }
            
            // Check if there are changes to commit
            val statusResult = runGitCommandWithOutput(directory, "git", "status", "--porcelain")
            if (statusResult.isNullOrBlank()) {
                println("[GitService] No changes to commit")
                return true
            }
            
            // Commit changes
            val commitResult = runGitCommand(directory, "git", "commit", "-m", message)
            if (commitResult) {
                println("[GitService] Successfully committed: $message")
                true
            } else {
                println("[GitService] Failed to commit changes")
                false
            }
            
        } catch (e: Exception) {
            println("[GitService] Error during git commit: ${e.message}")
            false
        }
    }
    
    fun push(directory: File, remote: String = "origin", branch: String = "main"): Boolean {
        return try {
            // Check if remote exists
            val remoteResult = runGitCommandWithOutput(directory, "git", "remote")
            if (remoteResult?.contains(remote) != true) {
                println("[GitService] Remote '$remote' not configured, skipping push")
                return true // Not an error, just no remote configured
            }
            
            val pushResult = runGitCommand(directory, "git", "push", remote, branch)
            if (pushResult) {
                println("[GitService] Successfully pushed to $remote/$branch")
                true
            } else {
                println("[GitService] Push failed or no changes to push")
                false
            }
            
        } catch (e: Exception) {
            println("[GitService] Error during git push: ${e.message}")
            false
        }
    }
    
    fun addRemote(directory: File, name: String, url: String): Boolean {
        return try {
            val result = runGitCommand(directory, "git", "remote", "add", name, url)
            if (result) {
                println("[GitService] Successfully added remote '$name': $url")
            } else {
                println("[GitService] Failed to add remote '$name'")
            }
            result
        } catch (e: Exception) {
            println("[GitService] Error adding remote: ${e.message}")
            false
        }
    }
    
    fun getRemotes(directory: File): List<String> {
        return try {
            val output = runGitCommandWithOutput(directory, "git", "remote", "-v")
            output?.lines()?.filter { it.isNotBlank() } ?: emptyList()
        } catch (e: Exception) {
            println("[GitService] Error getting remotes: ${e.message}")
            emptyList()
        }
    }
    
    fun getStatus(directory: File): String? {
        return try {
            runGitCommandWithOutput(directory, "git", "status", "--porcelain")
        } catch (e: Exception) {
            println("[GitService] Error getting git status: ${e.message}")
            null
        }
    }
    
    private fun runGitCommand(directory: File, vararg command: String): Boolean {
        return try {
            val processBuilder = ProcessBuilder(*command)
            processBuilder.directory(directory)
            processBuilder.redirectErrorStream(true)
            
            val process = processBuilder.start()
            val exitCode = process.waitFor()
            
            if (exitCode != 0) {
                val errorOutput = process.inputStream.bufferedReader().readText()
                println("[GitService] Git command failed (exit code $exitCode): ${command.joinToString(" ")}")
                println("[GitService] Output: $errorOutput")
            }
            
            exitCode == 0
        } catch (e: Exception) {
            println("[GitService] Exception running git command: ${e.message}")
            false
        }
    }
    
    private fun runGitCommandWithOutput(directory: File, vararg command: String): String? {
        return try {
            val processBuilder = ProcessBuilder(*command)
            processBuilder.directory(directory)
            processBuilder.redirectErrorStream(true)
            
            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                output
            } else {
                println("[GitService] Git command failed (exit code $exitCode): ${command.joinToString(" ")}")
                println("[GitService] Output: $output")
                null
            }
        } catch (e: Exception) {
            println("[GitService] Exception running git command: ${e.message}")
            null
        }
    }
}