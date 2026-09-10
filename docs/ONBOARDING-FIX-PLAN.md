# Rencana Perbaikan Onboarding Step 1–2 (hasil uji perangkat 2026-09-10)

> Status: RENCANA — belum dikerjakan. Dieksekusi bertahap, tiap fase butuh uji di HP oleh owner.
> Sumber temuan: uji instal APK v1.0.3 di Infinix — terhenti di Step 2 of 3.

## Diagnosis (terverifikasi di kode)

1. **Step 2 SUDAH adaptif per agent** (`ProviderChoiceStep` memakai `providersForAgent(agentKind)`).
   Daftar AgentRouter/9router/Hermes/Custom API = persis isi `HERMES_PROVIDERS`, artinya saat uji yang aktif adalah **Hermes agent**.
   Masalahnya isi set-nya, bukan adaptivitasnya:
   - `HERMES` dan `NINE_ROUTER` dua-duanya menunjuk `http://localhost:20128/v1` — di HP, localhost = HP itu sendiri, router di Mac tak terjangkau.
   - `AGENTROUTER` tidak layak top-level (maunya di bawah Custom).
   - `CUSTOM` terkunci protokol `ANTHROPIC_GATEWAY`.
2. **Error 404 saat Continue = validasi menembak endpoint Anthropic untuk URL OpenAI.**
   `ProviderApiClient.validate()` → `messagesEndpoint()` untuk `ANTHROPIC_GATEWAY` = `$base/v1/messages`.
   Dengan base `https://opencode.ai/zen/v1` hasilnya `.../v1/v1/messages` → 404.
   Discovery lolos karena `$base/models` memang ada — makanya "find available models" berhasil tapi Continue gagal.
   Satu-satunya pemilih protokol (`dshApi` + `DshApiProtocolPicker`) hanya untuk DSH+CUSTOM dan hanya dipakai `DshRouteMapper`, tidak dipakai validasi/discovery/bridge Claude & Hermes.
3. **Step 1 menduplikasi Claude**: baris Core informatif + `AgentKind.entries` penuh termasuk `CLAUDE_CODE`. Benar secara teknis (pilih Claude = tanpa unduhan), membingungkan secara tampilan.
4. **Antigravity jalan buntu potensial**: `providersForAgent(ANTIGRAVITY)` = `emptyList()`, tidak ada penanganan list kosong di Step 2.

## Fase A — Label & jalan buntu (kecil, UI saja)

- Step 1: tandai `CLAUDE_CODE` sebagai "Included in Core" (tetap bisa dipilih, tanpa unduh tambahan). Opsi hapus dari daftar: JANGAN — Claude = default yang sudah terbukti jalan.
- Step 2 untuk Antigravity: lewati daftar provider, langsung ke `AntigravityOnboardingScreen` (OAuth) yang sudah ada.
- File: `PocketDevApp.kt` saja. Uji: satu instal ulang, lewati Step 2 untuk Antigravity.

## Fase B — Protokol untuk Custom API (inti, memperbaiki 404)

- Tambah field `customProtocol` di `ProviderProfile` (`anthropic-messages | openai-completions | openai-responses`, default `anthropic-messages` agar perilaku lama tidak berubah).
- Tampilkan `DshApiProtocolPicker` (generalisasi) di Step 3 untuk `CUSTOM` pada SEMUA agent, bukan hanya DSH.
- `MainViewModel.discoverModels` / `validateProvider`: petakan `customProtocol` → `ProviderProtocol` (GATEWAY / OPENAI_CHAT / OPENAI_RESPONSES) sebelum memanggil `ProviderApiClient`.
- Bridge Claude & Hermes: hormati protokol custom saat merakit command/env (ikuti pola `DshRouteMapper`, bukan hardcode).
- File: `Models.kt`, `PocketDevApp.kt`, `MainViewModel.kt`, `ClaudeRuntimeBridge.kt`, `HermesRuntimeBridge.kt`.
- Uji: Custom API `https://opencode.ai/zen/v1` + kunci → discovery OK → Continue lolos → satu sesi chat jalan.

## Fase C — Rapikan daftar Hermes (butuh 2 keputusan owner)

- Keluarkan `AGENTROUTER` dari top-level (dipakai via Custom). Sudah disetujui owner.
- **Keputusan 1**: `NINE_ROUTER` dihapus dari onboarding, atau baseUrl-nya dijadikan bisa diedit (diisi IP LAN Mac, bukan localhost)?
- **Keputusan 2**: entri `HERMES` (preset localhost) disembunyikan total (Hermes ikut Custom), atau dipertahankan sebagai jalan pintas?
- Rekomendasi: sembunyikan keduanya dari onboarding, pindahkan ke Settings lanjutan bila nanti dibutuhkan.
- File: `Models.kt` (+ `PocketDevApp.kt` bila baseUrl editable).

## Fase D — Penempatan OpenCode Zen (butuh 1 keputusan owner)

- `OPENCODE_ZEN` (fixed, `OPENAI_RESPONSES`, `https://opencode.ai/zen/v1`) saat ini hanya muncul di daftar DeepSeek Harness — padahal relevan untuk Claude & Hermes.
- **Keputusan 3**: tampilkan juga di daftar Claude (+ Hermes bila Fase C selesai)?
- Rekomendasi: ya untuk Claude, karena protokolnya memang untuk Claude Code.

## Keputusan owner (2026-09-10, sudah diputuskan)

1. `9router`: HAPUS dari onboarding. ✅ diterapkan di Fase C.
2. Preset `Hermes` localhost: HAPUS, ganti daftar provider ala setup Hermes desktop/CLI. ✅ diterapkan: daftar Hermes = Anthropic API, OpenCode Zen, Custom API.
3. `OpenCode Zen`: SUDAH ADA sebagai entri fixed — tinggal sesuaikan. ✅ diterapkan: masuk daftar Hermes; protokol Responses dihormati end-to-end via Fase B.

## Temuan uji ADB di HP (2026-09-11, Infinix X6873)

- Step 1–2 terverifikasi visual: badge "Included in Core", daftar Hermes =
  Anthropic API / OpenCode Zen / Custom API, subtitle Custom netral.
- Validasi Zen free-tier (`muse-spark-1.3-contributor-free`, protokol
  `openai-responses`) → HTTP 400 dengan pesan server:
  **"Error from provider (Console): OpenCode's free tier can only be used in OpenCode"**.
  Artinya model free-tier Zen dikunci untuk aplikasi OpenCode sendiri —
  klien pihak ketiga (termasuk Mobile-Harness) SELALU ditolak. Bukan bug aplikasi.
- Konsekuensi: uji validasi butuh Zen API key berbayar/langganan Go.
  Model Go/berbayar yang lapor "API key was rejected" = key yang dipakai
  tidak punya billing/langganan — ganti key, bukan kode.
- Temuan samping: tiap build CI punya signature debug berbeda (keystore
  dibuat baru tiap runner) → install `-r` selalu
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; update sideload wajib uninstall dulu.
  Opsi perbaikan: keystore debug permanen di CI (perlu keputusan owner).

## Urutan & kriteria selesai

1. Keputusan owner sudah masuk (lihat di atas).
2. Kerjakan A → uji HP → B → uji HP → C+D → uji HP. Satu fase satu commit + satu build CI.
3. Kriteria akhir: tiap agent (Claude, DSH, Hermes; Antigravity via OAuth) bisa lewat Step 1–3 sampai workspace tanpa error 404, diverifikasi di HP — bukan hanya CI hijau.
