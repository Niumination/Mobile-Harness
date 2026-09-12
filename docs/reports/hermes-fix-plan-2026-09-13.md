# Rencana eksekusi Hermes — sekali jalan (2026-09-13)

Sumber: `docs/reports/hermes-chat-audit-2026-09-13.md` (3 putaran, 15 temuan).
Prinsip: ponytail — root cause, satu guard di fungsi bersama, delete > add,
tanpa sentuh DSH/Claude/AGY yang sedang bekerja kecuali swap mekanis identik.
EKSekusi menunggu trigger eksplisit ("gas") — file ini rencana, bukan eksekusi.

## Matriks konflik (semua item audit vs sistem)

| # | Perubahan | DSH | Claude | AGY | UI | CI/test | Putusan |
|---|-----------|-----|--------|-----|----|---------|---------|
| 1 | `collect` hermes di init VM | — (Flow terpisah) | — | — | + (event Hermes kini tampil) | — | AMAN |
| 2 | `getInputStream` tunggu-file (shared) | + (race laten ikut sembuh) | + | — (tidak pakai?) | — | JVM-test tak bisa (ParcelFD); verif di HP | AMAN, catat verif HP |
| 3 | Tarball pin + `HERMES_WANT` + update-check | — | — | — | + (tawaran update muncul 1x) | skrip diuji HermesGuestScriptsTest bila ada | AMAN, wart: repair→semver picu 1 tawaran |
| 4 | Toast denylist (handler bersama) | + (gagal DSH kini bersuara) | + | + | + | — | AMAN, efek lintas-agen = tujuan |
| 5 | CUSTOM disembunyikan utk HERMES | — | — | — | picker ikut hilang (hanya utk CUSTOM) | — | AMAN; profil CUSTOM-hermes lama fallback ANTHROPIC |
| 6 | History Hermes via filter bersama | — (swap mekanis, logika identik) | — (idem) | — | + (multi-turn nyata) | + ChatHistoryTest baru | AMAN |
| 7 | Timeout sesi Hermes (bridge only) | — | — | — | + (spinner ada ujung) | — | AMAN |
| 8 | Filter banner `^Warning:` (Hermes only) | — | — | — | + | — | AMAN |
| 9 | Rotasi `runtime-output-*.log` >7 hari | — | — | — | — | — | AMAN |
| 10 | Auto-install Hermes pasca-onboarding | — | — | — | + (isi jalur yang hilang) | — | AMAN, verify body finishOnboarding dulu |
| 11 | Doctor: marker==HERMES_WANT | — | — | — | + (✓ jujur) | — | AMAN |

DITOLAK (ponytail, dengan alasan): throttle persist (risiko data loss, crash
sudah sembuh); tipe event batal baru (churn, when exhaustif 1 situs — wart teks
kecil diterima); ubah kapabilitas (metadata tak dibaca UI, efek nol); config
writer custom endpoint (YAGNI setelah CUSTOM disembunyikan); approval
interaktif (mustahil di one-shot `-Q`).

## Urutan eksekusi (1 commit, 1 CI, 1 update HP)

1. `git stash drop` (WIP lama, diganti rencana ini) — catat SHA sebelum drop.
2. `runtime/NativeSpawnProcess.kt`: `getInputStream` tunggu file ≤10 dtk
   (poll 50 ms) sebelum `FileInputStream`. // ponytail: satu guard, semua bridge.
3. `ui/MainViewModel.kt`: +1 baris `hermesRuntime.events.collect` di samping
   dsh/antigravity (:319–320). (P0-11)
4. `runtime/ChatHistory.kt` (BARU, ~25 baris): `filterPriorChatMessages(history)`
   = predikat identik DSH/Claude + `dropLast(1)` + cap 20 pesan.
5. `DshRuntimeBridge.kt` + `ClaudeRuntimeBridge.kt`: ganti blok filter 8 baris
   dengan 1 panggilan (swap mekanis, tanpa ubah perilaku).
6. `HermesRuntimeBridge.kt`: (a) suntik `<conversation_history>` via helper (4)
   ke `-q`; (a2) guard ukuran: prompt >100KB → pangkas pesan tertua sampai di
   bawah cap (cegah `Argument list too long` proot); (b) timeout sesi 10 mnt → `SessionFailed("…timed out…")` + destroy;
   (c) saring baris `^Warning:` dari delta; (d) hapus log sesi >7 hari saat start.
7. `RuntimeInstaller.kt`: `HERMES_GIT_SHA=d62716c…` + tarball;
   `hermesInstallChain()` dipakai ensure DAN update; marker `HERMES_WANT`;
   entri HERMES di `checkAgentUpdates` (`current != HERMES_WANT`).
   Tripwire: bila tarball gagal install/run di guest → JANGAN diam-diam ke PyPI
   (itu = P0-1 hidup lagi); fallback ke SHA upstream stabil yang mengandung
   `ca06b87689` (cari via `git log` upstream saat eksekusi).
8. `MainViewModel.kt`: toast = tampil bila reason non-blank kecuali daftar redam
   (kosong untuk mulai — denylist, bukan allowlist); `probeConnection()` dipakai
   pingApi/validate/doctor (pangkas triplikat).
9. `model/Models.kt`: `HERMES_PROVIDERS` minus `CUSTOM` (+ komentar "add when
   custom-hermes mapping exists").
10. Onboarding: bila agen==HERMES dan belum terinstall → `installAgent(HERMES)`
    otomatis (verify body `finishOnboarding` sebelum tulis).
11. Tes: `ChatHistoryTest` (filter + cap + dropLast); pastikan
    `HermesGuestScriptsTest`, `DshBridgeTest` hijau.
12. `AGENTS.md` +1 baris riwayat. Commit 1x, push, kawal CI sampai hijau.
13. HP: `install -r`, Settings→Coding agent→Update Hermes, matriks uji:
   chat FREE/ling 2-turn (bukti history: follow-up merujuk pesan 1),
   matikan data saat chat (bukti toast), sesi >10 mnt (bukti timeout),
   doctor semua ✓. Prosedur ikut `android-adb-testing`: `logcat -c` sebelum
   tiap aksi + logcat pid-filter sesudahnya, screenshot + `runtime-output-*.log`
   diambil dalam pass yang sama, tiap langkah diverifikasi sebelum lanjut.
   Klaim "selesai" tunduk pada `verification-before-completion`: tanpa bukti
   fresh dari HP = belum selesai.

## Rollback

Satu commit → `git revert`. HP: install rilis sebelumnya. Data chat aman
(`install -r`, tanpa wipe).
