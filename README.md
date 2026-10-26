# KomikTrans

Alur: Buka gambar -> Scan+Terjemah (online/offline) -> Edit teks (salin/tempel notepad)
-> Hapus teks otomatis (+ kuas) -> Tulis terjemahan -> Simpan.

## Cara build APK
**A. Android Studio**: buka folder ini, tunggu sync, Build > Build APK(s).

**B. GitHub Actions (tanpa PC)**: unggah seluruh isi folder ini ke repo GitHub
(bisa lewat Termux: `git init`, `git add .`, `git commit`, `git push`).
Tab Actions > "Build APK" > unduh artifact `KomikTrans-debug-apk` > ekstrak > pasang app-debug.apk.

## Catatan
- OCR memakai model ML Kit bundled (jalan tanpa Google Play Services/microG).
- Terjemah Online memakai endpoint gratis tidak resmi; Offline memakai ML Kit Translate
  (model diunduh sekali; kalau gagal di microG, pakai Online).
- Gambar diperkecil maksimal 2600 px sisi terpanjang agar muat di RAM 3 GB.
