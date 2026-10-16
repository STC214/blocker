# Final verification — 2026-10-17

Baseline source commit: `9bc75f0b7263ebd7da5a69be05b717c536abeb3b`.
Historical baseline APK/source hashes and original copies remain in the local
workspace's `diagnostics/ifw-lock-patch` and `diagnostics/brand-icon` directories.
`source.patch` is the complete source/documentation delta, excluding distribution
artifacts and the user's pre-existing `gradlew.bat` edit.

## Final build

Command: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-release.ps1`

Literal terminal summary, exit 0:

```text
BUILD SUCCESSFUL in 3m 4s
1408 actionable tasks: 13 executed, 1395 up-to-date
RELEASE_APK=F:\Project\07_Androd\13_blocker\blocker\dist\blocker-2.1.0-foss-release.apk
SHA256=f46610671260c422cfc2f9373f1af71f1a7037bfc6b86c7d35d86ddd5440f71f
```

The packaging script checks the package ID, non-debuggable manifest, APK signature
and certificate hash against the independent signing keystore. Public certificate
SHA256: `09f082030f7b119871122655d76842aa0bccb1748bcd674aaced3ace8d7831d0`.
Private credentials and key files are outside Git. `signature.txt` retains the
literal apksigner output, including its JAR/META-INF warnings; v1 and v2 verify.

## Final regression

Command: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-release.ps1 -RepeatTests`

The script invokes:

```text
gradlew.bat testFossDebugUnitTest --rerun :app-compose:lintFossRelease :app-compose:spotlessCheck :core:common:spotlessCheck :core:ifw-api:spotlessCheck :core:provider:spotlessCheck :feature:settings:impl:spotlessCheck -Proborazzi.test.verify=false --max-workers=4 --continue --console=plain --no-configuration-cache
```

Literal summary, exit 0:

```text
BUILD SUCCESSFUL in 3m 10s
2347 actionable tasks: 41 executed, 1 from cache, 2305 up-to-date
EXIT_CODE=0
suites: 96
tests: 655
failures: 0
errors: 0
skipped: 0
```

Lint report: 26 Warning, 6 Hint, no Error. Linux screenshot baseline comparison
is explicitly excluded on Windows. The 655 count is not a claim of screenshot
baseline validation or whole-application device coverage.

## Final APK checks

`aapt dump badging dist/blocker-2.1.0-foss-release.apk`, exit 0:
package `com.stc214.blocker`, versionCode `6443`, versionName `2.1.0`, minSdk 23,
targetSdk 36; no `application-debuggable` flag.

`aapt dump xmltree dist/blocker-2.1.0-foss-release.apk AndroidManifest.xml`, exit 0:
provider `com.merxury.blocker.provider.ComponentProvider` has
`android:exported=(type 0x12)0x0` and authority
`com.stc214.blocker.provider.ComponentProvider`.

Original image SHA256:
`4a795b555fe0f80f2a3ab6840d789bf39fc42b0d5c1a44478908dcaebeb579c5`.
The final optimized resource `res/rt.jpg` matches this hash byte-for-byte.
The first name-based resource lookup did not find the original filename because
release resource optimization renames it; content-hash verification succeeded.

## Device checks and rollback

`DEVICE_VERIFICATION.txt` contains literal baseline failure and successful patched
root-command outputs from the earlier candidate. Do not substitute the final APK
hash into that historical record. The final APK was rebuilt after the manifest
restriction; ADB had no connected device for final install/UI/root retesting.

`powershell -NoProfile -ExecutionPolicy Bypass -File dist/rollback.ps1` checks
reverse-patch applicability without changing source. With `-Apply`, it reverses
only the listed source changes, leaving device rules/settings/signing keys intact.

Latest check-only rollback invocation exited 0:

```text
ROLLBACK_SOURCE_CHECK=PASS
No installed apps, app settings, signing keys or system IFW rules were changed.
```

## Cleanup

Temporary helper scripts, compiled device-test classes/JARs, Python bytecode
caches and the interrupted Robolectric download were removed locally. Formal
diagnostic evidence, original backups, signing material and reusable SDK/Gradle
dependencies were preserved. Phone-side staging files await the next ADB connection;
they are not repository content and were not claimed deleted.

Generated module build directories were subsequently removed after checking that
each resolved path was inside this repository and contained no tracked files.
Cleanup exited 0 with `BUILD_OUTPUT_CLEANUP=PASS`; the temporary cleanup script
deleted itself. Release artifacts and verification records remain in `dist`.
