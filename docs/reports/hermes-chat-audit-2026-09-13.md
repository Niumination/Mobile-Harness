# Audit: Hermes Agent di Mobile-Harness — 2026-09-13

Status: AUDIT ONLY. Tidak ada kode diubah untuk temuan ini. Tree bersih (`git status`
kosong); satu stash `WIP: hermes git-tarball install` menyimpan rintisan Tahap 1 yang
escaping-nya rusak — jangan dipakai langsung, tulis ulang dari catatan ini.

## 1. Arsitektur aktual (fakta dari kode HEAD `15de6b4`)

Komponen Hermes:

- `runtime/HermesRuntimeBridge.kt` (276 baris) — `startSession()` → cek
  `installedRuntime()` + `isAgentInstalled(HERMES)` → `secretFor()` dari vault →
  `mapProvider()` → `buildHermesCommand()` = `hermes chat -q PROMPT -Q -m MODEL
  --provider NAME` → `installer.process()` (proot, non-PTY) → `streamHermesOutput()`
  membaca `proc.inputStream` per baris → `AssistantDelta`; exit != 0 →
  `SessionFailed` + 5 baris tail.
- `runtime/NativeSpawnProcess.kt` — stdout+stderr native → `outputFile`;
  `getInputStream() = FileInputStream(outputFile)` (baca file yang sedang ditulis);
  `getErrorStream()` kosong.
- `runtime/RuntimeInstaller.kt` — install via `uv tool install hermes-agent`
  (PyPI) + fallback pip; marker `.pocket-hermes-version`; `checkAgentUpdates()`
  TANPA entri HERMES; `updateHermes()` = `uv tool upgrade` (PyPI); consent flag via
  sed ke guest `/root/.hermes/config.yaml`.
- `ui/MainViewModel.kt` — `sendPrompt()` → `startSession()`; `onRuntimeEvent()`
  menangani delta/selesai/gagal; `persistMessages()` tiap event; toast untuk
  `SessionFailed` hanya bila reason mengandung allowlist substring.
- `network/ProviderApiClient.kt` — Test/discover/validate = HTTP langsung dari HP,
  TIDAK lewat hermes CLI.
- `model/Models.kt` — `HERMES_PROVIDERS = {ANTHROPIC, OPENCODE_ZEN, OPENCODE_FREE,
  CUSTOM}`; `OPENCODE_FREE` fixed base+protocol, default `ling-3.0-flash-fin-free`.
- `data/AppPreferences.kt` — provider per-agen, `opencodeSessionId` stabil.

## 2. Temuan P0 — chat gagal secara struktural (bukan nasib)

- **P0-1. Guest hermes 0.19.0 tidak kenal `opencode-free`.** Bukti: log HP 23:08
  `no API keys or providers found... Run: hermes setup`; PyPI 0.19.0 rilis
  2026-07-20, plugin opencode-free lahir 2026-08-13, keyless 2026-08-20
  (`ca06b87689`). Selama guest dari PyPI, SEMUA chat FREE gagal pasti.
- **P0-2. Tidak ada jalur reinstall/update Hermes dari UI.**
  `installAgent()` bila terinstall hanya `selectAgent()` (MainViewModel:1295);
  `updateAgent()` butuh entri `agentUpdates[kind]` yang tidak pernah ada untuk
  HERMES karena `checkAgentUpdates()` tidak memeriksanya. Pengguna terkunci di
  versi rusak — persis keluhan "tidak bisa install ulang".
- **P0-3. Toast allowlist → gagal diam.** `SessionFailed` dengan teks baru
  ("no API keys...", dst.) tidak mengandung keyword allowlist → tanpa toast,
  tanpa teks. Pola allowlist rapuh: setiap pesan error baru = diam lagi.
  Seharusnya denylist (hanya redam yang diketahui berisik) atau selalu tampil.
- **P0-4. Race output file → `ENOENT`.** `streamHermesOutput` membuka
  `FileInputStream(outputFile)` segera, sementara native membuat file belakangan
  (proot fork lambat). Bukti 23:08:05, file muncul sesudahnya berisi output
  hermes. Satu sesi mati sia-sia tiap race menang.
- **P0-5. CUSTOM (+ preset localhost) = jalan buntu untuk Hermes.**
  `mapProvider()` null → chat pasti gagal "no built-in Hermes provider mapping
  yet", padahal UI mengizinkan memilihnya. 2 dari 4 opsi = gagal pasti.
- **P0-6. "Test sukses, chat mati" adalah HASIL DESAIN, bukan anomali.**
  Test = probe HTTP dari HP; chat = CLI hermes di guest dengan versi/provider
  berbeda. Keduanya tidak membuktikan satu sama lain.
