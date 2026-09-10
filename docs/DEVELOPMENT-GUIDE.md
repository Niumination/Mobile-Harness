# Mobile Harness Development Guide

> **Project:** Mobile Harness (`com.jarves.mh`)
> **Branch:** `main`
> **Latest Commit:** `08b8c39`
> **Last Updated:** 2026-09-10

This guide covers all aspects of developing Mobile Harness, from architecture overview to agent integration patterns.

---

## Project Overview

Mobile Harness is an Android application that provides a full coding environment on mobile devices. It bridges native Android Jetpack Compose UI to an isolated PRoot Linux execution layer.

> ⚠️ **Play Protect Notice:** Default debug builds use `targetSdk 28` and are blocked by Play Protect on Android 13+. Use `./gradlew -PplayBuild=true :app:assembleOnlineDebug` to build a compatible APK (online flavor, targetSdk 36) without ADB or upload keystore. Install `app/build/outputs/apk/online/debug/app-online-debug.apk` via file manager.

### Core Architecture

```
Android Host (Kotlin) ──JNI──▶ PRoot Linux (Ubuntu 20.04 ARM64) ──▶ Model Providers
       │                                                          │
       │    ┌──────────────────────────────────────────────────┐ │
       │    │  Agent Registry                                  │ │
       │    │  ├── Claude Code (ClaudeRuntimeBridge)          │ │
       │    │  ├── DeepSeek Harness (DshRuntimeBridge)        │ │
       │    │  ├── Antigravity CLI (AntigravityRuntimeBridge)  │ │
       │    │  └── Hermes Agent (HermesRuntimeBridge)          │ │
       │    └──────────────────────────────────────────────────┘ │
       │                                                          │
       └── C++ JNI Process Bridge ──────────────────────────────┘
```

### Key Directories

```
app/src/main/java/com/jarves/mh/
├── data/          # Preferences, Keystore AES encryption, SQLite persistence
├── model/         # Data entities: Projects, Chats, Files, Tool calls, AgentKind, ProviderKind
├── network/       # API clients for provider communication
├── runtime/       # PRoot installer, agent bridges, runtime execution
│   ├── AgentDriver.kt          # AgentRegistry + BuiltInAgentDriver
│   ├── ClaudeRuntimeBridge.kt  # Claude Code bridge
│   ├── DshRuntimeBridge.kt     # DeepSeek Harness bridge
│   ├── AntigravityRuntimeBridge.kt # Antigravity CLI bridge
│   ├── HermesRuntimeBridge.kt  # Hermes Agent bridge ← NEW
│   ├── RuntimeBridge.kt        # Interface all bridges implement
│   ├── RuntimeInstaller.kt     # Runtime + agent installation
│   ├── RuntimeSetupService.kt  # Background setup service
│   ├── RuntimeExecutionService.kt # Foreground service for active sessions
│   ├── NativeSpawnProcess.kt   # Native process spawning
│   └── WorkspaceCheckpoints.kt # Project checkpoint/rollback
├── ui/            # Jetpack Compose screens, ViewModels
│   ├── MainViewModel.kt        # Central ViewModel orchestrating all agents
│   ├── PocketDevApp.kt         # Main Compose app with agent selection
│   ├── SettingsScreen.kt       # Settings UI (classic)
│   ├── SettingsScreenModern.kt # Settings UI (modern)
│   └── ...
└── ...

app/src/main/cpp/
└── pocket_spawn.c   # Native C++ JNI process launcher

scripts/runtime-bundles/
└── manifest.json    # Runtime bundle definitions
```

---

## Adding a New Coding Agent

This section explains the complete process of adding a new coding agent to Mobile Harness. Follow these steps for any new agent integration.

### Step 1: Add AgentKind Entry

In `app/src/main/java/com/jarves/mh/model/Models.kt`:

```kotlin
enum class AgentKind(
    val stableId: String,
    val title: String,
    val subtitle: String,
    val downloadNote: String,
) {
    // ... existing entries ...
    
    NEW_AGENT(
        "new-agent-id",
        "New Agent Name",
        "Description of what it does",
        "Installation size or note",
    ),
    ;
}
```

### Step 2: Add ProviderKind Entries (if needed)

If the agent requires specific providers:

```kotlin
// Add to ProviderKind enum
NEW_PROVIDER("Provider Name", "Subtitle", ProviderProtocol.OPENAI_CHAT, "url", "model")
```

And create a providers list:

```kotlin
private val NEW_AGENT_PROVIDERS = setOf(ProviderKind.PROVIDER1, ProviderKind.PROVIDER2)
```

Update `providersForAgent`:

```kotlin
AgentKind.NEW_AGENT -> ProviderKind.entries.filter { it in NEW_AGENT_PROVIDERS }
```

### Step 3: Create the RuntimeBridge Implementation

Create `app/src/main/java/com/jarves/mh/runtime/NewAgentRuntimeBridge.kt`:

