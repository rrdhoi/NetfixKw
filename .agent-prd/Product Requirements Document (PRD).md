# Product Requirements Document (PRD)
## Aplikasi Katalog Film Android/iOS

**Versi:** 1.0  
**Status:** Draft siap implementasi  
**Pemilik produk:** Project Manager  
**Target implementasi:** Maksimal 4 jam untuk versi pengujian  
**Platform:** Android dan/atau iOS sesuai kemampuan tim  
**Sumber brief:** `TestMobileDeveloper.pdf`

---

## 1. Ringkasan Produk

Aplikasi ini merupakan aplikasi katalog film yang memungkinkan pengguna melihat daftar film, membuka informasi detail, mencari film secara langsung, dan menyimpan film pilihan ke daftar favorit pada penyimpanan lokal perangkat. Aplikasi menggunakan **OMDb API** sebagai sumber data film.

Produk diprioritaskan untuk membuktikan kemampuan implementasi mobile dalam waktu terbatas. Karena itu, pengalaman inti harus sederhana, stabil, cepat dipahami, dan dapat diuji tanpa proses login atau backend tambahan.

> **Prinsip utama:** selesaikan alur katalog film end-to-end terlebih dahulu sebelum menambahkan fitur kreatif.

---

## 2. Latar Belakang dan Masalah Pengguna

Pengguna membutuhkan cara sederhana untuk menemukan informasi film tanpa harus membuka banyak sumber. Dalam versi minimum, pengguna harus dapat melihat koleksi film, memahami informasi penting sebuah film, menemukan film berdasarkan kata kunci, dan menyimpan film yang ingin dilihat kembali.

Brief pengujian menekankan penyelesaian aplikasi dalam waktu empat jam. PRD ini menerjemahkan brief tersebut menjadi scope yang terukur agar implementasi AI tidak melebar dan dapat menghasilkan artefak yang dapat dijalankan.

---

## 3. Tujuan Produk

| ID | Tujuan | Indikator keberhasilan |
|---|---|---|
| G-01 | Menyediakan katalog film dari OMDb API | Daftar film berhasil ditampilkan dalam kondisi jaringan normal |
| G-02 | Memungkinkan pengguna memahami sebuah film | Halaman detail menampilkan informasi film yang relevan |
| G-03 | Mempercepat penemuan film | Pengguna dapat mencari berdasarkan judul secara live search |
| G-04 | Memungkinkan penyimpanan film pilihan | Film favorit tetap tersedia setelah aplikasi ditutup dan dibuka kembali |
| G-05 | Menunjukkan kualitas engineering | Aplikasi menggunakan arsitektur/pola desain yang jelas dan repository terdokumentasi |
| G-06 | Menghasilkan deliverable yang dapat diuji | APK hasil kompilasi dan link repository tersedia untuk dikirim |

### 3.1. Sasaran yang Tidak Termasuk

Versi pertama **tidak** mencakup login, sinkronisasi akun, backend sendiri, rating pengguna, komentar, pembayaran, streaming film, atau sinkronisasi favorit lintas perangkat. Fitur-fitur tersebut dapat dipertimbangkan setelah scope pengujian inti selesai.

---

## 4. Persona dan Use Case

### 4.1. Persona Utama

**Pengguna pencari film** adalah orang yang ingin mencari judul film, membaca ringkasan singkat, dan menandai film yang ingin diingat. Pengguna tidak perlu membuat akun dan mengharapkan antarmuka yang langsung dapat digunakan.

### 4.2. Use Case Utama

1. Pengguna membuka aplikasi dan melihat daftar film.
2. Pengguna mengetuk salah satu film untuk membuka detailnya.
3. Pengguna mengetik kata kunci pada kolom pencarian dan melihat hasil yang diperbarui.
4. Pengguna menekan ikon favorit pada daftar atau halaman detail.
5. Pengguna membuka tab/halaman favorit dan melihat film yang tersimpan.
6. Pengguna menghapus film dari favorit.
7. Pengguna mencoba kembali setelah terjadi error jaringan dan memperoleh pesan yang jelas.

---

## 5. Ruang Lingkup MVP

