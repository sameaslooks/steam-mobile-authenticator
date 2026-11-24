# SMA — Steam Mobile Authenticator

SMA is an open-source Steam Guard mobile authenticator for Android. Unlike the official Steam app's authenticator, it lets you keep and export your secrets: back them up as a `.maFile` compatible with Steam Desktop Authenticator, or as an `otpauth` URI for password managers such as KeePassXC.

> **Disclaimer:** SMA is not affiliated with or endorsed by Valve Corporation. Steam and Steam Guard are trademarks of Valve. Use it at your own risk. Always keep your revocation code.

## Features

- **Codes** — Steam Guard codes, synchronized with Steam time. Tap to copy.
- **Confirmations** — trades, market listings and account changes. Open Steam's own details page, or accept or decline from a notification after one biometric prompt. Auto-confirmation is not supported by design.
- **Sign-in** — approve QR sign-ins and sign-in requests. Requests appear automatically while the app is open. Optional map of where a request comes from (OpenStreetMap).
- **Alerts** — background checks for new confirmations and sign-in requests: periodic (every 15, 30 or 60 minutes) or real-time (every 30 s to 5 min).
- **Accounts** — import a `.maFile` (plain or SDA-encrypted). Move the authenticator from the official Steam app with a code sent by SMS or email, or remove it with the revocation code and link a new one. Search, sort and reorder accounts. Nicknames are shown next to logins.
- **Backup** — export a `.maFile` (SDA format) or an `otpauth` URI.
- **Privacy** — connects only to Steam, plus OpenStreetMap for the sign-in map (can be turned off). No telemetry, no own servers, no auto-update.

## Download

Signed APKs are published on the [Releases](https://github.com/sameaslooks/steam-mobile-authenticator/releases) page. There is one universal APK for all devices, running on Android 9 and newer.

Verify the APK before installing:

- Compare its SHA-256 with the value in the release notes.
- Check the signing certificate with `apksigner verify --print-certs`.

## Getting started

1. Install the APK. Allow installing unknown apps for the app you open it from.
2. Unlock SMA with your fingerprint or screen lock.
3. Add an account:
   - **Import a `.maFile`** from SDA.
   - **Move it from the official Steam app.** Choose the option that matches your situation:
     - **A** — you have the phone with the Steam app.
     - **B** — you do not have the phone; sign in with your password and enter the SMS or email code.
     - **C** — remove the authenticator with your revocation code (`R#####`) and link a new one to SMA.
   - Trade holds apply after the move (Steam's current rules):

     | Option | Trade hold |
     |---|---|
     | A, B | 2 days |
     | C | 15 days |

   - The official Steam app stops generating codes for the account after the move.
4. Write down the new revocation code shown at the end of the move.
5. Export a backup of your account.

## Background alerts

Choose one mode in settings:

- **Off.**
- **Periodic:** check every 15 (default), 30 or 60 minutes.
- **Real-time:** check every 30 s, 1, 2 or 5 minutes. Runs as a foreground service with a permanent low-priority notification.

Each account can be excluded from checks with its own toggle.

Approximate battery use:

| Mode | Battery use per day |
|---|---|
| Periodic | under 0.5 % |
| Real-time, 30 s | 3–6 % |
| Real-time, 1 min | 2–4 % |
| Real-time, 2–5 min | 1–2 % |

- Android may delay periodic checks further in Doze mode.
- On Xiaomi and HyperOS devices, enable Autostart for SMA and exempt it from battery optimization.
- SMA polls Steam because Steam's push notifications are delivered only to Valve's own app.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Talk to Steam servers; OpenStreetMap tiles and Nominatim geocoding for the sign-in map |
| `CAMERA` | Scan login QR codes (optional) |
| `USE_BIOMETRIC` | Unlock the vault and approve actions |
| `POST_NOTIFICATIONS` | Show confirmation and sign-in alerts |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Run real-time alert checks |
| `RECEIVE_BOOT_COMPLETED` | Restart alert checks after a reboot |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Let background checks run reliably |
| `ACCESS_NETWORK_STATE` | Check connectivity before background requests |

## Security

- Vault encrypted with AES-256-GCM; the key is held in the Android Keystore.
- The vault key is readable in the background so alerts work while the phone is locked. Protection while locked relies on device encryption and the screen lock.
- Biometric unlock, shown on launch.
- Screenshots and screen recording are blocked.
- Android backup is disabled.
- Network access is limited to Steam, plus OpenStreetMap tile and Nominatim hosts for the sign-in map.
- The built-in WebView has no JavaScript bridges.

Report vulnerabilities privately through [GitHub Security Advisories](https://github.com/sameaslooks/steam-mobile-authenticator/security/advisories).

## Building from source

Requirements: Windows 10/11, Linux or macOS; about 3 GB of free disk space; internet access for the first build.

The build scripts download JDK 17, the Android SDK and Gradle into `.tools` inside the repository. Nothing is installed system-wide. The first run downloads about 1.5 GB.

**Windows (PowerShell):**

```
git clone https://github.com/sameaslooks/steam-mobile-authenticator.git
cd steam-mobile-authenticator
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Options: `-Debug`, `-Clean`.

**Linux / macOS:**

```
git clone https://github.com/sameaslooks/steam-mobile-authenticator.git
cd steam-mobile-authenticator
./build.sh
```

Options: `--debug`, `--clean`.

The APK is written to `dist/sma-<version>.apk`.

**Signing:** the first release build creates a local key in `.signing`. Back it up. Updates install only over an APK signed with the same key; if the key is lost, you must uninstall and reinstall (export your accounts first). Keep `.signing` out of version control.

Alternatively, open the folder in Android Studio, let it sync, and use Build > Generate Signed APK.

### Release workflow

The **Release APK** GitHub Actions workflow (started manually) builds the version set by `versionName`, runs the tests, and publishes a GitHub release `vX.Y.Z` with the signed APK. The release notes include the APK SHA-256 and the signing certificate SHA-256.

Required repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | Base64 of `.signing/release.jks` |
| `SIGNING_STORE_PASSWORD` | `storePassword` from `.signing/keystore.properties` |
| `SIGNING_KEY_ALIAS` | `keyAlias` from `.signing/keystore.properties` |
| `SIGNING_KEY_PASSWORD` | `keyPassword` from `.signing/keystore.properties` |

Create `SIGNING_KEYSTORE_BASE64` from the local key:

- Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes(".signing\release.jks")) | Set-Clipboard`
- Linux: `base64 -w0 .signing/release.jks`
- macOS: `base64 -i .signing/release.jks | tr -d '\n'`

CI must sign with the same key as local builds. Otherwise, updates over a locally built install will fail.

## Credits

- [SteamDesktopAuthenticator](https://github.com/Jessecar96/SteamDesktopAuthenticator) and [SteamAuth](https://github.com/geel9/SteamAuth)
- [SteamDatabase/Protobufs](https://github.com/SteamDatabase/Protobufs)
- [DoctorMcKay/node-steam-session](https://github.com/DoctorMcKay/node-steam-session)
- [OpenStreetMap](https://www.openstreetmap.org/copyright) map data, rendered with [osmdroid](https://github.com/osmdroid/osmdroid)
- [ZXing](https://github.com/zxing/zxing) for QR code scanning

## License

MIT, see [LICENSE](LICENSE).
