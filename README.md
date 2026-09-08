# Movie Catalog

Aplikasi mobile untuk melihat, mencari, dan menyimpan film favorit menggunakan OMDb API.

## Features
- Movie list
- Movie detail
- Live search
- Local favorites
- Loading, empty, and error states

## Tech Stack
- Kotlin
- MVI (Model-View-Intent) & MVVM principles
- Jetpack Compose
- Retrofit & OkHttp
- Room Database
- Hilt (Dependency Injection)
- Kotlin Coroutines & Flow
- Clean Architecture (Core and App modules)

## Architecture
Aplikasi dipisahkan menjadi dua modul utama: `app` dan `core`.
`core` mengatur seluruh data layer, seperti local database, remote API (OMDb API), domain models, dan repositori.
`app` mengatur presentasi UI menggunakan Jetpack Compose, viewmodels (MVI pattern), dan dependensi utama.
Alur UI: UI → ViewModel → Repository → Remote/Local Data Source.

## Setup
1. Clone repository.
2. Buka project di Android Studio.
3. Konfigurasi OMDb API key (tersedia dummy API Key di `core/build.gradle.kts` sebagai `API_KEY` menggunakan `buildConfigField`). Nilai asli dapat diisi secara lokal.
4. Jalankan aplikasi pada emulator atau perangkat nyata.

## Screenshots
<img width="250" height="500" alt="Screenshot_20260908_113247" src="https://github.com/user-attachments/assets/c062fa65-6f93-44ee-adbb-04f9e6089d1b" />
<img width="250" height="500" alt="Screenshot_20260908_113308" src="https://github.com/user-attachments/assets/4d4941f7-30da-4eff-9afd-fd519e543aba" />
<img width="250" height="500" alt="Screenshot_20260908_113320" src="https://github.com/user-attachments/assets/9945a47f-3c0c-4178-94da-82dbbbd7da3d" />



## Build APK
Jalankan perintah ini pada root folder untuk membuat APK:
```bash
./gradlew assembleDebug
```
APK akan tersedia di `app/build/outputs/apk/debug/app-debug.apk`.

## Notes
- Aplikasi menggunakan local storage via Room untuk menyimpan favorit film secara offline.
- Aplikasi sudah menerapkan live search dengan debounce (500ms) menggunakan coroutine Flow.
