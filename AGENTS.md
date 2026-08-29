# Notes for agents working on this repo

This is a fork of [Yet Another Call Blocker](https://gitlab.com/xynngh/YetAnotherCallBlocker)
that adds caller ID display during incoming calls
(`CallerIdDirectoryProvider`, `CallerIdOverlay`, `CallerIdHelper`, `NumberInfoCache`).

## Always link the APK

Whenever you report a build, a merged change or a CI result to the user,
include a direct download link to the APK, not just a statement that the build passed:

```
https://github.com/blurayne/yet-another-callblocker/actions/runs/<run_id>/artifacts/<artifact_id>
```

Link the APK of the latest **successful** run for the current head, and say which commit it was
built from. Link the run page next to it, so the user can see the logs:

```
https://github.com/blurayne/yet-another-callblocker/actions/runs/<run_id>
```

Get the ids without a browser:

```
curl -s "https://api.github.com/repos/blurayne/yet-another-callblocker/actions/runs?branch=<branch>&status=success&per_page=1"
curl -s "https://api.github.com/repos/blurayne/yet-another-callblocker/actions/runs/<run_id>/artifacts"
```

The download itself needs a signed-in GitHub session (or `gh run download <run_id> -n apk`),
and the artifacts expire after 30 days - so re-link the current one instead of reusing an old link.
The `apk` artifact holds the debug APK (installable, debug-signed), the release APK
(unsigned unless the signing secrets are set) and `SHA256SUMS.txt`.

[DEVELOPMENT.md](DEVELOPMENT.md) covers signing, the CI, and how to get logs and crash reports
off a device; keep it up to date when any of that changes.

## Building

* **JDK 11.** Gradle 7.2 and AGP 7.0.3 don't support newer JDKs, and the Android command line
  tools need JDK 17+, so the SDK has to be set up *before* switching to JDK 11 (see the workflow).
* SDK packages: `platforms;android-30`, `build-tools;30.0.3`.
* `./gradlew assembleDebug assembleRelease`, `./gradlew lintDebug`.
* Some sandboxes have no Android SDK and can't reach `dl.google.com` to install one.
  Don't claim a change compiles in that case - push it and let the CI build it.

## CI

`.github/workflows/build.yml` builds on every push and pull request, and on demand
(with an option to bundle the offline database). It uploads the `apk` and `reports` artifacts,
prints the lint findings (the project never fails the build on them), checks that the caller ID
provider survived the manifest merge, and publishes a GitHub release for `v*` tags.
Release signing is optional and driven by the `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS` and `KEY_PASSWORD` secrets - see [BUILDING.md](BUILDING.md).

## Upstream

`upstream` is the GitLab repo, mirrored to `origin/master`. Keep changes rebaseable on it:
prefer additive files, keep the existing code style (slf4j logging, `Settings` getters,
`YacbHolder` wiring), and don't reformat untouched code.

## What CI cannot verify

Whether the caller ID actually appears depends on the phone app: the AOSP Dialer and the Google
phone app query remote contacts directories for numbers that aren't in the contacts, some vendor
phone apps (Samsung, MIUI) don't. That, and the overlay, can only be checked on a device.
