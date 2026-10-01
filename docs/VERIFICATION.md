# Verification record

Executed on 12 September 2026 using the installed Android toolchain, JDK 17 and an Android 14 / API 34 ARM64 emulator.

| Check | Result |
|---|---|
| Debug APK assembly | Passed |
| Kotlin domain unit/scenario tests | 10 passed |
| Android client HTTPS integration and decoder tests | 5 passed |
| Backend provider/server tests | 8 passed |
| Android device tests | 3 passed |
| Android lint | Passed: 0 errors; 29 non-blocking dependency/style/tooling warnings |

**26 tests passed.** Device tests exercise the complete five-drop-off demo through all seven arrival confirmations (including the depot return), Room's last-20 retention and deletion, and actual Android Keystore encryption/decryption with a ciphertext-at-rest check.

The final 50-drop-off domain test also includes the depot and a separate fixed final destination (52 unique locations). It completed the optimization in **362 ms on the development Mac**, under the five-second test budget. This measures the optimizer only, not networking or Android device performance. The domain runs off the UI thread; network latency and Google's quota can dominate live planning time.

The verified synthetic example produces `0 → 2 → 4 → 1 → 3 → 5 → 6 → 0`, reducing 20 km / 40 min / 3,600 g CO₂ to 12 km / 24 min / 2,160 g CO₂. Estimated savings are 8 km, 16 min and 1,440 g CO₂ with the 180 g/km factor.

The delivered debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

SHA-256:

```text
3cdc9e623ef84913d4eca5544e0fa48eab854bf7d2963c26ad6eba1b75a4c7c4
```

## Scope of verification

- Google calls were tested against injected provider response fixtures. No paid Google API account or live backend was provisioned.
- Android's backend client was exercised over HTTPS using a local test certificate, including error and redirect handling. Production certificate trust is unchanged.
- Device tests cover offline UI behavior, persistence and Keystore. Live map rendering, GPS permission/device behavior, and external Google Maps navigation require real-device/live-provider acceptance testing after configuration.
- The APK is a debug development build, not a signed store release. The release build and store submission were not performed.
- Emissions are model outputs, not measured reductions. Demo distances and polylines are explicitly synthetic.
- The app currently supports English, a light theme, and one foreground route session; there is no route replay after process death.

## Reproduce

```sh
./gradlew :domain:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
cd backend
node --test
```

Gradle HTML reports are under `domain/build/reports/tests/test`, `app/build/reports/tests/testDebugUnitTest`, and `app/build/reports/androidTests/connected/debug`. The lint report is `app/build/reports/lint-results-debug.html`.
