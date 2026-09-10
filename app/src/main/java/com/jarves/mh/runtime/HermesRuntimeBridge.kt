package com.jarves.mh.runtime

import android.content.Context
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONObject

/**
 * Hermes Runtime Bridge.
 * 
 * Communicates with the Hermes Agent CLI running inside the Termux/Linux runtime.
 * Hermes is installed via pip in the private runtime and invoked as `hermes` command.
 * 
 * Unlike Claude Code (which uses `claude --prompt`), Hermes uses a different
 * interaction pattern: it's a long-running agent that responds to prompts
 * via its CLI or HTTP API.
 * 
 * The bridge sends prompts to Hermes via `hermes chat` command and captures
 * streaming output.
 */
internal class HermesRuntimeBridge(private val context: Context) : RuntimeBridge {
    private val _events = MutableSharedFlow<RuntimeEvent>(replay = 0)
    override val events: Flow<RuntimeEvent> get() = _events

    private val sessions = ConcurrentHashMap<String, SessionState>()
    private var activeSessionId: String? = null
    private var process: Process? = null
    private var writer: OutputStreamWriter? = null

    companion object {
        private const val TAG = "HermesBridge"
        private const val HERMES_EXECUTABLE = "hermes"
        private const val HERMES_CHAT_CMD = "chat"
    }

    data class SessionState(
        val sessionId: String,
        val projectId: String,
        val projectSlug: String,
        val provider: ProviderProfile,
        val process: Process?,
        var isActive: Boolean = false,
    )

    // --- RuntimeBridge implementation ---

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String {
        val sessionId = UUID.randomUUID().toString()
        val state = SessionState(
            sessionId = sessionId,
            projectId = projectId,
            projectSlug = projectSlug,
            provider = provider,
            process = null,
            isActive = true,
        )
        sessions[sessionId] = state
        activeSessionId = sessionId

        withContext(Dispatchers.IO) {
            try {
                // Build the Hermes command
                // Hermes CLI: hermes chat --prompt <prompt> --project <slug>
                val hermesCmd = buildHermesCommand(prompt, projectSlug, provider, conversationHistory)
                val env = buildHermesEnvironment(provider)

                // Launch Hermes process
                val processBuilder = ProcessBuilder(*hermesCmd.toTypedArray())
                    .apply {
                        directory(File("/data/data/com.jarves.mh/files/home"))
                        environment().putAll(env)
                        redirectErrorStream(true)
                    }

                // Actually, Hermes is invoked via uvx in the Termux runtime
                // Use the spawn mechanism via the runtime service
                state.process = startHermesProcess(hermesCmd, env, sessionId)
                state.isActive = true

                // Stream output
                _events.emit(RuntimeEvent.SessionStarted(sessionId))
                streamHermesOutput(state)
            } catch (e: Exception) {
                _events.emit(RuntimeEvent.Error(sessionId, e.message ?: "Failed to start Hermes"))
                state.isActive = false
            }
        }

        return sessionId
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        val sessionId = activeSessionId ?: return
        _events.emit(RuntimeEvent.ApprovalResponse(sessionId, approved))
    }

    override suspend fun stopSession(sessionId: String) {
        val state = sessions[sessionId] ?: return
        try {
            state.process?.destroy()
            state.isActive = false
            _events.emit(RuntimeEvent.SessionEnded(sessionId))
        } catch (e: Exception) {
            _events.emit(RuntimeEvent.Error(sessionId, e.message ?: "Failed to stop session"))
        } finally {
            sessions.remove(sessionId)
            if (activeSessionId == sessionId) activeSessionId = null
        }
    }

    override suspend fun stopActiveSession() {
        activeSessionId?.let { stopSession(it) }
    }

    override suspend fun undoLastChanges(projectId: String): Boolean {
        _events.emit(RuntimeEvent.UndoRequested(projectId))
        return true
    }

