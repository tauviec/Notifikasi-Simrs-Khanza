# Notifikasi-Simrs-Khanza

Notifikasi yang memberikan informasi kalau ada permintaan Lab, Radiologi, dan Farmasi yang diorder dan belum dilayani.

Program Java kecil yang jalan di system tray Windows, connect **langsung** (read-only) ke database SIMRS Khanza, dan memunculkan notifikasi + alarm suara begitu ada permintaan Lab/Radiologi/Resep baru yang belum selesai — tanpa perlu buka browser.

Berdiri sendiri, terpisah dari aplikasi web "Notifikasi Khanza" (`C:\xampp\htdocs\notif-khanza`) — keduanya boleh dipakai bersamaan atau salah satu saja.

## Keterbatasan penting yang perlu dipahami

**Klik notifikasi TIDAK membuka Khanza langsung ke permintaan itu.** Sudah ditelusuri langsung ke source Khanza — aplikasi itu tidak punya mekanisme apa pun untuk diperintah dari luar supaya melompat ke layar/record tertentu (tidak ada command-line argument yang dibaca, tidak ada socket/IPC, tidak ada URL protocol). Klik notifikasi di sini membuka **jendela detail milik program ini sendiri**, menampilkan No. Rawat, No. RM, Nama Pasien, dan Asal (poli/ruangan) — lengkap dengan tombol **"Salin No. Rawat"** supaya tinggal paste ke kolom pencarian di Khanza.

## Cara Setup di Satu PC Klien

