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

The build requires JDK 11: Gradle 7.6 and the Android Gradle Plugin 7.4 don't support newer ones.


## Next steps

See [DEVELOPMENT.md](DEVELOPMENT.md) for signing (including how to create a keystore),
the CI build, and how to get logs and crash reports off a device.