### 5.1. Fitur Wajib

| ID | Fitur | Prioritas | Deskripsi |
|---|---|---:|---|
| F-01 | Daftar film | P0 | Menampilkan daftar film dari API dengan target awal 20 item atau sebanyak hasil valid yang tersedia. |
| F-02 | Detail film | P0 | Menampilkan informasi rinci film yang dipilih. |
| F-03 | Live search | P0 | Memperbarui hasil pencarian ketika pengguna mengetik kata kunci. |
| F-04 | Favorit lokal | P0 | Menambah, melihat, dan menghapus film favorit menggunakan penyimpanan lokal. |
| F-05 | Navigasi utama | P0 | Menyediakan akses yang jelas ke daftar film, pencarian, dan favorit. |
| F-06 | State UI | P0 | Menangani loading, kosong, error, dan sukses secara eksplisit. |
| F-07 | Dokumentasi repository | P0 | README menjelaskan aplikasi, arsitektur, cara menjalankan, API, dan screenshot. |
| F-08 | APK release/debug yang dapat dipasang | P0 | Menyediakan hasil kompilasi untuk pengujian. |

### 5.2. Fitur Tambahan Berdasarkan Kreativitas

Fitur tambahan hanya boleh dikerjakan setelah seluruh fitur P0 selesai dan diuji. Rekomendasi urutan prioritas adalah:

1. **Pull-to-refresh** untuk memuat ulang daftar.
2. **Filter/sort sederhana** berdasarkan tahun atau rating.
3. **Empty state yang informatif** dengan tombol untuk mencoba kembali.
4. **Dark mode** apabila tidak mengganggu stabilitas.
5. **Pagination atau load more** jika API dan waktu memungkinkan.

Fitur tambahan bukan bagian dari kriteria kelulusan minimum. Fitur tambahan tidak boleh mengorbankan fitur wajib, dokumentasi, atau deliverable APK.

---

## 6. Persyaratan Fungsional

### 6.1. Daftar Film

- Saat halaman utama dibuka, aplikasi menampilkan indikator loading.
- Aplikasi memanggil OMDb API menggunakan parameter pencarian yang telah dikonfigurasi.
- Aplikasi menampilkan maksimal 20 film pada pemuatan awal.
- Setiap item minimal menampilkan poster, judul, dan tahun rilis.
- Jika poster tidak tersedia atau gagal dimuat, aplikasi menampilkan placeholder yang konsisten.
- Pengguna dapat mengetuk item untuk membuka halaman detail.
- Jika API mengembalikan hasil kosong, aplikasi menampilkan empty state.
- Jika permintaan gagal, aplikasi menampilkan error state dan opsi coba lagi.

### 6.2. Detail Film

Halaman detail minimal menampilkan:

| Informasi | Wajib |
|---|---:|
| Poster atau gambar utama | Ya |
| Judul film | Ya |
| Tahun rilis | Ya |
| Genre | Ya jika tersedia dari API |
| Durasi | Ya jika tersedia dari API |
| Rating | Ya jika tersedia dari API |
| Sinopsis/plot | Ya jika tersedia dari API |
| Sutradara | Ya jika tersedia dari API |
| Aktor | Ya jika tersedia dari API |
| Tombol tambah/hapus favorit | Ya |

Status tombol favorit harus merefleksikan status penyimpanan lokal saat halaman dibuka. Perubahan status harus langsung terlihat oleh pengguna.

### 6.3. Live Search

- Kolom pencarian dapat diakses dari halaman daftar film.
- Hasil pencarian diperbarui berdasarkan input judul.
- Aplikasi tidak boleh mengirim request untuk input kosong.
- Implementasi dianjurkan menggunakan **debounce** sekitar 300–500 ms agar tidak mengirim request pada setiap karakter secara berlebihan.
- Saat pencarian berlangsung, aplikasi menampilkan loading yang tidak menghilangkan konteks pencarian.
- Hasil kosong menampilkan pesan bahwa film tidak ditemukan.
- Pengguna dapat menghapus kata kunci dan kembali ke daftar awal.
- Error pencarian menampilkan pesan yang dapat dipahami dan tombol coba lagi.

