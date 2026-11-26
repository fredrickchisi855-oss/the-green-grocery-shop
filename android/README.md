# Android application — THE GREEN GROCERY SHOP

This is the Android-first offline application. It packages the shop interface as local WebView assets and stores shop data in native SQLite.

## Build with Android Studio

1. Install Android Studio and Android SDK Platform 35.
2. Open the **android** folder as the project.
3. Allow Android Studio to download the Android Gradle plugin and dependencies.
4. Select **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

The first build needs internet access for Android build tools and Gradle dependencies. The packaged app itself has no INTERNET permission and uses local HTML/CSS assets.

## Automated GitLab validation

The repository CI configuration runs:
- A Node.js syntax check for every embedded JavaScript block in the packaged HTML.
- An Android debug APK build using Android SDK 35 and Gradle 8.9.

A successful pipeline will publish `app-debug.apk` as a downloadable job artifact for seven days. A committed CI configuration is not proof of a successful build; check the pipeline result and artifact before installing.

## Implemented foundation

- Existing POS HTML is packaged at `app/src/main/assets/www/index.html`.
- Packaged HTML no longer depends on Tailwind CDN or Google Fonts.
- `ShopDatabase` installs the shared SQLite schema and stores the current POS snapshot.
- Native owner setup and owner/cashier login use salted PBKDF2-derived hashes. Existing prototype SHA-256 hashes are upgraded after a successful login.
- Five failed login attempts trigger a 30-second in-memory lockout for that username.
- Cashier creation checks the currently authenticated native owner session.
- Logout clears the native session; sensitive snapshot writes require an active native session.
- WebView navigation is restricted to packaged local app assets. File-origin access to other files and universal cross-origin access are disabled.

## Important limitations before real shop use

- The current POS still stores its working data as a JSON compatibility snapshot. The normalized tables are installed, but sales, stock movements and checkout are not yet fully mapped to atomic normalized database transactions.
- Role checks in the HTML improve interface behavior, but they are not a substitute for moving every sensitive business operation into native code or a trusted local service.
- The login lockout is in-memory and resets when the app process restarts. Add persistent throttling and owner recovery controls before production.
- Cashier management currently supports creation only; edit, deactivate and PIN reset screens are not complete.
- The offline CSS still needs a full pass to reproduce all original utility styles.
- Camera barcode scanning, receipt printing, backup/restore and Wi-Fi sync are not complete.
- No APK should be described as device-tested until a successful build artifact has been installed and exercised on an Android device.

## Data preservation

Future schema changes must use additive SQLite migrations. Do not delete or recreate the database to apply an update, because that would erase shop records.


Automated Android APK builds run through GitHub Actions on changes under `android/`.