- **P0-7. Doctor memberi "semua ✓" palsu.** Cek binary/marker/python/PATH/probe
  HTTP — tidak satu pun membuktikan `hermes --provider opencode-free` bisa jalan
  (versi 0.19.0 lolos semua cek sambil chat mustahil).
- **P0-8. `conversationHistory` diabaikan.** Bridge one-shot `-q -Q` per pesan;
  model tidak pernah melihat pesan sebelumnya. "Chat" sebenarnya N pesan mandiri.
  Plus tiap pesan = spawn proot+hermes penuh (lambat, mahal).
- **P0-9. Base URL/model custom tidak diteruskan ke hermes.** Hanya key env
  (`buildHermesEnvironment`); hermes pakai default internalnya. Validasi dan
  eksekusi bisa beda endpoint tanpa peringatan.
- **P0-10. Protocol picker dekoratif untuk Hermes.** `dshApi` hanya mengatur
  discovery/validasi; bridge selalu pakai routing internal hermes.

## 3. Temuan P1 — keandalan & kejujuran status

- **P1-1. Tanpa timeout sesi.** `waitFor()` tak terbatas; model hang = spinner
  selamanya + `sendPrompt` terkunci (`isRunning`). Butuh watchdog (mis. 10 mnt)
  + pesan jelas.
- **P1-2. Banner/warning hermes jadi "jawaban AI".** Tiap baris stdout =
  `AssistantDelta` tanpa filter (`Warning: Unknown toolsets...`, teks setup).
- **P1-3. `persistMessages()` tiap event** (termasuk tiap baris stream) = tulis
  file tiap baris + blok "interrupted" spekulatif. Sudah 2x crash duplikat key
  (ditambal), tapi akar kerapuhannya tetap: menulis status sementara ke
  transcript permanen setiap event.
- **P1-4. `stopSession` memancarkan `SessionCompleted`.** Pembatalan user
  tercatat sebagai sukses.
- **P1-5. `respondToApproval` no-op.** One-shot `-Q` tak mendukung approval
  interaktif; alur ToolRequested/approval di UI mati untuk Hermes.
- **P1-6. Marker bermakna ganda.** Installer tulis skema sendiri (`0.1.0`),
  `repairHermesMarker` tulis versi aktual (`0.19.0`). Pemeriksaan versi/update
  tidak punya acuan tunggal.
- **P1-7. File `runtime-output-*.log` menumpuk di cache** (30+ terlihat) tanpa
  rotasi/pembersihan.
- **P1-8. `verifyGuest` hanya cek `--version`**, bukan kemampuan provider.

## 4. Minor / observasi

- Fallback provider tak valid → diam-diam jadi ANTHROPIC (`loadProvider`).
- `opencodeSessionId` stabil tapi tidak dipakai hermes (hermes baru kelola sesi
  sendiri) — dua mekanisme sesi yang membingungkan.
- `retryWithNextApiKey` hanya relevan untuk keyed providers; flap 503 upstream
  tanpa retry di level sesi.
- Peringatan muse-spark-vs-FREE sudah ada (15de6b4) — benar, pertahankan.

## 5. Rencana perbaikan ke depan (TIDAK dieksekusi di sesi ini)

- **Tahap 1 — buka jalan buntu:** install guest dari tarball upstream pin-SHA
  (d62716c…, terverifikasi keyless) + fallback PyPI; entri HERMES di
  `checkAgentUpdates` + `updateHermes` memakai rantai install yang sama;
  toast jadi denylist; tunggu file output ada sebelum dibaca (batas ~10 dtk);
  CUSTOM disembunyikan untuk Hermes sampai mapping custom diimplementasikan
  (tulis config guest dari app); doctor cek `hermes --version` + `opencode-free`
  didukung.
- **Tahap 2 — jujurkan status:** Test end-to-end opsional via CLI guest
  ("Test via Hermes") atau label jujur "Test = HTTP saja"; filter banner dari
  chat; timeout sesi + watchdog; `stopSession` → event `SessionCompleted` hanya
  bila memang selesai (atau event batal baru).
- **Tahap 3 — arsitektur:** sesi hermes persisten atau rangkuman history agar
  multi-turn nyata; app jadi satu-satunya penulis config provider guest
  (base/model/key); rotasi log cache; satukan makna marker (pakai versi aktual
  saja); pertimbangkan mode interaktif bila approval tools diinginkan.

## 6. Putaran 2 — wiring, kapabilitas, onboarding (temuan baru)