### 6.4. Favorit Lokal

- Pengguna dapat menambahkan film ke favorit dari daftar atau detail film.
- Pengguna dapat menghapus film dari favorit.
- Daftar favorit disimpan pada storage lokal perangkat.
- Data favorit tetap tersedia setelah aplikasi ditutup dan dibuka kembali.
- Film yang sama tidak boleh tersimpan dua kali.
- Identitas unik film harus menggunakan `imdbID` atau identifier stabil dari API, bukan hanya judul.
- Jika belum ada favorit, aplikasi menampilkan empty state dan arahan singkat.
- Daftar favorit dapat membuka halaman detail film.

### 6.5. Navigasi

Implementasi dapat menggunakan bottom navigation, tab, atau pola navigasi lain yang konsisten. Minimum tersedia:

- **Home/Movies:** daftar film awal.
- **Search:** pencarian film.
- **Favorites:** daftar film favorit.

Nama dan bentuk navigasi boleh disesuaikan dengan platform, tetapi pengguna harus dapat mencapai ketiga area tersebut tanpa langkah yang membingungkan.

---

## 7. Persyaratan Nonfungsional

| Area | Persyaratan |
|---|---|
| Performa | UI tetap responsif selama request jaringan dan pemuatan gambar. |
| Stabilitas | Aplikasi tidak crash ketika API gagal, hasil kosong, poster rusak, atau data detail tidak lengkap. |
| Usability | Alur utama dapat dipahami tanpa tutorial. Tombol dan status memiliki label atau ikon yang jelas. |
| Responsif | Layout menyesuaikan ukuran layar mobile dan orientasi yang didukung. |
| Keamanan | API key tidak ditulis di README, screenshot, atau source code yang dipublikasikan. Gunakan konfigurasi lokal/build config sesuai framework. |
| Offline behavior | Favorit lokal tetap dapat dibuka tanpa jaringan. Daftar dari API menampilkan pesan offline jika data belum tersedia. |
| Maintainability | Kode dipisahkan antara UI, state/presentation, domain/model, data/API, dan storage sejauh wajar untuk scope empat jam. |
| Accessibility | Kontras, ukuran teks, touch target, dan content description dasar diperhatikan. |
| Dokumentasi | README menjelaskan setup, arsitektur, konfigurasi API key, pengujian, dan screenshot. |

---

## 8. Rekomendasi Arsitektur dan Teknologi

### 8.1. Pola Desain

Rekomendasi default adalah **MVVM** karena cocok untuk aplikasi dengan UI reaktif, pemanggilan API, state loading/error/success, dan penyimpanan lokal. Tim boleh menggunakan MVP atau pola lain apabila lebih dikuasai, tetapi keputusan tersebut harus dijelaskan dalam README.

Struktur logis yang disarankan:

```text
Presentation/UI
  └── Screen, Component, ViewModel/Controller, UI State
Data
  ├── Remote Data Source: OMDb API client
  ├── Local Data Source: Local favorites storage
  └── Repository: abstraction for remote and local data
Domain/Model
  └── Movie, MovieSearchResult, FavoriteMovie
```

### 8.2. Prinsip Implementasi

- UI tidak memanggil HTTP client secara langsung.
- Repository menjadi satu pintu untuk mengambil data film dan mengelola favorit.
- ViewModel atau presenter mengubah hasil repository menjadi state UI.
- Model API dipisahkan dari model tampilan jika perbedaan struktur cukup besar.
- Error API, parsing, timeout, dan storage dipetakan ke pesan UI yang aman.
- Komponen daftar film digunakan kembali pada Home, Search, dan Favorites.

### 8.3. Kontrak Integrasi API

Aplikasi menggunakan endpoint dan aturan resmi OMDb API sesuai dokumentasi dan akses API key yang tersedia. Tim implementasi wajib memvalidasi nama parameter, batas hasil, format respons, dan status error pada saat coding.

Secara konseptual diperlukan dua operasi:

1. **Search movies:** mencari film berdasarkan kata kunci judul.
2. **Get movie detail:** mengambil detail berdasarkan `imdbID`.

Respons API harus dipetakan ke state berikut:

