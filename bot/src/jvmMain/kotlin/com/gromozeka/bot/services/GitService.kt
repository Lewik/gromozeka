package com.gromozeka.bot.services

import klog.KLoggers

import org.springframework.stereotype.Service
import java.io.File

@Service
class GitService {
    private val log = KLoggers.logger(this)

    fun initializeRepository(directory: File): Boolean {
        return try {
            if (!directory.exists()) {
                directory.mkdirs()
            }
            
            val gitDir = File(directory, ".git")
            if (gitDir.exists()) {
                log.info("Git repository already exists in $directory")
                return true
            }
            
            log.info("Initializing git repository in $directory")
            
            val initResult = runGitCommand(directory, "git", "init")
            if (!initResult) {
                log.warn("Failed to initialize git repository")
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
            
            log.info("Git repository initialized successfully")
            true
            
        } catch (e: Exception) {
            log.warn("Error initializing git repository: ${e.message}")
            false
        }
    }
    
    fun addAndCommit(directory: File, message: String): Boolean {
        return try {
            // Add all changes
            val addResult = runGitCommand(directory, "git", "add", ".")
            if (!addResult) {
                log.warn("Failed to add files to git")
                return false
            }
            
            // Check if there are changes to commit
            val statusResult = runGitCommandWithOutput(directory, "git", "status", "--porcelain")
            if (statusResult.isNullOrBlank()) {
                log.debug("No changes to commit")
                return true
            }
            
            // Commit changes
            val commitResult = runGitCommand(directory, "git", "commit", "-m", message)
            if (commitResult) {
                log.info("Successfully committed: $message")
                true
            } else {
                log.warn("Failed to commit changes")
                false
            }
            
        } catch (e: Exception) {
            log.warn("Error during git commit: ${e.message}")
            false
        }
    }
    
    fun push(directory: File, remote: String = "origin", branch: String = "main"): Boolean {
        return try {
            // Check if remote exists
            val remoteResult = runGitCommandWithOutput(directory, "git", "remote")
            if (remoteResult?.contains(remote) != true) {
                log.debug("Remote '$remote' not configured, skipping push")
                return true // Not an error, just no remote configured
            }
            
            val pushResult = runGitCommand(directory, "git", "push", remote, branch)
            if (pushResult) {
                log.info("Successfully pushed to $remote/$branch")
                true
            } else {
                log.warn("Push failed or no changes to push")
                false
            }
            
        } catch (e: Exception) {
            log.warn("Error during git push: ${e.message}")
            false
        }
    }
    
    fun addRemote(directory: File, name: String, url: String): Boolean {
        return try {
            val result = runGitCommand(directory, "git", "remote", "add", name, url)
            if (result) {
                log.info("Successfully added remote '$name': $url")
            } else {
                log.warn("Failed to add remote '$name'")
            }
            result
        } catch (e: Exception) {
            log.warn("Error adding remote: ${e.message}")
            false
        }
    }
    
    fun getRemotes(directory: File): List<String> {
        return try {
            val output = runGitCommandWithOutput(directory, "git", "remote", "-v")
            output?.lines()?.filter { it.isNotBlank() } ?: emptyList()
        } catch (e: Exception) {
            log.warn("Error getting remotes: ${e.message}")
            emptyList()
        }
    }
    
    fun getStatus(directory: File): String? {
        return try {
            runGitCommandWithOutput(directory, "git", "status", "--porcelain")
        } catch (e: Exception) {
            log.warn("Error getting git status: ${e.message}")
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
                log.warn("Git command failed (exit code $exitCode): ${command.joinToString(" ")}")
                log.debug("Output: $errorOutput")
            }
            
            exitCode == 0
        } catch (e: Exception) {
            log.warn("Exception running git command: ${e.message}")
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
                log.warn("Git command failed (exit code $exitCode): ${command.joinToString(" ")}")
                log.debug("Output: $output")
                null
            }
        } catch (e: Exception) {
            log.warn("Exception running git command: ${e.message}")
            null
        }
    }
}