# Mobile-Harness DOX

**Project:** Mobile Harness (`com.jarves.mh`)
**Lokasi:** `~/Desktop/Niumination/apps/Mobile-Harness/`
**Pengguna:** Niumination / Afrizal Munthe
**Branch:** `main`
**Latest Commit:** `7474350`

---

## Project Overview

Mobile Harness bridges Android Jetpack Compose UI to an isolated PRoot Linux execution layer. It supports multiple coding agents, each with its own `RuntimeBridge` implementation.

### Supported Agents (4)

| Agent | Bridge Class | Installation | Protocol |
|-------|-------------|-------------|----------|
| Claude Code | `ClaudeRuntimeBridge` | Included in Core | Anthropic-native |
| DeepSeek Harness | `DshRuntimeBridge` | On-demand | Anthropic-compatible |
| Antigravity CLI | `AntigravityRuntimeBridge` | Online download | Google OAuth |
| **Hermes Agent** | `HermesRuntimeBridge` | On-demand (`pip install`) | OpenAI-compatible |

### Agent Pattern

All agents follow the same architecture:
1. **Enum** in `model/Models.kt` (`AgentKind.XXX`)
2. **ProviderKind** in `model/Models.kt` (`ProviderKind.XXX`) + `providersForAgent()` 
3. **Bridge** in `runtime/XXXRuntimeBridge.kt` implementing `RuntimeBridge`
4. **Driver** in `runtime/AgentDriver.kt` as `BuiltInAgentDriver`
5. **Installer** in `runtime/RuntimeInstaller.kt` with `ensureXXXInstalled()`
6. **UI** in `ui/PocketDevApp.kt` — color + abbreviation mapping
7. **Orchestrator** in `ui/MainViewModel.kt` — `AgentRegistry.builtIns()`

---

## Key Files

### Architecture Core
| File | Purpose |
|------|---------|
| `model/Models.kt` | `AgentKind`, `ProviderKind`, `providersForAgent()`, `RuntimeState` |
| `runtime/RuntimeBridge.kt` | Interface all bridges implement |
| `runtime/AgentDriver.kt` | `AgentRegistry`, `BuiltInAgentDriver`, `AgentCapability` |
| `runtime/RuntimeInstaller.kt` | Runtime + agent installation logic |
| `runtime/NativeSpawnProcess.kt` | Native process spawning |
| `runtime/WorkspaceCheckpoints.kt` | Project checkpoint/rollback |
| `runtime/RuntimeExecutionService.kt` | Foreground service for active sessions |
| `runtime/RuntimeSetupService.kt` | Background setup service |

### Agent Bridges
| File | Agent |
|------|-------|
| `runtime/ClaudeRuntimeBridge.kt` | Claude Code |
| `runtime/DshRuntimeBridge.kt` | DeepSeek Harness |
| `runtime/AntigravityRuntimeBridge.kt` | Antigravity CLI |
| `runtime/HermesRuntimeBridge.kt` | Hermes Agent |

### UI Layer
| File | Purpose |
|------|---------|
| `ui/MainViewModel.kt` | Central ViewModel with `AgentRegistry` |
| `ui/PocketDevApp.kt` | Main Compose app |
| `ui/SettingsScreen.kt` | Classic settings |
| `ui/SettingsScreenModern.kt` | Modern settings (provider, agent, connection) |

### Infrastructure
| File | Purpose |
|------|---------|
| `data/ApiKeyVault.kt` | Hardware-backed Keystore encryption |
| `data/AppPreferences.kt` | SharedPreferences wrapper |
| `network/ProviderApiClient.kt` | API client for model providers |
| `network/GitHubClient.kt` | GitHub release/download client |

---

## Build & Test