```text
Idle → Loading → Success(data)
                 └→ Empty
                 └→ Error(message)
```

API key harus disediakan melalui konfigurasi environment atau mekanisme rahasia platform. Nilai asli tidak boleh dimasukkan ke repository publik.

---

## 9. Alur Pengguna

### 9.1. Alur Melihat Film

1. Pengguna membuka aplikasi.
2. Aplikasi memuat daftar film.
3. Pengguna melihat poster, judul, dan tahun.
4. Pengguna mengetuk film.
5. Aplikasi menampilkan detail film.
6. Pengguna dapat menambahkan film ke favorit.

### 9.2. Alur Pencarian

1. Pengguna membuka Search atau mengetuk ikon pencarian.
2. Pengguna mengetik judul film.
3. Aplikasi menunggu debounce singkat.
4. Aplikasi memanggil API dan menampilkan hasil.
5. Pengguna memilih film dari hasil.
6. Aplikasi membuka detail film.

### 9.3. Alur Favorit

1. Pengguna mengetuk ikon favorit pada film.
2. Aplikasi menyimpan atau menghapus film dari local storage.
3. Ikon berubah sesuai status terbaru.
4. Pengguna membuka Favorites.
5. Aplikasi menampilkan semua film tersimpan.
6. Pengguna dapat membuka detail atau menghapus film.

---

## 10. Spesifikasi UI/UX

Gaya visual mengikuti referensi pada PDF secara umum: aplikasi katalog sederhana dengan app bar, daftar vertikal, poster di sisi kiri, informasi ringkas di sisi kanan, dan tombol favorit yang mudah ditemukan.

### 10.1. Komponen Minimum

- App bar dengan judul halaman dan akses pencarian bila relevan.
- Search field dengan tombol clear.
- Movie card/list item.
- Poster dengan placeholder.
- Loading indicator atau skeleton.
- Empty state.
- Error state dengan tombol retry.
- Favorite icon dengan dua kondisi: aktif dan tidak aktif.
- Detail header dengan poster, judul, dan favorite action.

### 10.2. State yang Harus Dirancang

| State | Tampilan minimum |
|---|---|
| Loading | Indikator proses dan layout yang tidak terasa kosong |
| Success | Data film dan aksi yang dapat digunakan |
| Empty | Penjelasan singkat dan tindakan berikutnya |
| Error | Pesan ramah, tidak teknis, dan tombol coba lagi |
| Offline favorites | Daftar favorit lokal tetap tersedia |
| Missing image | Placeholder tanpa merusak layout |

---

## 11. Acceptance Criteria

### 11.1. Kriteria Penerimaan MVP

| ID | Kriteria |
|---|---|
| AC-01 | Setelah aplikasi dibuka pada jaringan normal, daftar film tampil atau aplikasi menampilkan error state yang dapat dipulihkan. |
| AC-02 | Daftar menampilkan hingga 20 item film dengan poster, judul, dan tahun bila data tersedia. |
| AC-03 | Mengetuk item membuka detail film yang sesuai dengan film tersebut. |
| AC-04 | Detail menampilkan poster, judul, tahun, serta informasi film lain yang tersedia dari API. |
| AC-05 | Pengguna dapat mengetik kata kunci dan melihat hasil live search tanpa perlu menekan tombol submit. |
| AC-06 | Input pencarian kosong tidak menyebabkan request pencarian yang tidak perlu. |
| AC-07 | Pengguna dapat menambah film ke favorit dari detail dan/atau daftar. |
| AC-08 | Film favorit muncul di halaman Favorites dan tidak terduplikasi. |
| AC-09 | Favorit tetap ada setelah aplikasi direstart. |
| AC-10 | Pengguna dapat menghapus film dari favorit dan perubahan terlihat segera. |
| AC-11 | API error, empty result, loading, dan poster tidak tersedia ditangani tanpa crash. |
| AC-12 | Repository memuat README dengan deskripsi, fitur, arsitektur, setup, API key configuration, screenshot, dan instruksi build. |
| AC-13 | APK berhasil dipasang dan menjalankan alur utama pada perangkat/emulator target. |

### 11.2. Definition of Done