1. Salin seluruh folder `NotifKhanzaClient` ini ke PC klien (mis. Lab/Radiologi/Farmasi), taruh di `C:\NotifKhanzaClient\`.
2. Salin `config.properties.example` menjadi `config.properties`, lalu isi:
   - `db.host` — alamat server database Khanza (kalau PC klien ini ADA di server yang sama, isi `localhost`; kalau PC terpisah, isi IP server-nya — lihat peringatan keamanan di bawah).
   - `db.username` / `db.password` — pakai user MySQL **read-only** (lihat bagian "Keamanan" di bawah, JANGAN pakai `root`).
   - `jenis.watch` — isi sesuai PC ini mau memantau apa: `lab`, `radiologi`, `farmasi`, atau gabungan dipisah koma.
   - `farmasi.unit` — khusus untuk notifikasi Farmasi, atur unit asal resep yang mau dipantau: `ralan` (Rawat Jalan), `ranap` (Rawat Inap), atau `ralan,ranap` (default, keduanya). Mis. PC di Farmasi Rawat Jalan cukup isi `farmasi.unit=ralan` supaya tidak ikut kena notif resep dari Rawat Inap.
   - `farmasi.ralan.poli` — saring lagi resep Ralan per poliklinik asal (nama harus persis sama dengan nama poliklinik di Khanza, case-insensitive, boleh lebih dari satu dipisah koma). Kosongkan untuk semua poliklinik Ralan. Mis. `farmasi.ralan.poli=IGD` supaya notif Ralan cuma muncul untuk resep dari IGD saja, bukan semua poliklinik rawat jalan.
   - `alarm.sound.file` — sudah default ke `alarm.mp3`, file suara alarm yang sudah disertakan di folder ini. Mau ganti suara lain? Taruh file `.mp3`-nya di folder `C:\NotifKhanzaClient` juga, lalu ganti nama filenya di baris ini. Kalau dikosongkan atau filenya tidak ada, otomatis pakai beep bawaan Windows.
   - `alarm.sound.repeat` — default `3`. Berapa kali suara/beep diputar ULANG setiap kali alarm dibunyikan (beda konsep dari `alarm.repeat.minutes` — yang ini soal jumlah putaran DALAM satu kali alarm, bukan jarak antar alarm). Isi `1` kalau mau sekali putar saja tanpa berulang.
   - `alarm.repeat.minutes` — default `2`. Alarm akan bunyi ULANG tiap sekian menit selama permintaan itu MASIH belum dilayani di Khanza (bukan cuma sekali di awal) — begitu sudah selesai dilayani, otomatis berhenti diingatkan. Isi `0` untuk kembali ke perilaku lama (alarm cuma sekali).
   - `popup.auto` — default `true`. Selain alarm bunyi + balloon notifikasi kecil di pojok, jendela daftar lengkap ("Belum Dilihat") juga **otomatis muncul ke layar** tiap kali ada permintaan baru atau pengingat — dipaksa naik ke depan walau layar sedang dipakai aplikasi lain (mis. Khanza), supaya tidak kelewat. Isi `false` kalau cuma mau alarm+balloon saja tanpa jendela otomatis muncul.
3. Double-klik `start.bat`. Ikon lonceng hijau akan muncul di system tray (pojok kanan bawah, mungkin perlu diklik panah "^" untuk melihat ikon yang disembunyikan Windows).
4. Klik kanan ikon tray untuk menu "Cek Sekarang" (paksa cek manual) / "Keluar".

**Instalasi pertama kali**: semua item yang SUDAH pending saat itu (bisa ratusan) otomatis dicatat sebagai "sudah dilihat" TANPA memicu notifikasi — supaya tidak langsung membanjiri dengan ratusan popup+alarm. Hanya item yang muncul **setelah** program ini pertama kali jalan yang akan dinotifikasi (item baseline ini tetap masuk hitungan pengingat berkala di bawah, jadi kalau memang masih belum dilayani, tetap akan diingatkan setelah `alarm.repeat.minutes` menit berjalan).

**Alarm bunyi ulang selama belum dilayani**: bukan cuma sekali saat pertama muncul — tiap permintaan yang statusnya masih pending akan diingatkan lagi tiap `alarm.repeat.minutes` menit (default 2 menit), sampai statusnya selesai dilayani di Khanza. Bisa diubah/dimatikan lewat `alarm.repeat.minutes` di `config.properties`.

**Kalau beberapa permintaan baru muncul dalam satu kali cek** (mis. 5 permintaan Lab sekaligus), notifikasinya digabung jadi **satu popup ringkasan** ("5 Permintaan Baru — 5 Permintaan Lab"), bukan 5 popup terpisah — ini disengaja, karena Windows tidak menumpuk beberapa balloon notification dari ikon tray yang sama dengan baik (yang lama bisa tertimpa/hilang sebelum sempat terlihat kalau ditembak beruntun). Daftar lengkapnya tetap selalu benar dan lengkap di jendela yang muncul saat ikon tray diklik.

**Daftar "Belum Dilihat" otomatis bersih sendiri**: begitu sebuah permintaan sudah divalidasi/dilayani di Khanza, barisnya otomatis hilang dari jendela daftar ini di poll berikutnya — tidak ada tombol manual untuk menyembunyikan baris, daftar ini murni cerminan status pending yang sebenarnya di Khanza saat itu.

**Cuma memantau permintaan tanggal HARI INI** (berlaku untuk alarm DAN daftar): kalau di Khanza ada backlog lama (permintaan bertanggal lama yang entah kenapa belum pernah diisi hasilnya sampai sekarang), itu TIDAK ikut memicu alarm dan TIDAK muncul di jendela daftar — supaya notifikasi ini benar-benar fokus ke permintaan yang realtime/berjalan hari ini, bukan riwayat lama. Backlog lama itu tetap ada di database dan bisa dicek manual langsung di Khanza kalau perlu.

## Keamanan — WAJIB dibaca sebelum dipasang ke banyak PC

Kredensial database tersimpan sebagai **teks biasa** di `config.properties` di **setiap** PC klien tempat program ini dipasang — beda dengan aplikasi web yang kredensialnya cuma ada di satu server. Karena itu:

- **Selalu pakai user MySQL read-only**, jangan pernah `root`. User `notifkhanza_ro` (`GRANT SELECT ON rscnd.*`) sudah dibuat untuk aplikasi web notif-khanza — bisa dipakai ulang di sini, tapi sebaiknya buat **password yang beda** khusus untuk klien desktop ini, supaya kalau satu bocor (mis. PC klien kena malware), yang lain tidak ikut kena:
  ```sql
  CREATE USER 'notifkhanza_client'@'%' IDENTIFIED BY 'PASSWORD_BEDA_DAN_KUAT';
  GRANT SELECT ON rscnd.* TO 'notifkhanza_client'@'%';
  FLUSH PRIVILEGES;
  ```
  (`@'%'` = boleh diakses dari IP mana saja di jaringan — kalau mau lebih ketat, ganti dengan subnet spesifik jaringan RS, mis. `@'192.168.1.%'`.)
- Kalau PC klien ada di jaringan yang BEDA dari server database, port MySQL (3306) harus bisa diakses dari jaringan klien itu — cek firewall server database.
- Jangan pernah salin `config.properties` yang sudah terisi ke tempat yang bisa diakses publik (email, chat, repository publik, dsb).

## Kalau perlu kompilasi ulang

Butuh JDK yang sudah termasuk JavaFX (dites pakai **Liberica JDK 15 edisi "Full"** — edisi biasa/standard TIDAK menyertakan JavaFX, wajib pakai yang "Full"). Suara alarm `.mp3` diputar lewat modul `javafx.media` bawaan JDK ini, bukan library tambahan. Dari folder ini:
```
javac --add-modules javafx.media NotifKhanzaClient.java
jar cfe NotifKhanzaClient.jar NotifKhanzaClient NotifKhanzaClient*.class
```
`start.bat` juga sudah menyertakan flag `--add-modules javafx.media` saat menjalankan — jangan dihapus, karena tanpa flag ini modul JavaFX tidak ke-load dan suara alarm gagal diputar (otomatis fallback ke beep, tapi lebih baik tetap dengan flag-nya supaya suara .mp3 benar-benar terdengar).

## Driver database

`mysql-connector-java-5.1.39-bin.jar` — driver JDBC lama, diambil dari source SIMRS Khanza sendiri (`resumepasien/lib/`). Sudah dites langsung, kompatibel dengan MariaDB yang dipakai Khanza saat ini.

## File yang dibuat otomatis

- `seen_ids.txt` — daftar item yang sudah pernah dinotifikasi di PC ini (supaya tidak dobel). Spesifik per-PC, tidak perlu (dan sebaiknya tidak) disalin ke PC lain.
