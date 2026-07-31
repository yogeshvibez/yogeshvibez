# Heyogesh Drive

Heyogesh Drive is a personal storage system for a Windows Documents folder. A small Node.js host safely exposes a password-protected API through Cloudflare Tunnel; the Android app browses that API, opens media through short-lived streaming links, and saves resumable downloads to Android Downloads.

The public download page is [index.html](index.html). Its APK button intentionally targets `Heyogesh_Drive.apk` in this repository root.

## Architecture

```text
 Android app / installed media player
       |  HTTPS + password session or signed 10-minute media link
       v
 https://storage.heyogesh.dpdns.org
       |  Cloudflare edge TLS, DDoS controls, no inbound router port
       v
 Cloudflare Tunnel (cloudflared service on the Windows PC)
       |  loopback-only HTTP: http://127.0.0.1:8787
       v
 host_index.js
       |-- HMAC session and media-link verification
       |-- path and symlink protection
       |-- byte-range media/file streaming
       |-- temporary ZIP archive jobs
       v
 Windows Documents folder
```

## Why HTTPS is the correct Cloudflare protocol

The public protocol is **HTTPS**. In the Cloudflare Tunnel public-hostname form, select service type **HTTP** and point it to `http://127.0.0.1:8787`. These are not contradictory:

- The app connects to Cloudflare with public HTTPS, so the password, session token, and files are encrypted across the internet.
- `cloudflared` creates its own authenticated, outbound encrypted tunnel to Cloudflare. The final short hop is loopback HTTP on the same Windows PC and is never reachable from a LAN or the public internet.
- HTTPS gives Android, external video players, image viewers, HTTP Range requests, MIME types, and downloads the standard web semantics they expect.
- TCP is a poor fit: it does not provide an HTTP API, normal URL streaming, browser/player compatibility, or Cloudflare's standard HTTPS behaviour. SSH, RDP, and UNIX are administration protocols, not file/media delivery protocols.

Do **not** bind the Node host to a public IP or make a router port-forward. The tunnel is the public boundary.

## Host installation on Windows

### 1. Install Node.js and dependencies

Install Node.js 20.11 or newer. In PowerShell, from this project folder:

```powershell
npm ci
```

`host_index.js` has only one production dependency: `archiver`, which creates ZIP64-capable folder/multiple-item downloads without loading the archive into memory.

### 2. Set host configuration

The default Windows Documents location is automatically selected. These PowerShell settings are appropriate for a first local run:

```powershell
$env:DOCUMENTS_ROOT = [Environment]::GetFolderPath('MyDocuments')
$env:PUBLIC_BASE_URL = 'https://storage.heyogesh.dpdns.org'
$env:HEYOGESH_DRIVE_PASSWORD = '2608'
$env:HEYOGESH_DRIVE_SIGNING_KEY = node -e "console.log(require('crypto').randomBytes(48).toString('base64url'))"
node host_index.js
```

The supplied application password is `2608`; the Android login screen sends the value the user enters over HTTPS to `/api/v1/auth/login`. Keep a long, random `HEYOGESH_DRIVE_SIGNING_KEY` in the service environment. Without it, the host intentionally makes a random key on boot, which invalidates sessions after a restart.

For a persistent deployment, put these four environment values in the account/service configuration that starts Node (for example, a Task Scheduler task or NSSM service), then start:

```powershell
node C:\path\to\Project\host_index.js
```

The server listens only on `127.0.0.1:8787` by default. A successful local health check is:

```powershell
Invoke-RestMethod http://127.0.0.1:8787/healthz
```

### 3. Connect Cloudflare Tunnel

1. Install `cloudflared` on the Windows storage PC.
2. In the Cloudflare Zero Trust dashboard, open **Networks → Tunnels**, select the tunnel that owns `storage.heyogesh.dpdns.org`, and add a public hostname.
3. Use subdomain `storage`, domain `heyogesh.dpdns.org`, service type **HTTP**, and URL `http://127.0.0.1:8787`.
4. Start the tunnel service with the complete tunnel-token command already provided to you. The token is deliberately not repeated or committed here; it is a machine credential. Its safe representation in documentation is:

   ```powershell
   cloudflared.exe service install [REDACTED_SECRET]
   ```

5. Verify `https://storage.heyogesh.dpdns.org/healthz` returns `{"status":"ok"}`.

Cloudflare manages the required tunnel DNS route. Do not replace it with a direct A record to the Windows PC. Keep Cloudflare's SSL/TLS mode at **Full (strict)** for normal domain traffic. The origin service is loopback-only, while Cloudflare handles the public certificate.

Avoid placing an interactive Cloudflare Access login page in front of this hostname unless the Android app is updated to authenticate to Access as well; a browser redirect would prevent installed media players from resolving the signed stream URL. The application already authenticates requests itself and signed media URLs expire quickly.

## Node API and behaviour

All `/api/v1/*` routes except login and signed `/api/v1/media` links require `Authorization: Bearer <session>`.

| Route | Purpose |
| --- | --- |
| `POST /api/v1/auth/login` | Checks the submitted password and returns a 12-hour signed session. Login attempts are rate limited. |
| `GET /api/v1/folders?path=` | Lists one Documents subfolder with metadata, folders first, and pagination. |
| `GET /api/v1/tree?path=&depth=` | Returns a bounded nested folder tree. |
| `GET /api/v1/open?path=` | Returns a short-lived signed stream URL plus an authenticated download path. |
| `GET /api/v1/media?...` | Streams supported media or images directly; supports `Range` and does not require a player to add headers. |
| `GET /api/v1/download?path=` | An authenticated attachment download with `Content-Length` and byte ranges. |
| `POST /api/v1/archives` | Starts background ZIP creation for one folder or a mixed multi-selection. |
| `GET /api/v1/archives/:id` | Polls ZIP creation progress. |
| `GET /api/v1/archives/:id/download` | Range-downloads a finished ZIP. |

