# Building AlterInstaller APK

This is a modified version of [AlterInstaller](https://github.com/chenxiaolong/AlterInstaller)
converted from a Magisk/KernelSU module into a standalone Android APK with a UI.

## What changed

| Original | Modified |
|---|---|
| Magisk/KernelSU module (ZIP) | Standalone APK with UI |
| No Activity/UI | MainActivity with config editor and apply button |
| Build via `./gradlew zipRelease` | Build via `./gradlew assembleDebug` |
| Versioned from git tags via jgit | Static version 1.0.0 |
| AGP 9.0 + Gradle 9.3 | AGP 8.7.3 + Gradle 8.10.2 |

**All original logic is kept intact** — Main.java, AlterInstallerSerializer.java, and PackageConfig.java are unchanged.

## Requirements

- **Android Studio** (Hedgehog or newer) — recommended
- **OR** JDK 17+ and Android SDK (command line)
- Android SDK Build Tools 35
- Device must be **rooted** (Magisk/KernelSU/APatch) to apply changes

## Build steps

### Option A — Android Studio (recommended)

1. Open Android Studio
2. File → Open → select the `AlterInstaller/` folder
3. Wait for Gradle sync to complete
4. Build → Build Bundle(s) / APK(s) → Build APK(s)
5. APK will be at `app/build/outputs/apk/debug/app-debug.apk`

### Option B — Command line

```bash
cd AlterInstaller
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

For a release build (requires setting up a signing keystore):

```bash
./gradlew assembleRelease
```

## How it works

The APK adds a simple UI wrapper around the original Java logic:

1. **Config editor** — Edit the JSON config directly in the app (same format as `/data/local/tmp/AlterInstaller.json`)
2. **Save** — Writes the config to `/data/local/tmp/AlterInstaller.json` via root shell
3. **Apply Now** — Uses `app_process` with root to invoke `Main.apply()` directly against `/data/system/packages.xml`
4. **Load** — Reads the current config file from the device

### Apply command under the hood

```bash
su -c "CLASSPATH=/path/to/apk app_process / com.chiller3.alterinstaller.Main apply \
  /data/local/tmp/AlterInstaller.json \
  /data/system/packages.xml \
  /data/system/packages.xml"
```

This is the same technique Magisk modules use to invoke bundled Java code.

## Config format

```jsonc
{
    "com.example.app": {
        // Mark as installed by the Play Store
        "installer": "com.android.vending",
        // Allow only this app store to update it
        "updateOwner": "com.android.vending"
    },
    "org.videolan.vlc": {
        "installer": "com.android.vending",
        "updateOwner": "com.looker.droidify"
    }
}
```

## Notes

- Root access is required — the app will show "Root access: GRANTED/DENIED" in the log on startup
- Changes to `packages.xml` take full effect after a reboot
- The original module approach (modifying before package manager starts) is more reliable; this APK applies changes at runtime and may require a reboot to take effect
- Supports Android 12 (API 31) and newer

## License

GPL-3.0-only — same as the original project.