- **P0-11. Event Hermes TIDAK PERNAH dikoleksi — ini kemungkinan penyebab #1
  "chat tidak ada respon".** `HermesRuntimeBridge._events` =
  `MutableSharedFlow(replay=0)`; kolektor yang ada hanya dsh (MainViewModel:319),
  antigravity (:320), claude (:968). `grep hermesRuntime` = hanya deklarasi
  (:252–253), nol `collect`. Akibat: `SessionStarted/AssistantDelta/
  SessionCompleted/SessionFailed` dari Hermes DIBUANG diam-diam — jawaban tidak
  akan tampil, `isRunning` terkunci selamanya, toast gagal tidak pernah muncul,
  BAHKAN bila guest hermes + provider sudah sempurna. Satu baris
  `hermesRuntime.events.collect(::onRuntimeEvent)` adalah fix leverage tertinggi.
  (Jejak yang konsisten: transcript berisi blok "interrupted" kosong dengan
  workedMillis besar = ditulis `persistMessages` saat kirim/ganti chat, bukan
  dari event.)
- **P0-12. Kapabilitas fiktif.** Hermes mendeklarasikan `INTERACTIVE_APPROVALS`
  + `RESUME` (AgentDriver:88–94), tapi `respondToApproval` no-op dan tidak ada
  resume/session-persist (`saveAgentConversation` hanya dipakai ANTIGRAVITY).
  UI menjanjikan yang tidak ada.
- **P0-13. DSH & Claude menyuntik `<conversation_history>` ke prompt;
  Hermes tidak.** (DshRuntimeBridge:520–571 vs Hermes: history hanya di
  signature:65, tak dipakai.) Kesenjangan spesifik-Hermes, terkonfirmasi
  perbandingan langsung.
- **P0-14. Onboarding tidak menginstall.** Pilih Hermes di setup =
  `selectAgent` saja (PocketDevApp:253); tidak ada `installAgent` otomatis.
  Pengguna bisa masuk chat dengan agen belum terinstall → gagal "not installed".
- Settings → Coding agent: tap baris agen = `installAgent` = bila terinstall
  hanya switch (SettingsScreenModern:317 + MainViewModel:1295–1298). Tidak ada
  affordance reinstall di mana pun — P0-2 terkonfirmasi dari sisi UI juga.
- `ensureAgentInstalled` + dispatch HERMES → `ensureHermesInstalled` ada dan
  benar (RuntimeInstaller:282,438) — jalur install pertama baik; yang hilang
  hanya reinstall/update + koleksi event.

## 7. Putaran 3 — validasi ulang klaim (tidak ada yang gugur)

- P0-1: `hermes --version` guest 0.19.0 (marker HP) vs plugin lahir 13 Agu /
  keyless 20 Agu — valid.
- P0-3: filter allowlist masih di MainViewModel:3203–3210 — valid.
- P0-4: `getInputStream()=FileInputStream(outputFile)` (NativeSpawn:20) dibuka
  segera di `streamHermesOutput` (:242) sementara native membuat file belakangan
  — valid, cocok dengan ENOENT 23:08:05.
- P0-5/P0-9/P0-10: `mapProvider` null→gagal (:199–215), env hanya key (:218–225)
  — valid.
- P0-6/P0-7: Test & doctor HTTP-only — valid (ProviderApiClient, doctor
  MainViewModel:1590–1648).
- P0-8: history tak dipakai — valid (satu-satunya kemunculan = signature).
- Crash-fix `9591a18` terkomit: filter `interrupted-` di persist (:3292) +
  timestamp (:3298) + guard load (AppPreferences:422–426) — valid.
- Cakupan uji: hanya `HermesGuestScriptsTest` (skrip install); tidak ada uji
  bridge/event/UI. CI punya job unit-test — patch nanti wajib hijau.
- Klaim yang DIKOREKSI dari sesi kemarin: "tes FRESH-HOME Mac" tidak valid
  (`HERMES_HOME` menunjuk config asli); "marker hilang" salah path (marker ada
  di root rootfs, bukan `/root/`); FileNotFoundException cache = race, bukan
  direktori hilang (`mkdirs` sudah ada di NativeSpawn:64).

Prioritas eksekusi bila diperintah: P0-11 (satu baris collect) → P0-1+P0-2
(install tarball + update-check) → P0-3 (denylist toast) → P0-4 (tunggu file) →
sisanya sesuai Tahap 1–3 di seksi 5.

## Bukti

- Log HP: `E/HermesBridge: ... no API keys or providers found` + `ENOENT
  runtime-output-99091745620136.log` (23:08:05, via `adb logcat`).
- `curl https://pypi.org/pypi/hermes-agent/json` → 0.19.0, upload 2026-07-20.
- `git log --follow plugins/model-providers/opencode-free` (~/src/hermes-agent):
  lahir `28a9b6c5` 2026-08-13, keyless `ca06b87689` 2026-08-20.
- `hermes --version` Mac: v0.21.1 (dev, keyless OK).
- Kode: rujukan baris di seksi 1–3, HEAD Mobile-Harness `15de6b4`, tree bersih.