The app computes speed and ETA from real received bytes and the server's `Content-Length`; archive preparation reports source bytes/files until the final ZIP is ready. The host does not buffer media or entire folders in RAM. It blocks path traversal and symbolic links, rejects paths outside Documents, sets a bounded archive-entry count, cleans temporary ZIPs after an hour, and never logs passwords or bearer tokens.

Visible file metadata includes the requested `mp4`, `mkv`, `avi`, `mov`, `webm`, `m4v`, `mp3`, `wav`, `flac`, `jpg`, `jpeg`, `png`, `gif`, `webp`, and `apk` types. Other regular files remain accessible as generic files so the whole Documents root is still browsable/downloadable.

## Android app

The Android project is in [android-app](android-app).

- `LoginActivity` sends the entered password over HTTPS, then saves the returned session encrypted with an Android Keystore AES-GCM key.
- `MainActivity` has breadcrumb navigation, pull-to-refresh, empty/error/offline states, folder opening motion, search, sort, list/grid switching, multi-select, and the five-second direct-download hold gesture.
- Files open through Android's standard `ACTION_VIEW` with a signed HTTPS URL. A video player begins a normal web stream; the app does not download the media before opening it.
- `DownloadService` is a foreground service backed by SQLite. It uses HTTP `Range` to resume a paused file, writes via `MediaStore.Downloads` to the standard Downloads folder, and updates an ongoing notification. Folder or multi-item downloads wait for the server ZIP job, then transfer as a normal resumable file.

The minimum Android version is Android 10 (API 29), which allows correct scoped-storage writes to the shared Downloads folder. The target/compile SDK is API 35.

### Build an APK

Use JDK 17 and Gradle 8.11.1. Android Gradle Plugin 8.9.2 is pinned in the project; its Android API 35 / Gradle / JDK compatibility is documented by [Android Developers](https://developer.android.com/build/releases/agp-8-9-0-release-notes).

For an installable development APK:

```powershell
cd android-app
gradle :app:assembleDebug --no-daemon
Copy-Item .\app\build\outputs\apk\debug\app-debug.apk ..\Heyogesh_Drive.apk
```

For a release-signed APK, create and protect a keystore outside this repository, then pass the four properties. The Gradle build already reads them and never contains a signing password:

```powershell
keytool -genkeypair -keystore $env:USERPROFILE\heyogesh-drive-release.jks -alias heyogesh-drive -keyalg RSA -keysize 4096 -validity 10000
gradle :app:assembleRelease --no-daemon `
  -PHEYOGESH_DRIVE_KEYSTORE="$env:USERPROFILE\heyogesh-drive-release.jks" `
  -PHEYOGESH_DRIVE_KEYSTORE_PASSWORD='your-keystore-password' `
  -PHEYOGESH_DRIVE_KEY_ALIAS='heyogesh-drive' `
  -PHEYOGESH_DRIVE_KEY_PASSWORD='your-key-password'
Copy-Item .\app\build\outputs\apk\release\app-release.apk ..\Heyogesh_Drive.apk
```

Never commit a keystore, a signing password, the Cloudflare tunnel token, or a server signing key. The supplied GitHub Actions workflow builds a debug APK artifact using JDK 17 and Gradle 8.11.1; download that artifact after the run and copy it to the repository root only if you intentionally want the debug build published. A real public release should use the release-signing command above.

## GitHub deployment

Create the requested repository from this folder after the APK has been copied into the root:

```powershell
git init
git add .
git commit -m "Build Heyogesh Drive"
gh repo create Heyogesh-App --private --source=. --remote=origin --push
```

The repository is created private by default because it contains personal-storage source and a downloadable APK. Change visibility only when you intend to make the landing page public. With `Heyogesh_Drive.apk` at repository root, GitHub Pages (or any static hosting that serves this folder) will make the existing `index.html` download link work unchanged.

## Folder structure

```text
Project/
├── index.html                       # responsive, framework-free download page
├── Heyogesh_Drive.apk               # copy a real built APK here before publishing
├── host_index.js                    # Windows Documents API host
├── package.json / package-lock.json # host dependency lock
├── android-app/                     # complete Android Gradle application
│   └── app/src/main/java/...         # API, secure session, UI, download queue
├── .github/workflows/build-apk.yml  # CI debug APK artifact
└── README.md
```

## Security notes

- Public traffic is HTTPS only; Android rejects cleartext HTTP.
- The Node origin binds to loopback by default. Cloudflare Tunnel is the only intended ingress.
- Password comparison and signed-token comparison are timing-safe. Passwords are not embedded in the Android app or logged by the host.
- Player-safe media URLs carry an HMAC signature and short expiry; external players do not need a bearer header.
- The stored Android session is encrypted with a non-exportable Android Keystore key.
- ZIP jobs are owned by the session that created them, expire automatically, and are not retained in Documents.
- Keep the password and signing key in the Windows service environment; rotate them if a device is lost or a credential is exposed.

## Future improvements

- Add a multi-user account store rather than a shared personal password.
- Move archive jobs from in-memory state to a persistent queue if the host must survive reboots mid-archive.
- Add optional thumbnail generation, per-folder shares, upload support, and server-side audit logs.
- Add Cloudflare Access service-token support if an organisation-wide Zero Trust policy is required.