```kotlin
internal class NewAgentRuntimeBridge(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
) : RuntimeBridge {
    private val _events = MutableSharedFlow<RuntimeEvent>(replay = 0)
    override val events: Flow<RuntimeEvent> get() = _events
    
    // ... implement all RuntimeBridge methods ...
}
```

Follow the pattern from `ClaudeRuntimeBridge.kt` or `HermesRuntimeBridge.kt`.

### Step 4: Add to AgentDriver Registry

In `app/src/main/java/com/jarves/mh/runtime/AgentDriver.kt`, add the import and entry:

```kotlin
import com.jarves.mh.runtime.NewAgentRuntimeBridge

// In AgentRegistry.builtIns:
companion object {
    fun builtIns(
        claude: RuntimeBridge,
        deepSeek: RuntimeBridge,
        antigravity: RuntimeBridge,
        hermes: RuntimeBridge,
        newAgent: RuntimeBridge,
    ) = AgentRegistry(
        listOf(
            // ... existing entries ...
            BuiltInAgentDriver(
                AgentKind.NEW_AGENT,
                newAgent,
                setOf(
                    AgentCapability.API_KEY,
                    AgentCapability.PROVIDER_PICKER,
                    AgentCapability.MODEL_PICKER,
                    AgentCapability.RESUME,
                ),
            ),
        )
    )
}
```

### Step 5: Add to RuntimeInstaller

In `app/src/main/java/com/jarves/mh/runtime/RuntimeInstaller.kt`:

```kotlin
// Add to ensureAgentInstalled:
com.jarves.mh.model.AgentKind.NEW_AGENT -> ensureNewAgentInstalled(runtime.proot, 0.05f, onProgress)

// Add to isAgentInstalled:
com.jarves.mh.model.AgentKind.NEW_AGENT -> isInstalled() &&
    File(rootfs, NEW_AGENT_GUEST_PATH.removePrefix("/")).canExecute() &&
    !newAgentMarker.readTextOrNull().isNullOrBlank()

// Add the installation method:
private suspend fun ensureNewAgentInstalled(
    proot: File, // the proot binary — there is no `Proot` class
    fraction: Float,
    onProgress: suspend (RuntimeInstallProgress) -> Unit,
) {
    // ... installation logic, e.g. process(...) + verifyGuest(...) ...
}
```

### Step 6: Add to MainViewModel

In `app/src/main/java/com/jarves/mh/ui/MainViewModel.kt`:

```kotlin
import com.jarves.mh.runtime.NewAgentRuntimeBridge

private val newAgentRuntime = NewAgentRuntimeBridge(application) { profile -> vault.get(profile.kind.name) }

private val agentRegistry = AgentRegistry.builtIns(
    claudeRuntime, dshRuntime, antigravityRuntime, hermesRuntime, newAgentRuntime
)
```

### Step 7: Add to UI

In `app/src/main/java/com/jarves/mh/ui/PocketDevApp.kt`:

Add color and abbreviation mappings:

```kotlin
AgentKind.NEW_AGENT -> Color(0xFFRRGGBB)  // Distinct color
AgentKind.NEW_AGENT -> "NA"               // Abbreviation
```

### Step 8: Update README.md

Add the new agent to the coding agents table in README.md.

---

## Runtime Bundle System

### Structure

Runtime bundles are compressed rootfs archives (`tar.zst`) distributed via CDN:

| Bundle | Size | Contents |
|--------|------|----------|
| `core` | ~149 MB compressed | Node.js, npm, Git, Claude Code |
| `python` | ~55 MB compressed | Python 3, pip, venv |
| `android` | ~100 MB | Android SDK, Gradle, NDK |

### Adding a New Bundle

1. Create the rootfs archive
2. Compute SHA-256 checksum
3. Add entry to `dist/runtime-bundles/manifest.json`
4. Update `RuntimeInstaller` to check for and install the bundle
5. Add build script to `scripts/runtime-bundles/`

### Bundle Download Flow

```
User selects agent → RuntimeInstaller checks if agent installed
→ If not, download bundle → Verify SHA-256 → Extract to proot → Write marker
```

---

## Testing

### Unit Tests

```bash
./gradlew :app:testOnlineDebugUnitTest :app:testOfflineDebugUnitTest
```

Run specific tests:

```bash
./gradlew :app:testOnlineDebugUnitTest --tests "com.jarves.mh.runtime.*"
```

### Static Analysis

```bash
./gradlew :app:lintOnlineDebug :app:lintOfflineDebug
```

### Testing on Device

```bash
./gradlew -PplayBuild=true :app:assembleOnlineDebug
# copy app/build/outputs/apk/online/debug/app-online-debug.apk to the phone,
# install via file manager — no ADB needed
```

### Debugging Runtime

To debug runtime issues, connect to the device and inspect:

```bash
# Check runtime setup status
adb shell cat /data/data/com.jarves.mh/files/setup/runtime-setup.log

# Check if proot is running
adb shell ps | grep proot

# Access the runtime directly
adb shell su -c "chroot /data/data/com.jarves.mh/files/home /bin/bash"
```