Pekerjaan dianggap selesai apabila semua kriteria penerimaan P0 terpenuhi, aplikasi dapat dijalankan dari repository bersih mengikuti README, tidak ada API key rahasia di repository, APK telah diuji pada minimal satu perangkat atau emulator, dan screenshot utama telah ditambahkan ke dokumentasi.

---

## 12. Rencana Pengujian

### 12.1. Skenario Fungsional

| Skenario | Ekspektasi |
|---|---|
| Membuka aplikasi dengan jaringan normal | Daftar film tampil |
| Membuka aplikasi tanpa jaringan | Error yang informatif, aplikasi tidak crash |
| Membuka detail item | Detail sesuai film yang dipilih |
| Mencari judul valid | Hasil relevan tampil |
| Mencari kata acak | Empty state tampil |
| Menghapus query | Daftar awal atau state default kembali |
| Menambah favorit | Film muncul di Favorites |
| Menambah film yang sama dua kali | Hanya satu entri tersimpan |
| Menghapus favorit | Film hilang dari Favorites |
| Restart aplikasi | Favorit masih tersedia |
| Poster tidak tersedia | Placeholder tampil dan layout tetap rapi |
| Respons API gagal/parsing error | Pesan error dan retry tampil |

### 12.2. Pengujian Visual

Periksa kesesuaian dengan referensi PDF pada ukuran layar umum, keterbacaan judul, pemotongan teks, jarak antar item, status ikon favorit, loading state, empty state, dan error state.

---

## 13. Prioritas dan Rencana Waktu 4 Jam

| Waktu | Fokus | Output |
|---:|---|---|
| 0:00–0:20 | Inisialisasi project, dependency, konfigurasi API, dan struktur folder | Project dapat build |
| 0:20–1:10 | Model, API client, repository, dan daftar film | Data film dapat dimuat |
| 1:10–1:50 | Detail film dan navigasi | Alur list → detail selesai |
| 1:50–2:30 | Live search dan state UI | Search berhasil digunakan |
| 2:30–3:00 | Local storage dan Favorites | CRUD favorit selesai |
| 3:00–3:25 | Polishing UI, error state, dan fitur kreatif kecil bila aman | UI siap diuji |
| 3:25–3:45 | Testing manual, perbaikan bug, dan build APK | APK terverifikasi |
| 3:45–4:00 | README, screenshot, pemeriksaan source code, dan final packaging | Repository dan deliverable siap dikirim |

Apabila terjadi keterlambatan, fitur kreatif harus dihapus terlebih dahulu. Jangan mengurangi pengujian alur favorit dan validasi APK.

---

## 14. Risiko dan Mitigasi

| Risiko | Dampak | Mitigasi |
|---|---|---|
| API key belum tersedia atau tidak valid | Data tidak dapat dimuat | Sediakan konfigurasi yang jelas, validasi key di awal, dan tampilkan error yang dapat dipahami. |
| Rate limit atau API tidak tersedia | Daftar/detail gagal | Sediakan retry, timeout, dan jangan membuat request berulang tanpa kontrol. |
| Respons API tidak lengkap | Detail tampak rusak | Gunakan fallback untuk field kosong dan placeholder gambar. |
| Scope melebar karena fitur tambahan | MVP tidak selesai | Kunci fitur P0 dan kerjakan tambahan hanya pada sisa waktu. |
| Local storage berbeda antar platform | Favorit tidak tersimpan | Bungkus storage melalui abstraction/repository dan uji restart aplikasi. |
| Kunci rahasia masuk Git | Risiko keamanan | Gunakan environment/local config dan periksa repository sebelum publikasi. |
| Build membutuhkan konfigurasi platform | APK terlambat tersedia | Jalankan build awal sedini mungkin, bukan hanya di akhir. |

---

## 15. Deliverables

### 15.1. Repository GitHub

Repository minimal harus memuat:

