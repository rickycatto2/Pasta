# Pasta

A self-hosted, single-user clipboard for **text and images** that follows you between laptops, desktops and Android.

Pasta is deliberately small: a dependency-free Node.js server, a responsive web app/PWA, and a native Android client. Run it on a home server or spare PC, expose it through a Cloudflare Tunnel, and open the same private clipboard from any device.

This repository is the source, not a hosted service. You provide the server, domain and passphrase.

## Features

- Paste text or images on one device, see them instantly on others over a live SSE connection.
- Copy text or images to the current device with one click.
- Pin, search, filter, delete, and preview images full-screen.
- Installable PWA (Chrome/Edge “Install app”, iOS “Add to Home Screen”).
- Native Android app:
  - text composer and clipboard reader
  - image picker and upload
  - image copying through a private content provider
  - Android share target (`Share → Pasta`) for text and images
  - automatic refresh while open
- Single passphrase, 30-day sessions, per-device revocation, login rate limiting.
- No npm dependencies, no external database, no analytics, no third-party SDKs.

## Requirements

- **Server:** Node.js **24+** (uses the built-in `node:sqlite` module).
- **Tunnel:** a Cloudflare account and `cloudflared` (any current version).
- **Android build (optional):** JDK 17+, Android SDK platform 37 and build-tools 37.0.0. The included build script uses the SDK command-line tools directly, so no Gradle download is required.

The startup/stop scripts in `scripts/` are for Windows. The server and tunnel themselves are cross-platform; on macOS/Linux run the two commands directly or wire them into systemd/supervisor.

## Quick start

```bash
git clone https://github.com/YOUR_USER/Pasta.git
cd Pasta

cp pasta.env.example pasta.env
# edit pasta.env: choose a long PASTA_PASSWORD and set PASTA_PUBLIC_URL

node server/server.mjs
```

Open <http://127.0.0.1:4317>. There is no `npm install` step.

The server binds to `127.0.0.1` only. Do not expose port 4317 directly to the internet; use the tunnel.

### Configuration

```ini
PASTA_PORT=4317
PASTA_PUBLIC_URL=https://pasta.example.com
PASTA_PASSWORD=choose-a-long-passphrase
```

| Variable | Purpose |
| --- | --- |
| `PASTA_PORT` | Local HTTP port (default `4317`). |
| `PASTA_PUBLIC_URL` | Public HTTPS address, used for display and the Android app link. |
| `PASTA_PASSWORD` | The one passphrase that unlocks the clipboard. Use a long, unique value. |

`pasta.env` is ignored by git. Keep it private. If it is missing, the server generates a passphrase and writes it to `data/initial-password.txt` on first run.

Changing `PASTA_PASSWORD` does **not** revoke existing 30-day sessions. Revoke devices under **Settings & devices**, or delete the `sessions` rows in `data/pasta.db`.

## Cloudflare Tunnel

A stable named tunnel is better than a rotating quick tunnel. Example:

```bash
cloudflared tunnel login
cloudflared tunnel create pasta
cloudflared tunnel route dns pasta pasta.example.com
cp cloudflared.example.yml cloudflared.yml
# edit cloudflared.yml: tunnel UUID, credentials path, hostname
cloudflared tunnel --config cloudflared.yml run
```

`cloudflared tunnel create` prints the tunnel UUID and the credentials file path. Put those in `cloudflared.yml`; the example file shows the shape.

Set `PASTA_PUBLIC_URL` to the same HTTPS hostname. `cloudflared.yml` is ignored by git because it contains machine-specific paths and tunnel identifiers.

For a second factor, put **Cloudflare Access** in front of the hostname in the Cloudflare dashboard. Pasta's passphrase remains the app-level lock.

## Running persistently

On Windows:

```powershell
# Start server + dedicated tunnel; safe to run repeatedly
powershell -ExecutionPolicy Bypass -File .\scripts\start-pasta.ps1

# Stop only Pasta; other tunnels are left alone
powershell -ExecutionPolicy Bypass -File .\scripts\stop-pasta.ps1

# Register a Task Scheduler entry that starts Pasta at sign-in
powershell -ExecutionPolicy Bypass -File .\scripts\install-startup.ps1
```

The scripts resolve the project root from their own location, so the checkout can live anywhere. Logs go to `logs/`.

On Linux/macOS, run:

```bash
node server/server.mjs
cloudflared tunnel --config cloudflared.yml run
```

under systemd, launchd, Docker, or another process supervisor. Make sure both processes restart after a crash.

## Android app

The native client lives in `android/` and is built with the SDK tools directly:

```powershell
cd android
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Clean
```

The default server URL in the repository is the placeholder `https://pasta.example.com`. To prefill your own URL without committing it:

```powershell
# android/server-url.txt is ignored by git
'https://pasta.example.com' | Set-Content android\server-url.txt
```

The build script reads that file and generates an ignored resource override before compiling. Without it, the app starts with the placeholder, and the server URL remains editable on the login screen.

### Signing

For repeatable updates, use your own keystore:

```powershell
keytool -genkeypair -keystore android\pasta-release.jks -alias pasta `
  -keyalg RSA -keysize 2048 -validity 10000

Copy-Item android\keystore.properties.example android\keystore.properties
# edit android\keystore.properties with the real password
```

`pasta-release.jks` and `keystore.properties` are ignored by git. The build script also accepts `PASTA_KEYSTORE_PASSWORD`.

Outputs:

- `android/build/Pasta.apk`
- `public/downloads/Pasta.apk`
- `Pasta.apk` in the repository root

The APK is signed with your local key. Android will warn about installing from an unknown developer; that is normal for a sideloaded personal app.

## Security model

- One passphrase, hashed with scrypt in memory; never logged or written to the repository.
- Session tokens are 256-bit random values; only SHA-256 hashes are stored in SQLite. Cookies are `HttpOnly`, `SameSite=Lax`, and `Secure` over HTTPS.
- Login rate limiting: 8 failed attempts per IP per 15 minutes.
- Same-origin enforcement on mutations, CSP, frame denial, `nosniff`, no-referrer, no indexing.
- Images are type-sniffed (PNG/JPEG/GIF/WebP), size-capped at 20 MB, stored outside the web root, and served with a private immutable cache header.
- Text is capped at 100,000 characters.
- Android requires HTTPS (`usesCleartextTraffic="false"`), stores only the API token in its private SharedPreferences, and exposes image clipboard URIs through a non-exported provider with one-shot URI grants.

Never commit `pasta.env`, `cloudflared.yml`, `data/`, `logs/`, the signing keystore, or `keystore.properties`. They are all in `.gitignore` for a reason.

## Project layout

```
.
├─ server/server.mjs           zero-dependency Node server
├─ public/                     web app, PWA assets and fonts
├─ android/                    native Java Android client
│  ├─ src/                     activities, API client, provider
│  ├─ res/                     resources and vector icons
│  ├─ assets/fonts/            bundled OFL fonts
│  └─ build.ps1                SDK-only APK build script
├─ scripts/                    Windows start/stop/startup helpers
├─ data/                       SQLite DB + uploaded images (private)
├─ logs/                       process logs (private)
├─ pasta.env.example           configuration template
├─ cloudflared.example.yml     tunnel configuration template
└─ package.json
```

## How it works

- `server/server.mjs` is a single Node HTTP server with SQLite, scrypt password verification, cookie/bearer sessions, image uploads, and an SSE event stream.
- `public/` is a vanilla JS/CSS PWA. Images are re-encoded to WebP on upload and converted to PNG when copied to the clipboard for compatibility.
- `android/` uses platform APIs only: `HttpURLConnection`, `org.json`, `ClipboardManager`, and a small `ContentProvider` for image clipboard URIs.

## Known limitations

- Browsers require a user gesture for clipboard writes, so cross-device copying is one click. A website cannot silently replace the OS clipboard in the background.
- Safari/iOS may require opening an image and long-pressing to copy it; text copy is supported.
- Images are stored as WebP to keep the server small. Copying re-encodes to PNG for compatibility.
- The included startup helper only starts at user sign-in; if the machine boots and nobody logs in, Pasta is not available.
- This is intentionally a single-user app. Do not share the passphrase.
- No license has been selected for this repository yet. Add one if you intend to accept contributions or redistribute it.

## Credits

- Display font: **Fraunces**, UI font: **DM Sans**. Both are licensed under the SIL Open Font License; license texts are included in `public/fonts/` and `android/assets/fonts/`.
- Icons are small hand-built vector paths inspired by common line-icon conventions.