---

## Build Variants

### Standard Debug APK (Online Flavor)

```bash
./gradlew -PplayBuild=true :app:assembleOnlineDebug
```

The generic `assembleDebug` task name is ambiguous (two flavors: `online`, `offline`) and fails task resolution — always qualify with `:app:` and the flavor. CI builds the online flavor and publishes `app-online-debug.apk` to GitHub Releases. Always clone with `--recurse-submodules` — the C++ bridge needs `third_party/proot` and `third_party/libandroid-shmem`.

### Play-Compliant Build

`-PplayBuild=true` switches `targetSdk` 28 → 36 so the APK is Play Protect compatible and sideloadable via file manager on Android 13+ without ADB or an upload keystore.

### Offline Flavor

```bash
./gradlew :app:assembleOfflineDebug
```

The offline flavor sets `OFFLINE_RUNTIME_BUNDLES=true` (a BuildConfig flag, not a `-P` property) and expects pre-staged files under `dist/runtime-bundles/`. Debug CI builds intentionally skip bundling (`prepare*Assets` tasks are no-ops) so the APK stays small and downloadable on demand.

---

## Dependencies & Version Tracking

### Key Dependencies

| Dependency | Purpose | Location |
|------------|---------|----------|
| AndroidX Compose | UI Framework | `app/build.gradle.kts` |
| kotlinx.coroutines | Async operations | Runtime bridges |
| org.json:json | JSON parsing | Runtime communication |
| PRoot | Process virtualization | Native C++ bridge |
| Termux | Linux runtime | Runtime execution |

### Version Constants

Key version constants are in `app/src/main/java/com/jarves/mh/` subpackages. Check `BuildConfig` for auto-generated versions.

---

## Code Patterns

### RuntimeBridge Pattern

All agent bridges follow this pattern:

1. **Class**: `XxxRuntimeBridge(context, secretFor) : RuntimeBridge` — `secretFor` resolves the API token from `ApiKeyVault`, keyed by `ProviderProfile.kind`
2. **Events**: `MutableSharedFlow<RuntimeEvent>` for streaming events
3. **Session**: `startSession()` spawns process, returns sessionId
4. **Streaming**: Background coroutine reads stdout, emits `RuntimeEvent.AssistantDelta` / `RuntimeLog`
5. **Cleanup**: `stopSession()` destroys process, emits `RuntimeEvent.SessionCompleted`

### AgentDriver Pattern

Each agent is registered as a `BuiltInAgentDriver`:

```kotlin
BuiltInAgentDriver(
    AgentKind.XXX,           // The agent identifier
    runtimeBridge,            // The RuntimeBridge implementation
    setOf(                    // Supported capabilities
        AgentCapability.API_KEY,
        AgentCapability.PROVIDER_PICKER,
        AgentCapability.MODEL_PICKER,
        AgentCapability.RESUME,
    )
)
```

### Capability Matrix

| Agent | ACCOUNT_LOGIN | API_KEY | PROVIDER_PICKER | MODEL_PICKER | RESUME | INTERACTIVE_APPROVALS | REASONING_EFFORT |
|-------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| Claude Code | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |
| DeepSeek Harness | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |
| Antigravity CLI | ✅ | ❌ | ❌ | ✅ | ✅ | ❌ | ✅ |
| Hermes Agent | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |

---

## Repository History

### Recent Commits

| Commit | Description | Key Changes |
|--------|-------------|-------------|
| `7474350` | Hermes Agent integration | Full Hermes bridge, 322 new lines |
| `3d700d2`–`57164b8` | Hermes compile fixes | HERMES_PROVIDERS, exhaustive Dsh mapper, bridge events, installer rewrite |
| `1ce7380` | CI green | Per-flavor tasks, submodules, online APK → Release v1.0.3 |
| `2f8f38a` | CI cleanup | Removed release workflow |
| `4805af2` | CI lint | Limited build to lint + unit tests |

### Branches

| Branch | Status | Purpose |
|--------|--------|---------|
| `main` | ✅ Active | Production code, all 4 agents supported |
| `dev` | ✅ Pushed | Development branch |
| `deepseek_harness` | ✅ Pushed | DeepSeek integration (merged into main) |

---

## Documentation Index

| Document | Description |
|----------|-------------|
| [HERMES-AGENT-INTEGRATION.md](HERMES-AGENT-INTEGRATION.md) | Complete Hermes Agent integration guide |
| [PLAY_STORE_CHECKLIST.md](PLAY_STORE_CHECKLIST.md) | Google Play release checklist |
| [update-testing.md](update-testing.md) | Testing in-app updater without publishing |
| [play/](play/) | Play Store compliance documents |
| [screenshots/](screenshots/) | App screenshots and graphics |

---

## Support

For questions about Mobile Harness development:
- Check the source code comments
- Review the commit history (`git log --oneline`)
- Consult the [Niumination ecosystem docs](~/Desktop/Niumination/AGENTS.md)
