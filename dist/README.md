# Blocker 2.1.0 (6443)

Independent FOSS release: `com.stc214.blocker`. This does not replace
`com.merxury.blocker` or `com.merxury.blocker.debug`. Grant root access to this
new package after installation. App-private settings are not migrated automatically;
Android's IFW directory and its existing rules remain shared system state.

## Changes

- Every IFW write/delete unlocks the directory only for the operation, then sets
  it immutable regardless of its initial state. Writes publish complete UTF-8
  XML atomically. Mutation failures and relock failures are reported.
- Single selection, multi-selection and batch selection share the same path.
  Read/modify/write operations are serialized; success callbacks follow relocking.
- Mutations reread current rules and stop on read/parse failure instead of
  overwriting rules with an empty fallback. Component changes preserve unrelated
  conditional/logging rules and intent selectors, and remove duplicate selectors.
- Custom launcher, splash and settings image from the supplied `icon.jpg`.
- Independent signing identity; Release no longer falls back to a debug key.
- The privileged component-control provider is no longer exported. Third-party
  apps cannot invoke its control API; in-app component operations are unchanged.

## Build and verify (Windows)

Set JDK 21 `JAVA_HOME`, Android `ANDROID_HOME`, and optionally `GRADLE_USER_HOME`.
Build Tools 36.0.0 is used by the packaging verification script.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-release.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-release.ps1
```

Only for a **new** signing identity, pass `-InitializeSigning` once. Never
regenerate the existing key for an update. By default private signing material
lives in `%USERPROFILE%\.android-signing\blocker`, outside the repository, with
current-user-only NTFS permissions. The credential file is Windows DPAPI protected.
Back up the keystore AND retain its password in a secure password manager;
the DPAPI credential file alone is not portable to a different Windows account.
No private signing material belongs in Git or `dist`.

Check `SHA256SUMS.txt` and `signature.txt` against the delivered APK.
`test-summary.json` and `test-results.json` record available regression results.
Full log files are retained locally and excluded from Git.
See [Chinese release/audit notes](../docs/RELEASE-2.1.0.zh-CN.md) and the
[complete AdClose guide](../docs/ADCLOSE-GUIDE.zh-CN.md).

`DEVICE_VERIFICATION.txt` is a historical run on the candidate identified by its
own hash, before disabling the exported provider. It is not a device test of the
final APK. The final build passed packaging/signature/manifest checks; no device
was connected on 2026-10-17 for a fresh installation or root/UI retest.

## Limits and rollback

Protection covers normal completion, exceptions and coroutine cancellation, not
power loss, SIGKILL, kernel failure or another root program changing attributes.
Batch commits are per package; a later failure does not undo earlier packages.
An intent-based advanced rule can still block a component even after its explicit
component selector is removed. Component toggles do not erase such advanced rules.
Screenshot baseline comparison requires Linux and is excluded from Windows runs.

For source rollback, run `powershell -NoProfile -File dist/rollback.ps1` from
the repository root. It checks the reverse patch without editing. Add `-Apply`
only to reverse the source changes described in `source.patch`. Local signing
keys, phone data and installed packages are not affected.

The original/debug app remains installed independently. To stop using this release,
switch back to that app; IFW rules remain shared and are not rolled back by changing
apps. Do not uninstall a package until its app-private settings have been exported.