- Source code lengkap.
- README dengan nama dan ringkasan aplikasi.
- Daftar fitur yang telah diimplementasikan.
- Teknologi dan design pattern yang digunakan.
- Struktur arsitektur singkat.
- Cara menjalankan project.
- Cara mengatur API key tanpa membocorkan nilai rahasia.
- Cara menjalankan test atau pemeriksaan bila tersedia.
- Screenshot Home/List, Detail, Live Search, dan Favorites.
- Catatan keterbatasan atau fitur yang belum diimplementasikan.

### 15.2. APK

APK harus merupakan hasil kompilasi dari commit yang tersedia di repository. Nama file sebaiknya mencantumkan nama aplikasi dan versi, misalnya `movie-catalog-v1.apk`. APK harus diuji instalasi dan pembukaan pada perangkat/emulator target.

### 15.3. Pengiriman

Sesuai brief, link repository dan APK dikirim ke alamat email yang tercantum pada brief pengujian. Pengiriman email merupakan aktivitas eksternal; isi email minimal perlu mencantumkan nama kandidat/tim, link repository, link atau lampiran APK, teknologi, dan catatan singkat cara menjalankan aplikasi.

---

## 16. Template README yang Disarankan

```markdown
# Movie Catalog

Aplikasi mobile untuk melihat, mencari, dan menyimpan film favorit menggunakan OMDb API.

## Features
- Movie list
- Movie detail
- Live search
- Local favorites
- Loading, empty, and error states

## Tech Stack
- [Framework/language]
- MVVM
- [HTTP client]
- [Local storage]

## Architecture
Jelaskan alur UI → ViewModel/Presenter → Repository → Remote/Local Data Source.

## Setup
1. Clone repository.
2. Pasang dependency.
3. Tambahkan OMDb API key melalui konfigurasi lokal.
4. Jalankan aplikasi pada emulator atau perangkat.

## Screenshots
Tambahkan screenshot List, Detail, Live Search, dan Favorites.

## Build APK
Jelaskan command build sesuai framework yang digunakan.

## Notes
Jelaskan batasan, asumsi, dan fitur tambahan bila ada.
```

---

## 17. Prompt Implementasi untuk AI Developer

Bagian berikut dapat diberikan langsung kepada AI coding agent setelah repository dibuat:

> Bangun aplikasi mobile katalog film sesuai PRD ini. Prioritaskan fitur P0: daftar film maksimal 20 item dari OMDb API, detail film, live search dengan debounce, favorit menggunakan local storage, navigasi yang jelas, serta state loading/success/empty/error. Gunakan MVVM atau pola arsitektur setara dengan pemisahan UI, ViewModel/presentation, repository, remote data source, local data source, dan model. Jangan menaruh API key di source code yang dipublikasikan. Buat UI yang sederhana, responsif, dan menyerupai referensi brief: poster di sisi kiri pada daftar, judul dan tahun di sisi kanan, serta aksi favorit pada detail. Tangani respons API yang kosong atau gagal tanpa crash. Setelah implementasi, lakukan pengujian alur list → detail → favorite, search → detail, restart → favorite tetap ada, dan error jaringan. Perbarui README dengan setup, arsitektur, screenshot, dan instruksi build APK. Jangan menambahkan fitur P1 sebelum seluruh acceptance criteria P0 terpenuhi.

---

## 18. Keputusan Produk yang Perlu Dicatat Saat Implementasi

| Keputusan | Default PRD |
|---|---|
| Design pattern | MVVM |
| Penyimpanan favorit | Local storage perangkat |
| Identitas film | `imdbID` |
| Jumlah list awal | Maksimal 20 item |
| Search | Berbasis judul dengan debounce 300–500 ms |
| Login | Tidak diperlukan |
| Backend sendiri | Tidak diperlukan |
| Fitur tambahan | Hanya setelah P0 selesai |
| API key | Environment/local configuration, tidak dipublikasikan |

Jika implementasi memilih alternatif dari default tersebut, alasan dan konsekuensinya harus ditulis di README.

---

## References

[1]: https://www.omdbapi.com/ "OMDb API — The Open Movie Database"
[2]: /home/ubuntu/upload/TestMobileDeveloper.pdf "TestMobileDeveloper — Brief Tes Android/iOS Developer"

Dokumen ini disusun berdasarkan brief pada [2] dan mengarahkan integrasi sumber data film ke [1].
