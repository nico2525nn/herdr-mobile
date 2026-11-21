# Release procedure

One signed APK per ABI. Never build or attach a universal APK.

1. **Bump and document.** Set `versionCode`/`versionName` in
   `app/build.gradle.kts`; move the `CHANGELOG.md` entry to the release
   version/date; update the release notes.
2. **Verify the source.**
   ```bash
   ./gradlew test --offline
   ./gradlew :app:lintDebug --offline
   tools/check.sh
   ./gradlew :feature-home:connectedDebugAndroidTest \
     :feature-terminal:connectedDebugAndroidTest \
     :feature-settings:connectedDebugAndroidTest \
     :terminal-ghostty:connectedDebugAndroidTest
   tools/ci.sh          # isolated private Herdr session/daemon
   ```
   Do not run instrumented/connected suites concurrently on one emulator.
3. **Commit and tag before building.** The tag must point at the exact source
   that produced the artifacts; the worktree must be clean.
   ```bash
   git add -A
   git commit -m "vX.Y.Z: ..."
   git tag -a vX.Y.Z -m "vX.Y.Z: ..."
   ```
4. **Build and copy each ABI before the next build overwrites the output.**
   ```bash
   export HERDR_RELEASE_KEYSTORE_PASSWORD="$(cat ~/.config/herdr-mobile/keystore.password)"
   mkdir -p build/release
   ./gradlew :app:assembleRelease -PrequireSigning --offline
   cp app/build/outputs/apk/release/app-release.apk build/release/herdr-mobile-X.Y.Z-arm64-v8a.apk
   ./gradlew :app:assembleRelease -PrequireSigning -Pabi=armeabi-v7a --offline
   cp app/build/outputs/apk/release/app-release.apk build/release/herdr-mobile-X.Y.Z-armeabi-v7a.apk
   ```
5. **Verify signatures, version, and ABI isolation.**
   ```bash
   apksigner verify --print-certs build/release/herdr-mobile-X.Y.Z-arm64-v8a.apk
   aapt2 dump badging build/release/herdr-mobile-X.Y.Z-arm64-v8a.apk |
     grep -E '^package:|^native-code'
   unzip -l build/release/herdr-mobile-X.Y.Z-arm64-v8a.apk |
     grep 'lib/arm64-v8a/libghostty_android.so'
   ```
   Repeat for `armeabi-v7a`. The certificate must match the previous release.
6. **Daemon and installer.** Build the tarball and a checksum that uses the
   asset basename (so `sha256sum -c` works from the download directory):
   ```bash
   tools/package-daemon.sh build/release/herdr-mobile-daemon-linux-x86_64.tar.gz
   ( cd build/release &&
     sha256sum herdr-mobile-daemon-linux-x86_64.tar.gz \
       > herdr-mobile-daemon-linux-x86_64.tar.gz.sha256 &&
     sha256sum -c herdr-mobile-daemon-linux-x86_64.tar.gz.sha256 )
   git show vX.Y.Z:install.sh > build/release/install.sh
   ```
   The installer is attached exactly as tagged; never replace assets after
   publishing.
7. **Publish.**
   ```bash
   git push origin main vX.Y.Z
   gh release create vX.Y.Z --verify-tag --title "vX.Y.Z: ..." \
     --notes-file docs/release-notes-X.Y.Z.md \
     build/release/herdr-mobile-X.Y.Z-arm64-v8a.apk \
     build/release/herdr-mobile-X.Y.Z-armeabi-v7a.apk \
     build/release/herdr-mobile-daemon-linux-x86_64.tar.gz \
     build/release/herdr-mobile-daemon-linux-x86_64.tar.gz.sha256 \
     build/release/install.sh
   ```
8. **Verify the published release.** List the five assets, download the APKs
   and confirm the certificate/version/ABI, and check that `install.sh`
   resolves the daemon tarball.