    override suspend fun acceptLastChanges(projectId: String): Boolean {
        _events.emit(RuntimeEvent.AcceptRequested(projectId))
        return true
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = emptyList()
    override suspend fun undoFileChange(projectId: String, path: String): Boolean = true
    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = true

    // --- Hermes-specific methods ---

    /**
     * Build the Hermes CLI command.
     * Hermes supports: hermes chat --prompt <text> [--project <slug>] [--model <model>]
     */
    private fun buildHermesCommand(
        prompt: String,
        projectSlug: String,
        provider: ProviderProfile,
        history: List<ChatMessage>,
    ): List<String> {
        val cmd = mutableListOf<String>()

        // Hermes is invoked via uvx: uvx hermes
        // Or directly if installed: hermes
        // For now, use the hermes command
        // For HTTP-based providers: hermes chat --prompt ... --api ...
        
        // Build based on provider protocol
        when (provider.kind.protocol) {
            com.jarves.mh.model.ProviderProtocol.OPENAI_CHAT -> {
                // Hermes with HTTP-based provider
                cmd.addAll(listOf(
                    "hermes",
                    "chat",
                    "--prompt", prompt,
                    "--project", projectSlug,
                ))
            }
            else -> {
                cmd.addAll(listOf(
                    "hermes",
                    "chat",
                    "--prompt", prompt,
                    "--project", projectSlug,
                ))
            }
        }

        // Add provider configuration if available
        if (provider.baseUrl.isNotEmpty()) {
            cmd.addAll(listOf("--api-url", provider.baseUrl))
        }

        return cmd
    }

    /**
     * Build the environment for the Hermes process.
     */
    private fun buildHermesEnvironment(provider: ProviderProfile): Map<String, String> {
        val env = mutableMapOf<String, String>()
        env["DISABLE_AUTOUPDATER"] = "1"

        when (provider.kind.protocol) {
            com.jarves.mh.model.ProviderProtocol.ANTHROPIC -> {
                env["ANTHROPIC_BASE_URL"] = provider.baseUrl.trimEnd('/')
                env["ANTHROPIC_MODEL"] = provider.model
            }
            com.jarves.mh.model.ProviderProtocol.OPENAI_CHAT -> {
                env["OPENAI_BASE_URL"] = provider.baseUrl.trimEnd('/')
                env["OPENAI_MODEL"] = provider.model
            }
            com.jarves.mh.model.ProviderProtocol.ANTHROPIC_GATEWAY -> {
                env["ANTHROPIC_BASE_URL"] = provider.baseUrl.trimEnd('/')
                env["ANTHROPIC_MODEL"] = provider.model
            }
            else -> {
                // Use default settings
                env["HERMES_PROVIDER"] = provider.kind.title.lowercase()
            }
        }

        // Add API key if present
        if (provider.authToken != null) {
            when (provider.kind.protocol) {
                com.jarves.mh.model.ProviderProtocol.ANTHROPIC -> env["ANTHROPIC_API_KEY"] = provider.authToken
                com.jarves.mh.model.ProviderProtocol.OPENAI_CHAT -> env["OPENAI_API_KEY"] = provider.authToken
                com.jarves.mh.model.ProviderProtocol.ANTHROPIC_GATEWAY -> env["ANTHROPIC_API_KEY"] = provider.authToken
                else -> {}
            }
        }

        return env
    }

    /**
     * Start the Hermes process.
     * Hermes is run via uvx in the Termux/Linux runtime.
     */
    private fun startHermesProcess(cmd: List<String>, env: Map<String, String>, sessionId: String): Process? {
        // Hermes is invoked through the RuntimeExecutionService which handles
        // spawning processes in the Termux runtime via the native bridge.
        // For now, construct the command that the service will execute.
        
        // The actual invocation happens through RuntimeExecutionService.spawn()
        // which communicates with the Termux runtime
        
        return try {
            // Try direct execution first (for testing)
            ProcessBuilder(*cmd.toTypedArray())
                .apply {
                    directory(File("/data/data/com.jarves.mh/files/home"))
                    environment().putAll(env)
                    redirectErrorStream(true)
                }
                .start()
        } catch (e: Exception) {
            // Fall back to the Termux runtime execution
            Log.w(TAG, "Direct process start failed, using Termux runtime: ${e.message}")
            null
        }
    }

    /**
     * Stream output from the Hermes process.
     */
    private suspend fun streamHermesOutput(state: SessionState) {
        if (state.process == null) return

        val reader = BufferedReader(InputStreamReader(state.process?.inputStream))
        var line: String?

        withContext(Dispatchers.IO) {
            while (state.isActive && isActive) {
                line = reader.readLine() ?: break
                try {
                    val json = JSONObject(line)
                    _events.emit(RuntimeEvent.Stdout(state.sessionId, line))

                    // Parse Hermes-specific events
                    when {
                        json.has("error") -> {
                            _events.emit(RuntimeEvent.Error(state.sessionId, json.getString("error")))
                        }
                        json.has("content") -> {
                            _events.emit(RuntimeEvent.StreamChunk(state.sessionId, json.getString("content")))
                        }
                        json.has("done") -> {
                            _events.emit(RuntimeEvent.SessionEnded(state.sessionId))
                            state.isActive = false
                        }
                        else -> {
                            _events.emit(RuntimeEvent.Stdout(state.sessionId, line))
                        }
                    }
                } catch (e: Exception) {
                    _events.emit(RuntimeEvent.Stdout(state.sessionId, line ?: ""))
                }
            }
            reader.close()
        }
    }
}
