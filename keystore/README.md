# Signing key

The Android signing key lives here as `lanfps.keystore` and is **deliberately
not committed** — `*.keystore` is in `.gitignore`.

Nothing breaks if it is missing:

* `./gradlew :client-android:assembleRelease` still succeeds and produces
  `client-android-release-unsigned.apk`. An unsigned APK cannot be installed on
  a phone, so generate a key if you want an installable build.
* `scripts/build-release.sh` and `scripts/build-release.bat` generate one
  automatically on first run.
* CI generates a throwaway key, so the APK attached to every green build **is**
  signed and installable.

To create one by hand:

```bash
keytool -genkeypair -v \
  -keystore keystore/lanfps.keystore \
  -alias lanfps -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass lanfps -keypass lanfps \
  -dname "CN=LAN FPS, OU=LAN, O=LAN FPS, L=LAN, S=LAN, C=LT"
```

The password `lanfps` is hard-coded in `client-android/build.gradle.kts`. That is
fine for a game you install by copying an APK over USB on your own LAN. It is
**not** a key you would ever publish an app with — if this project ever goes to a
store, replace this with a real key kept outside the repository and injected
through environment variables or a secrets store.