```bash
# Standard debug APK
./gradlew assembleDebug

# Unit tests
./gradlew testDebugUnitTest

# Static analysis
./gradlew lintDebug

# Deploy to device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Development Workflow

### Adding a New Agent
Follow the 8-step pattern in [DEVELOPMENT-GUIDE.md](docs/DEVELOPMENT-GUIDE.md):
1. `AgentKind` entry → `Models.kt`
2. `ProviderKind` entries → `Models.kt`
3. `XXXRuntimeBridge` → `runtime/`
4. `BuiltInAgentDriver` → `AgentDriver.kt`
5. `ensureXXXInstalled` → `RuntimeInstaller.kt`
6. `XXXRuntime` instance → `MainViewModel.kt`
7. Color + abbreviation → `PocketDevApp.kt`
8. Table row → `README.md`

### Runtime Bundle Updates
- Bundle definitions: `dist/runtime-bundles/manifest.json`
- Build scripts: `scripts/runtime-bundles/`

### Merge from `deepseek_harness` Branch
The `deepseek_harness` branch provided the multi-agent infrastructure. Merge conflicts were resolved in `Models.kt` and `PocketDevApp.kt`. Future branch merges should:
1. `git merge remotes/origin/<branch>` 
2. Resolve any conflicts in `Models.kt`, `PocketDevApp.kt`, `MainViewModel.kt`
3. Verify `AgentRegistry.builtIns()` includes all agents
4. Commit with descriptive message

---

## Dependencies

| Dependency | Purpose |
|------------|---------|
| AndroidX Compose | UI Framework |
| kotlinx.coroutines | Async operations |
| org.json:json | JSON-RPC parsing |
| PRoot | Process virtualization |
| Termux | Linux runtime environment |

---

## Documentation

| Document | Description |
|----------|-------------|
| [HERMES-AGENT-INTEGRATION.md](docs/HERMES-AGENT-INTEGRATION.md) | Complete Hermes integration guide |
| [DEVELOPMENT-GUIDE.md](docs/DEVELOPMENT-GUIDE.md) | Complete development guide |
| [PLAY_STORE_CHECKLIST.md](docs/PLAY_STORE_CHECKLIST.md) | Google Play release checklist |
| [update-testing.md](docs/update-testing.md) | Testing in-app updater |
| — | — |

## CI Notes

### GitHub Actions CI (`.github/workflows/build.yml`)
- **3 jobs**: Lint Check, Unit Tests, Build & Release APK
- **Key commands**:
  - `./gradlew lintDebug` — lint all flavors
  - `./gradlew testDebugUnitTest` — run unit tests
  - `./gradlew -PplayBuild=true assembleDebug` — build Play Protect-compatible APK
- **Android SDK setup**: `android-actions/setup-android@v3` with `packages` input (space-separated string)
- **Java**: `actions/setup-java@v5` (not v4)
- **Release**: `softprops/action-gh-release@v1` automatically creates GitHub Release with APK

### Known Issues & Fixes
- `lintOnlineDebug`/`testOnlineDebugUnitTest` don't exist → use generic `lintDebug`/`testDebugUnitTest`
- `android-actions/setup-android@v3` doesn't accept `compile-sdk`/`target-sdk`/`min-sdk`/`ndk-version` → use `packages: "cmdline-tools;latest platforms;android-36 ..."`
- `setup-java@v4` deprecated → use `@v5`

---

## History

| Date | Event |
|------|-------|
| 2026-09-10 | CI fix: setup-android@v3 uses `packages` input (space-separated string), not compile-sdk/target-sdk/min-sdk |
| 2026-09-10 | CI fix: use `lintDebug`/`testDebugUnitTest` instead of `lintOnlineDebug`/`testOnlineDebugUnitTest` |
| 2026-09-10 | CI fix: `setup-java@v4` → `@v5` to reduce Node.js 20 deprecation warnings |
| 2026-09-10 | Fork takeover complete — `origin` → `Niumination/Mobile-Harness` |
| 2026-09-10 | Merge `deepseek_harness` into `main` — multi-agent infrastructure |
| 2026-09-10 | Hermes Agent integration (`7474350`) — full bridge + installer + UI |
| 2026-09-10 | Documentation created — `HERMES-AGENT-INTEGRATION.md`, `DEVELOPMENT-GUIDE.md` |

## CI Debug Notes

### CI Failure History
| Commit | Issue | Fix |
|--------|-------|-----|
| `943ecd9` | Added APK job | CI existed but didn't build APK |
| `f94ae51` | Updated release action | `actions/create-release@v1` deprecated |
| `326747e` | Fixed setup-android inputs | `compile-sdk` input invalid, `@v4` deprecated |
| `8724efc` | Fixed YAML syntax | `workflow_dispatch` missing colon |
| `34b4af8` | Changed packages format | `packages` as list instead of string |
| `4fa4282` | Changed to @v4 | Still using packages format, but @v4 has Node.js 24 |
| `12d3806` | Revert to compile-sdk format | Still invalid input |
| `205c856` | Changed to lintDebug/testDebugUnitTest | Generic tasks, still fails |
| `0c508bb` | Added test-secrets.properties creation | Waiting for result |
| `c3727ad` | `runtimeBundleDir.asFile.get()` — `asFile` is `File`, not `Provider`, so `.get()` unresolved | 3 jobs gagal kompilasi script |
| `HEAD` | Kedua task prepare jadi no-op murni tanpa sentuh Gradle Directory API | Tunggu CI |
| `3d700d2` | `HERMES_PROVIDERS` tak terdefinisi; DshRouteMapper tak exhaustive; HermesRuntimeBridge pakai event/field fiktif | Lanjut: RuntimeInstaller |
| `HEAD2` | Blok installer Hermes pakai `Proot`/`exec`/konstanta fiktif → tulis ulang ikut pola agy/dsh | Tunggu CI |

### Root Causes
1. `android-actions/setup-android@v3` only accepts `packages` input (space-separated string), not `compile-sdk`, `target-sdk`, etc.
2. `setup-java@v4` deprecated → use `@v5`
3. `lintOnlineDebug`/`testOnlineDebugUnitTest` tasks don't exist → use `lintDebug`/`testDebugUnitTest`
4. `test-secrets.properties` missing → create empty file before gradle runs

### Current Working CI Configuration
```yaml
- android-actions/setup-android@v3
  with: packages: "cmdline-tools;latest platforms;android-36 platforms;android-28 build-tools;36.0.0 build-tools;28.0.3 ndk;26.1.10909125"
- actions/setup-java@v5
- ./gradlew lintDebug
- ./gradlew testDebugUnitTest
- ./gradlew -PplayBuild=true assembleDebug
- softprops/action-gh-release@v1
```
