# Development

See [BUILDING.md](BUILDING.md) for how to build the app.


## Logs and crash reports from a device

The app installs its own crash handler, which writes `crash_<timestamp>.txt` into its cache
directory. That directory is private, so unless "Save reports to public storage"
(Settings -> Advanced settings) is enabled, the file can only be read with `adb` (see below).

There's also "Export logcat" in Settings -> Advanced settings, which shares the logcat contents
with an app of your choosing. Both the crash reports and the logs may contain phone numbers
and contact names - check them before posting them anywhere.


### With adb

None of this needs root: `adb` runs as the `shell` user, which may read the logs.

```
adb logcat -b crash -d > crash.txt          # the crash buffer
adb logcat -d | grep -A 60 "FATAL EXCEPTION" # the same from the main buffer
```

A crash that already scrolled out of the buffer is often still in the system's dropbox
(it is cleared by a reboot):

```
adb shell dumpsys dropbox --print | grep -A 60 data_app_crash
```

The app's own crash files can be read with `run-as`, which works because the debug builds are
debuggable (it does not work with a release build):

```
adb shell run-as net.evolution515.callblocker ls cache/
adb shell run-as net.evolution515.callblocker cat cache/crash_<timestamp>.txt
```

`run-as` reaches the credential-protected data directory. The databases live in the
device-protected one (`/data/user_de/0/net.evolution515.callblocker/`), which needs root.


### With adb on the phone itself (Android 11+)

