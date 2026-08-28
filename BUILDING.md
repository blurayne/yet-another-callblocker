# Building

## Clone the project repo

```
git clone https://gitlab.com/xynngh/YetAnotherCallBlocker.git
```

### Clone the assets repo (optional step: allows to avoid the initial DB downloading after installation)

```
git clone https://gitlab.com/xynngh/YetAnotherCallBlocker_data.git
```

Sym-link the assets:

Linux
```
cd YetAnotherCallBlocker/app/src/main/assets/
ln -s ../../../../../YetAnotherCallBlocker_data/assets/sia .
```
Windows
```
cd YetAnotherCallBlocker\app\src\main\assets
mklink /d sia ..\..\..\..\..\YetAnotherCallBlocker_data\assets\sia
```

**or** copy the whole directory `YetAnotherCallBlocker_data/assets/sia` into `YetAnotherCallBlocker/app/src/main/assets/`.


## Build the app

Open and build the project in Android Studio or use Gradle:
```
./gradlew build
```

The build requires JDK 11: Gradle 7.2 and the Android Gradle Plugin 7.0 don't support newer ones.


## Signing the release build (optional)

The release build is unsigned unless the signing credentials are provided,
either in the environment (`YACB_KEYSTORE_FILE`, `YACB_KEYSTORE_PASSWORD`,
`YACB_KEY_ALIAS`, `YACB_KEY_PASSWORD`) or in a `keystore.properties` file
in the project root (it's git-ignored):

```
storeFile=/path/to/keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

A keystore can be created with:
```
keytool -genkey -v -keystore keystore.jks -alias yacb -keyalg RSA -keysize 4096 -validity 10000
```

Keep the keystore: an APK signed with a different key can't be installed
over an existing installation (the app has to be uninstalled first),
which also applies to the builds from F-Droid.


## Building on GitHub

The `Build` workflow (`.github/workflows/build.yml`) builds the app on every push
and pull request, and can be started manually from the Actions tab
(with an option to bundle the offline database into the APK).
The APKs are attached to the workflow run as the `apk` artifact,
together with their checksums; the lint reports are attached as `reports`.

To get signed release APKs, add these repository secrets
(Settings -> Secrets and variables -> Actions):

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | the keystore file, base64-encoded: `base64 -w0 keystore.jks` |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | the key alias |
| `KEY_PASSWORD` | the key password |

Without them the workflow still builds, but the release APK is left unsigned
(and can't be installed) - use the debug APK from the same artifact in that case.

Pushing a `v*` tag additionally publishes the APKs as a GitHub release.
