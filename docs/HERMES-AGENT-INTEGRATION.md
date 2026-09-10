# Hermes Agent Integration Guide

> **Status:** ✅ Implemented (Commit `7474350`)
> **Last Updated:** 2026-09-10
> **Branch:** `main`

## Overview

Hermes Agent (by Nous Research) is a personal AI coding agent with built-in skills, memory, and interactive workflows. Mobile Harness can now run Hermes Agent directly on Android through the private Termux/Linux runtime.

This document explains the full integration architecture, how Hermes is installed, how it communicates with the Android host, and how developers can extend or debug the Hermes runtime.

---

## Architecture

### High-Level Flow

```
┌─────────────────────────────────────────────────────┐
│  Android Host (Kotlin + Jetpack Compose)            │
│                                                     │
│  ┌──────────────┐    ┌──────────────────────────┐  │
│  │  SettingsScreen│──│  MainViewModel           │  │
│  │  (Hermes UI) │  │  (AgentRegistry)          │  │
│  └──────────────┘  │    │                       │  │
│                    │    ├── claudeRuntime       │  │
│  ┌──────────────┐  │    ├── dshRuntime         │  │
│  │  PocketDevApp│──│  ├── antigravityRuntime   │  │
│  │  (Chat UI)   │  │  └── hermesRuntime        │  │
│  └──────────────┘  │    (HermesRuntimeBridge)  │  │
│                    └────────────┬───────────────┘  │
│                               │                   │
└───────────────────────────────┼───────────────────┘
                                │ RuntimeBridge interface
                                │ (startSession, respondToApproval, etc.)
                                ▼
┌─────────────────────────────────────────────────────┐
│  Private Linux Runtime (PRoot ARM64)                │
│                                                     │
│  ┌──────────────────────────────────────────────┐   │
│  │  Termux/Linux Environment                    │   │
│  │  ├── hermes command (pip installed)          │   │
│  │  ├── hermes chat --prompt <text>            │   │
│  │  └── stdin/stdout JSON communication         │   │
│  └──────────────────────────────────────────────┘   │
│                                                     │
│  Provider Flow:                                     │
│  9router (localhost:20128) → Agent → Response      │
│  or: Custom API URL → Agent → Response              │
└─────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | File | Responsibility |
|-----------|------|----------------|
| `HermesRuntimeBridge` | `runtime/HermesRuntimeBridge.kt` | Spawns Hermes process, streams output, emits RuntimeEvents |
| `AgentKind.HERMES` | `model/Models.kt` | Enum entry identifying Hermes as an agent |
| `ProviderKind.HERMES` | `model/Models.kt` | Enum entry for the Hermes provider |
| `HERMES_PROVIDERS` | `model/Models.kt` | Set of providers compatible with Hermes |
| `HermesBuiltInAgentDriver` | `runtime/AgentDriver.kt` | Registers Hermes with capabilities |
| `ensureHermesInstalled` | `runtime/RuntimeInstaller.kt` | Pip installs Hermes in runtime |
| `AgentRegistry` | `runtime/AgentDriver.kt` | Orchestrates all agents including Hermes |

---

## Installation Flow

### How Hermes Gets Installed on Android

1. User selects **Hermes Agent** from the agent picker in Settings
2. `RuntimeInstaller.ensureAgentInstalled(AgentKind.HERMES)` is called
3. The installer checks if `File(rootfs, HERMES_GUEST_PATH).canExecute()` — if Hermes is already installed, skip
4. If not installed, `ensureHermesInstalled()` runs:
   ```bash
   pip install hermes-agent
   ```
   inside the PRoot Linux environment
5. After installation, verify `File(rootfs, "/usr/local/bin/hermes").canExecute()`
6. Write `.hermes-version` marker file
7. `hermesVersion` property returns the installed version

### Prerequisites

- Core runtime must be installed (Ubuntu 20.04 ARM64 rootfs)
- Python/pip must be available in the runtime
- Network access for `pip install hermes-agent`

### Offline Consideration

Hermes is **not bundled** in the APK. It requires network access for `pip install`. To support offline:
- Create a runtime bundle (like `pocketdev-python-arm64`) with Hermes pre-installed
- Or include `hermes-agent` as a pre-extracted package in `assets/`

---

## Communication Protocol

### HermesRuntimeBridge Pattern

`HermesRuntimeBridge` implements the `RuntimeBridge` interface:

```kotlin
interface RuntimeBridge {
    val events: Flow<RuntimeEvent>
    suspend fun startSession(projectId, projectSlug, projectKind, prompt, conversationHistory, provider): String
    suspend fun respondToApproval(request, approved)
    suspend fun stopSession(sessionId)
    suspend fun stopActiveSession()
    suspend fun undoLastChanges(projectId): Boolean
    suspend fun acceptLastChanges(projectId): Boolean
    // ... etc
}
```

### Hermes Command Structure

Hermes is invoked via the `hermes chat` command:

```bash
hermes chat --prompt "<user's prompt>" --project "<project-slug>" [--api-url <url>] [--api-key <key>]
```

The bridge handles:
- **Process spawning** via `ProcessBuilder` in the Termux runtime
- **Stdout streaming** — capturing `RuntimeEvent.StreamChunk` events
- **Environment setup** — provider-specific env vars (API keys, base URLs)
- **Error detection** — parsing JSON-RPC responses for errors

### Environment Variables Set by HermesRuntimeBridge

| Variable | When Set | Purpose |
|----------|----------|---------|
| `DISABLE_AUTOUPDATER` | Always | Prevent Hermes from auto-updating |
| `ANTHROPIC_BASE_URL` | Anthropic protocol | Route requests to custom endpoint |
| `ANTHROPIC_MODEL` | Anthropic protocol | Override default model |
| `OPENAI_BASE_URL` | OpenAI-compatible | Route to gateway |
| `OPENAI_API_KEY` | When authToken present | API authentication |
| `ANTHROPIC_API_KEY` | When authToken present | API authentication |

### Supported Providers for Hermes

Hermes supports the same provider protocol as the 9router/gateway:

- **9router** (`NINE_ROUTER`) — local model router at `http://localhost:20128/v1`
- **AgentRouter** (`AGENTROUTER`) — multi-model gateway
- **Huancheng** (`HUANCHENG`) — Anthropic-compatible endpoint
- **Custom** (`CUSTOM`) — any Anthropic-compatible API endpoint