A phone can run `adb` against itself over "Wireless debugging", which is useful when there's
no computer around. Enable it in the developer options, then, in
[Termux](https://f-droid.org/packages/com.termux/):

```
pkg install android-tools
adb pair localhost:PORT          # port + code from "Pair device with pairing code"
adb connect localhost:PORT       # the port shown on the Wireless debugging screen
adb logcat -b crash -d
```

The pairing port is not the same as the port to connect to - the first is shown in the pairing
dialog, the second on the "Wireless debugging" screen itself.


### Without any tooling

Developer options -> "Take bug report" -> "Interactive report" produces a zip that contains the
full logcat, and the notification it posts when it's done can share it. It also contains a lot
of unrelated device data, so it's worth extracting the interesting part
(`FATAL EXCEPTION`, or the lines mentioning `net.evolution515.callblocker`) before sharing.


## Signing

### Debug builds

The debug builds are signed with `ci-debug.keystore` from the project root instead of the
per-machine `~/.android/debug.keystore`, so that debug APKs built anywhere (in particular by
the CI, which starts from a clean machine every time) can be installed over each other.
Android's debug credentials are fixed and public (`androiddebugkey` / `android`), so the file
is not a secret - but for the same reason it must never be used to sign anything that is
distributed to others. Debug builds are also debuggable, which gives anyone holding the file
access to the app's data through `run-as`.


### Creating a release keystore

```
keytool -genkeypair -v -keystore yacb.jks -alias yacb -keyalg RSA -keysize 4096 -validity 10000
```

Check what it contains (the alias is what goes into `KEY_ALIAS`):

```
keytool -list -v -keystore yacb.jks
```

Keep the keystore and its passwords: an APK signed with a different key can't be installed over
an existing installation, the app has to be uninstalled first (which deletes its data, so export
the blacklist beforehand). The same applies to installing over a build from F-Droid.


### Signing a local build

The release build is unsigned unless the credentials are provided, either in the environment
(`YACB_KEYSTORE_FILE`, `YACB_KEYSTORE_PASSWORD`, `YACB_KEY_ALIAS`, `YACB_KEY_PASSWORD`) or in a
`keystore.properties` file in the project root (it is git-ignored):

```
storeFile=/path/to/yacb.jks
storePassword=...
keyAlias=yacb
keyPassword=...
```

An unsigned APK cannot be installed at all - Android rejects it as invalid - so a build without
these produces an APK that is only good for checking that the release build compiles.


### Signing in the CI

Add these repository secrets (Settings -> Secrets and variables -> Actions):

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | the keystore file, base64-encoded (see below) |
| `KEYSTORE_PASSWORD` | the keystore (store) password |
| `KEY_ALIAS` | the key alias |
| `KEY_PASSWORD` | the key password |

The keystore is a binary file, so it goes in as base64. Mind the line wrapping: `base64` wraps
its output by default, and a wrapped or truncated value is the usual reason for the workflow to
reject it.

```
base64 -w0 yacb.jks                                     # Linux
base64 -i yacb.jks                                      # macOS
```
```
[Convert]::ToBase64String([IO.File]::ReadAllBytes("yacb.jks"))   # Windows PowerShell
```

`certutil -encode` produces a PEM-wrapped file rather than plain base64. The workflow strips
that wrapper, but generating the value with one of the commands above is less surprising.

Without the secrets the workflow still builds, and the release APK is left unsigned; install the
debug APK from the same artifact in that case. The artifact contains an `INSTALL.txt` saying
which of the two can be installed.


### Verifying what an APK is signed with

```
keytool -printcert -jarfile app-release-0.5.17-<build time>-<revision>.apk    # JDK only
apksigner verify --print-certs app-release-0.5.17-<build time>-<revision>.apk # build-tools
```

The certificate fingerprint of the committed debug key is
`2C:5D:C8:91:2F:C9:FF:87:14:A8:0A:61:98:00:E1:0E:12:69:A7:C8:17:2A:33:0D:B5:2C:75:3E:2B:7F:A8:43`.


## Continuous integration

The `Build` workflow (`.github/workflows/build.yml`) builds the app on every push and pull
request, and can be started manually from the Actions tab (with an option to bundle the offline
database into the APK). It attaches the APKs and their checksums to the run as an
`apk-<build time>-<revision>` artifact and the lint reports as `reports`, prints the lint
findings in the job log (the project doesn't fail the build on them), and checks that the
caller ID provider survived the manifest merge. Pushing a `v*` tag additionally publishes the APKs as a GitHub release.


## What a source may be packed in

A source hands over either the database itself or a layer on top of it, and it may hand it
over packed. What arrives is decided by looking at its first bytes rather than at its name,
because a file server calls everything `application/octet-stream` and a file name is not a
promise.

* **The database** is a set of files - a few thousand `data_slice_*.dat` and the metadata
  beside them - so the whole archive is unpacked into the directory the app reads them from.
  A **zip** is handed to the library, which has always unpacked its own; a **tar**, a
  **tar.gz** or a **gzipped file** is unpacked by the app. Folders inside the archive are
  dropped: the same database is packed flat by one command and under a folder of its own by
  the next, and the app has no use for the difference.
* **A layer** is one file, so a **plain file**, a **gzipped** one, or the one `.dat` inside a
  **tar**, **tar.gz** or **zip** is read out of it.

The database is only counted as fetched once it can be read. An archive that holds nothing
the app recognises unpacks into an empty directory, and before that was noticed it was moved
into the place of the database that worked.


## The custom provider API

A provider in the app is normally an address that is opened in a browser. One can instead be
switched to "ask an API", and then the app talks to it itself - which means the app has to know
what to send and what comes back. PhoneBlock and tellows are two it knows by name; anything else
is a *custom* API and follows the contract below.

Nothing is asked without the user asking first: the app has no business telling a server who is
calling whom, so an API is only ever consulted from the dialog about a call, after a tap.

**The request.** `GET <address>` with the number substituted into it, or - when the address has
no place for a number - `POST <address>` with a JSON body:

```
POST https://example.net/lookup
Authorization: Bearer <the token from the provider screen>
Content-Type: application/json

{"number": "+4930123456"}
```

The same placeholders the address of a search uses work here: `{number}` (`+4930123456`),
`{number00}` (`004930123456`), `{digits}` (`4930123456`), `{national}` (`030123456`) and
`{token}`. An address that contains one of them is fetched with GET; one that contains none is
asked with POST and the body above.

**Saying who we are.** The login of a provider is one of three things, and each has its own
field on the provider screen:

* *None* - nothing is sent. A key can still be written into the address itself as `{token}`,
  which is how an API that wants its key in the query is described without the app knowing
  anything about that API.
* *API token (Bearer)* - the token goes into `Authorization: Bearer <token>`.
* *User name/password (Basic)* - the user name and the password go into
  `Authorization: Basic <base64 of user:password>`. The password is kept apart from the token:
  they are two different secrets, and setting one never overwrites the other.

Both the token and the password stay on the phone and are written into a backup only when
"include passwords and tokens" is switched on.

**The answer.** JSON, and every field is optional:

```json
{
  "name": "Some Company GmbH",
  "rating": "negative",
  "category": "telemarketer",
  "score": 42,
  "comments": 17,
  "url": "https://example.net/num/4930123456"
}
```

* `name` - what the number is known as; shown where a contact name would be
* `rating` - one of `positive`, `neutral`, `negative`, `unknown`
* `category` - free text, shown as it is
* `score` - how strongly the provider feels, -100 (harmless) to 100 (certain spam)
* `comments` - how many people said something about it
* `url` - a page about the number, offered as a row of its own

An answer that isn't JSON, or a status that isn't 2xx, is reported as a failed lookup and
changes nothing about the number.

**Not implemented yet.** The provider screen stores all of this - the choice, the address, the
token - and the caller info does not offer API providers while there is no client to ask them.
The rows appear as soon as it exists; the settings do not have to be entered again.
