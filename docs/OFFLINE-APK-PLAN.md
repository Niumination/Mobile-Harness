# Rencana APK Offline (ala techjarves)

> Status: RENCANA — belum dikerjakan. Dieksekusi bertahap per fase dengan approval per fase.
> Dibuat: 2026-09-10. Pemilik: Afrizal Munthe.

## Kondisi saat ini

- CI hanya merilis APK **online** (`app-online-debug.apk`, ±62 MB, Release v1.0.3).
- `dist/runtime-bundles/` hanya berisi `manifest.json` — bundle `.tar.zst` tidak ada di repo maupun di Releases Niumination.
- Step curl bundle di workflow mengarah ke tag `runtime-2026.09.x` yang **tidak ada** (gagal diam-diam via `|| true`).
- Task `prepareOfflineRuntimeAssets` / `prepareBundledAgentAssets` sengaja no-op.
- Flavor `offline` ada di `app/build.gradle.kts` tapi tidak pernah di-build CI.

## Target akhir

Job CI baru merilis `app-offline-debug.apk` (±800 MB, targetSdk 36) berisi bundle Core + Python + Android, bisa setup tanpa internet (tetap butuh internet untuk API key provider dan `pip install hermes-agent`).

## Fase 0 — Inventarisasi (ringan, tanpa unduh besar)

1. Baca `dist/runtime-bundles/manifest.json`: catat nama file, versi, SHA-256 yang diharapkan.
2. Cek URL bundle upstream techjarves masih hidup atau tidak (HEAD request saja).
3. Putuskan sumber bundle: **Opsi A** (re-host bundle upstream ke Releases Niumination) atau **Opsi B** (bangun rootfs sendiri — berat, hindari bila Opsi A memungkinkan).
4. Kriteria selesai: tabel sumber × SHA-256 × ukuran terisi.

## Fase 1 — Sediakan bundle di Releases Niumination

1. Unduh 3 bundle (core ±150 MB, python ±55 MB, android ±100 MB, terkompresi) ke Mac — butuh ruang ±1 GB dan koneksi stabil.
2. Verifikasi SHA-256 tiap file cocok dengan `manifest.json`.
3. Upload ke GitHub Releases Niumination/Mobile-Harness dengan tag `runtime-2026.09.x` (nama file persis seperti yang di-curl workflow).
4. Kriteria selesai: URL curl di workflow mengembalikan HTTP 200 (cek via `curl -I`).

## Fase 2 — Hidupkan kembali task prepare

1. Kembalikan `prepareOfflineRuntimeAssets` / `prepareBundledAgentAssets` jadi task `Sync` asli (atau guard dengan `rootProject.file(...)` yang terbukti kompilasi — JANGAN `Directory.asFile.get()`).
2. Pasang ulang `dependsOn` untuk varian `offline` saja; varian `online` tetap tanpa dependensi.
3. Kriteria selesai: `git diff` hanya menyentuh `app/build.gradle.kts`, tidak ada error kompilasi script (terbukti via CI).

## Fase 3 — Job CI offline + rilis aset kedua

1. Tambah job `apk-offline` di `.github/workflows/build.yml`: checkout submodules recursive, unduh bundle (tanpa `|| true` agar gagal keras bila bundle hilang), `./gradlew -PplayBuild=true :app:assembleOfflineDebug`.
2. Upload `app/build/outputs/apk/offline/debug/app-offline-debug.apk` ke Release yang sama + artifact 7 hari.
3. Perbarui README + AGENTS.md (link unduhan, ukuran).
4. Kriteria selesai: CI hijau, Release berisi 2 APK.

## Fase 4 — Uji di perangkat (Infinix)

1. Sideload APK offline via file manager, izinkan "install unknown apps".
2. setup awal dalam mode pesawat (pastikan tidak butuh unduhan).
3. Nyalakan internet, uji `pip install hermes-agent` + satu sesi chat Hermes.
4. Kriteria selesai: checklist uji lolos, temuan dicatat di `docs/update-testing.md` bila relevan.

## Risiko

- Upload ratusan MB ke GitHub bisa gagal di tengah jalan — gunakan `gh release upload` yang bisa dilanjutkan, atau unggah per file.
- Batas file GitHub 2 GB — aman untuk ±800 MB, tapi hampir wajib Wi-Fi stabil.
- Bundle upstream bisa usang/tidak cocok — verifikasi SHA-256 wajib sebelum upload ulang.
- Waktu CI bertambah (unduh + rakit ratusan MB) — pertimbangkan hanya jalan saat tag, bukan tiap push.