---

## Developer Guide

### Adding Hermes to a New Build Variant

If you create a custom build that includes Hermes pre-installed:

1. Add `hermes-agent` to the runtime bundle
2. Create a pre-extracted `.deb` or pip wheel in `assets/runtime-bundles/`
3. Update `RuntimeInstaller.ensureHermesInstalled()` to check for the pre-installed binary
4. Set `HERMES_GUEST_PATH` to the correct installation path

### Debugging Hermes on Device

```bash
# Connect to the device and access the runtime
adb shell
su -c "cd /data/data/com.jarves.mh/files/home && hermes --version"

# Check if Hermes is installed
su -c "ls -la /usr/local/bin/hermes"

# Run Hermes directly (for testing)
su -c "hermes chat --prompt 'hello' --project test"

# Check Hermes logs
su -c "cat ~/.hermes/logs/*.log"
```

### Testing HermesRuntimeBridge

The bridge follows the same testing pattern as `ClaudeRuntimeBridge`:
- Unit tests verify `buildHermesCommand()` generates correct arguments
- Integration tests verify `startSession()` emits `RuntimeEvent.SessionStarted`
- Error handling tests verify `RuntimeEvent.Error` is emitted on failures

### Hermes Configuration

Hermes stores its configuration in the Linux runtime home directory:
- `~/.hermes/config.toml` — Agent configuration
- `~/.hermes/skills/` — Built-in and custom skills
- `~/.hermes/memory/` — Agent memory and context

These persist across app restarts because they live in the private runtime filesystem.

---

## RuntimeBundle Integration

### Current State

Hermes is installed **on-demand** via `pip install`. There is no bundled runtime for Hermes like there is for Claude Code or DeepSeek Harness.

