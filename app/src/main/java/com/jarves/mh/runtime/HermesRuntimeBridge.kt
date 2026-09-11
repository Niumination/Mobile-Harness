package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Hermes Runtime Bridge.
 *
 * Invokes the real Hermes Agent CLI (`pip install hermes-agent`) inside the
 * PRoot Linux guest via [RuntimeInstaller.process] — the same mechanism the
 * DeepSeek bridge uses. One-shot mode (`hermes chat -q … -Q`) prints the final
 * response as plain text; provider selection uses Hermes' built-in provider
 * names (`-m MODEL --provider NAME`, see the hermes-agent skill reference).
 */
internal class HermesRuntimeBridge(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
) : RuntimeBridge {
    private val _events = MutableSharedFlow<RuntimeEvent>(replay = 0)
    override val events: Flow<RuntimeEvent> get() = _events

    private val installer = RuntimeInstaller(context)
    private val sessions = ConcurrentHashMap<String, SessionState>()
    private var activeSessionId: String? = null

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
        var process: Process?,
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
                val installed = installer.installedRuntime()
                if (!installer.isAgentInstalled(com.jarves.mh.model.AgentKind.HERMES)) {
                    _events.emit(
                        RuntimeEvent.SessionFailed(
                            sessionId,
                            "Hermes Agent is not installed. Open Settings → Coding agent to install it.",
                        ),
                    )
                    state.isActive = false
                    return@withContext
                }
                val token = secretFor(provider)
                val mapped = mapProvider(provider)
                if (mapped?.keyEnv != null && token.isNullOrBlank()) {
                    _events.emit(
                        RuntimeEvent.SessionFailed(sessionId, "API key is missing. Reconnect the provider in Settings."),
                    )
                    state.isActive = false
                    return@withContext
                }
                // Real Hermes CLI: `hermes chat -q PROMPT -Q -m MODEL --provider NAME`.
                val hermesCmd = buildHermesCommand(prompt, provider)
                    ?: run {
                        _events.emit(
                            RuntimeEvent.SessionFailed(
                                sessionId,
                                "Endpoint ${provider.resolvedBaseUrl} has no built-in Hermes provider mapping yet.",
                            ),
                        )
                        state.isActive = false
                        return@withContext
                    }
                val env = buildHermesEnvironment(provider, token.orEmpty())
                val workspace = File(context.filesDir, "workspaces/$projectSlug").apply { mkdirs() }
                state.process = installer.process(
                    installed.proot,
                    installed.rootfs,
                    workspace,
                    env,
                    hermesCmd,
                    guestWorkspacePath = "/workspace/$projectSlug",
                )
                state.isActive = true

                // Stream output
                _events.emit(RuntimeEvent.SessionStarted(sessionId))
                streamHermesOutput(state)
            } catch (e: Exception) {
                Log.e(TAG, "Hermes session failed", e)
                _events.emit(RuntimeEvent.SessionFailed(sessionId, e.message ?: "Failed to start Hermes"))
                state.isActive = false
            }
        }

        return sessionId
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        _events.emit(
            if (approved) RuntimeEvent.ToolApproved(request.sessionId, request.approvalId)
            else RuntimeEvent.ToolRejected(request.sessionId, request.approvalId),
        )
    }

    override suspend fun stopSession(sessionId: String) {
        val state = sessions[sessionId] ?: return
        try {
            state.process?.destroy()
            state.isActive = false
            _events.emit(RuntimeEvent.SessionCompleted(sessionId))
        } catch (e: Exception) {
            _events.emit(RuntimeEvent.SessionFailed(sessionId, e.message ?: "Failed to stop session"))
        } finally {
            sessions.remove(sessionId)
            if (activeSessionId == sessionId) activeSessionId = null
        }
    }

    override suspend fun stopActiveSession() {
        activeSessionId?.let { stopSession(it) }
    }

    override suspend fun undoLastChanges(projectId: String): Boolean {
        _events.emit(RuntimeEvent.RuntimeLog(activeSessionId ?: projectId, "Undo", "Undo requested for $projectId"))
        return true
    }

    override suspend fun acceptLastChanges(projectId: String) {
        _events.emit(RuntimeEvent.RuntimeLog(activeSessionId ?: projectId, "Accept", "Accept requested for $projectId"))
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = emptyList()
    override suspend fun undoFileChange(projectId: String, path: String): Boolean = true
    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = true

    // --- Hermes-specific methods ---

    /**
     * Build the real Hermes CLI command.
     * One-shot: `hermes chat -q PROMPT -Q -m MODEL --provider NAME`.
     * Returns null when the endpoint has no built-in Hermes provider mapping.
     */
    private fun buildHermesCommand(prompt: String, provider: ProviderProfile): List<String>? {
        val mapped = mapProvider(provider) ?: return null
        return listOf(
            HERMES_EXECUTABLE,
            HERMES_CHAT_CMD,
            "-q", prompt,
            "-Q",
            "-m", provider.model.ifBlank { return null },
            "--provider", mapped.name,
        )
    }

    /** Built-in Hermes provider name + key env for a known endpoint host. Null keyEnv = keyless. */
    private data class HermesProvider(val name: String, val keyEnv: String?)

    private fun mapProvider(provider: ProviderProfile): HermesProvider? {
        if (provider.kind == com.jarves.mh.model.ProviderKind.OPENCODE_FREE) {
            return HermesProvider("opencode-free", null)
        }
        val base = provider.resolvedBaseUrl.lowercase()
        return when {
            "opencode.ai/zen/go" in base -> HermesProvider("opencode-go", "OPENCODE_GO_API_KEY")
            "opencode.ai/zen" in base -> HermesProvider("opencode-zen", "OPENCODE_ZEN_API_KEY")
            "api.anthropic.com" in base -> HermesProvider("anthropic", "ANTHROPIC_API_KEY")
            "api.openai.com" in base -> HermesProvider("openai", "OPENAI_API_KEY")
            "api.deepseek.com" in base -> HermesProvider("deepseek", "DEEPSEEK_API_KEY")
            "generativelanguage.googleapis.com" in base -> HermesProvider("gemini", "GOOGLE_API_KEY")
            "api.x.ai" in base -> HermesProvider("xai", "XAI_API_KEY")
            "openrouter.ai" in base -> HermesProvider("openrouter", "OPENROUTER_API_KEY")
            else -> null
        }
    }

    /** Key env only — Hermes reads settings from its config, secrets from env. */
    private fun buildHermesEnvironment(provider: ProviderProfile, token: String): Map<String, String> {
        val env = mutableMapOf<String, String>()
        env["DISABLE_AUTOUPDATER"] = "1"
        mapProvider(provider)?.let { mapped ->
            if (mapped.keyEnv != null && token.isNotBlank()) env[mapped.keyEnv] = token
        }
        return env
    }

    /**
     * Stream plain-text output from `hermes chat -q -Q`. Every line is
     * forwarded as it arrives; a non-zero exit fails loudly with the tail
     * instead of hanging silently.
     */
    private suspend fun streamHermesOutput(state: SessionState) {
        val proc = state.process
        if (proc == null) {
            _events.emit(RuntimeEvent.SessionFailed(state.sessionId, "Hermes process did not start."))
            state.isActive = false
            return
        }
        withContext(Dispatchers.IO) {
            val tail = ArrayDeque<String>()
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    var line: String?
                    while (state.isActive && isActive) {
                        line = reader.readLine() ?: break
                        if (line.isNotBlank()) {
                            tail.addLast(line)
                            if (tail.size > 20) tail.removeFirst()
                            _events.emit(RuntimeEvent.AssistantDelta(state.sessionId, line))
                        }
                    }
                }
                val exit = proc.waitFor()
                if (!state.isActive || !isActive) return@withContext
                if (exit == 0) {
                    _events.emit(RuntimeEvent.SessionCompleted(state.sessionId))
                } else {
                    val detail = tail.takeLast(5).joinToString("\n").take(500)
                    _events.emit(
                        RuntimeEvent.SessionFailed(
                            state.sessionId,
                            if (detail.isBlank()) "Hermes exited with code $exit." else "Hermes exited ($exit): $detail",
                        ),
                    )
                }
            } catch (e: Exception) {
                if (state.isActive) {
                    Log.e(TAG, "Hermes output failed", e)
                    _events.emit(RuntimeEvent.SessionFailed(state.sessionId, e.message ?: "Hermes output failed"))
                }
            } finally {
                state.isActive = false
            }
        }
    }
}