### Creating a Hermes Runtime Bundle

To create a standalone runtime bundle with Hermes pre-installed:

```bash
# 1. Create a minimal Ubuntu rootfs with Hermes
# 2. Package it as a tar.zst archive
# 3. Add entry to dist/runtime-bundles/manifest.json
# 4. Update RuntimeInstaller to check for the bundle
```

The bundle would be stored in `scripts/runtime-bundles/` and distributed via the same CDN infrastructure as the core runtime.

---

## Comparison: Hermes vs Claude Code vs DeepSeek

| Feature | Hermes Agent | Claude Code | DeepSeek Harness |
|---------|-------------|-------------|------------------|
| **Installation** | `pip install hermes-agent` | Included in Core bundle | On-demand via `dsh` |
| **Command** | `hermes chat --prompt` | `claude --prompt` | `dsh --prompt` |
| **Protocol** | OpenAI-compatible | Anthropic-native | Anthropic-compatible |
| **Provider** | 9router, AgentRouter, Huancheng, Custom | Anthropic, OpenRouter, 9router, etc. | DeepSeek, Kimi, OpenCode Zen |
| **Auth** | API key | OAuth login or API key | API key |
| **Bridge Class** | `HermesRuntimeBridge` | `ClaudeRuntimeBridge` | `DshRuntimeBridge` |
| **Capabilities** | API_KEY, PROVIDER_PICKER, MODEL_PICKER, RESUME, INTERACTIVE_APPROVALS | API_KEY, ACCOUNT_LOGIN, PROVIDER_PICKER, MODEL_PICKER, RESUME, INTERACTIVE_APPROVALS | API_KEY, PROVIDER_PICKER, MODEL_PICKER, RESUME, INTERACTIVE_APPROVALS |
| **Memory** | Built-in (`~/.hermes/memory/`) | Context-based | Context-based |
| **Skills** | Built-in + custom (`~/.hermes/skills/`) | Built-in | Built-in |

---

## Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|--------------|-----|
| Hermes not found after install | pip install failed or pip path wrong | Check `pip install hermes-agent` output in runtime |
| `hermes` command not executable | Binary not in `/usr/local/bin/` | Check file permissions: `ls -la /usr/local/bin/hermes` |
| Provider connection error | Wrong API key or base URL | Verify provider config in Settings |
| Session starts but no response | Hermes CLI crashed or timed out | Check `RuntimeEvent.Error` for details |
| Memory not persisting | Runtime filesystem issue | Verify `.hermes/memory/` directory exists |
| Skills not loading | Skills directory missing | Check `~/.hermes/skills/` in runtime |

---

## Future Enhancements

- [ ] **Hermes runtime bundle** — Pre-installed for offline use
- [ ] **Hermes-specific settings UI** — Model selection, reasoning effort, memory management
- [ ] **Hermes skills browser** — Built-in UI for discovering and managing skills
- [ ] **Hermes conversation history** — Persist conversations in `AppPreferences`
- [ ] **Hermes error codes** — Structured error reporting for Hermes-specific failures
- [ ] **Hermes MCP support** — Connect Hermes to Niumination MCP tools

---

## References

- [Hermes Agent Documentation](https://github.com/NousResearch/hermes-agent)
- [RuntimeBridge Interface](app/src/main/java/com/jarves/mh/runtime/RuntimeBridge.kt)
- [Models.kt — AgentKind & ProviderKind](app/src/main/java/com/jarves/mh/model/Models.kt)
- [RuntimeInstaller.kt — Installation Logic](app/src/main/java/com/jarves/mh/runtime/RuntimeInstaller.kt)
- [HermesRuntimeBridge.kt — Bridge Implementation](app/src/main/java/com/jarves/mh/runtime/HermesRuntimeBridge.kt)
- [AgentDriver.kt — Agent Registry](app/src/main/java/com/jarves/mh/runtime/AgentDriver.kt)
- [MainViewModel.kt — Orchestration](app/src/main/java/com/jarves/mh/ui/MainViewModel.kt)
