# Shishyams AI Caller — Android companion app (Steps 5–7, version 1.2.0)

The Android half of the **AI Calling Agent** (Digital Marketing Portal). In Step 5 it does one job well:
it **pairs with your PHP application and stays securely connected** to it. Placing SIM calls,
live voice AI, and campaign controls arrive in the next steps and plug into the same link.

```
 Dashboard (PHP)                                   Android phone
 institute/digital-marketing/ai-calling-devices.php
        │  generate one-time code ───────────────►  enter server address + code
        │                                           │
 ai-calling-api.php  ◄── HTTPS  pair ──────────────┘   → receives a private device token
        │
        ├── HTTPS long-poll  (always works, any PHP host)
        └── Secure WebSocket (optional, needs a long-running PHP process)
```

## What's in this step

| Part | File |
|---|---|
| Device API (HTTPS) | `ai-calling-api.php` |
| Pairing / auth / command queue | `includes/dm_ai_devices_functions.php` |
| Dashboard page (pair, status, test, rename, remove) | `institute/digital-marketing/ai-calling-devices.php` |
| Optional WebSocket server | `bin/ai-calling-ws-server.php` |
| Settings | `config/ai_calling.php` |
| This Android project | `android-app/` |

Database tables are created automatically the first time you open any AI Calling page
(reference SQL: `database/migrations/2026_11_16_add_dm_ai_calling_devices.sql`).

## 1. Build the app

Requirements: **Android Studio** (Koala or newer, includes JDK 17) and internet access for Gradle.

1. Open Android Studio → **Open** → choose this `android-app` folder. Let Gradle sync.
2. Plug in your phone (USB debugging on) and press **Run ▶**, or build an APK:
   **Build → Build Bundle(s) / APK(s) → Build APK(s)**. The file is in `app/build/outputs/apk/`.
3. Command line alternative: run `gradle wrapper --gradle-version 8.7` once, then `./gradlew assembleDebug`.
4. Copy the APK to the phone and install it (allow "Install unknown apps").

Min Android version: 8.0 (API 26). Tested design target: Android 14.

## 2. Pair the phone

1. Dashboard → **Digital Marketing → AI Calling Agent → Devices** (or the **Pair Device** button).
2. Press **Generate pairing code** — an 8-character code valid for 10 minutes appears with your server address.
3. In the app enter the **server address** (e.g. `https://shishyams.com`), the **code**, and a name, then tap **Pair**.
4. The dashboard page updates by itself. Press **Test** next to the phone — you should see
   *"Connected — the phone answered in … ms over HTTPS"* (or WebSocket).
5. In the app tap **Keep running in background** and allow it, so Android does not pause the link.

## 3. Security model

* **HTTPS only.** The server refuses plain `http://`; the app refuses it too, and the OS blocks cleartext
  through `network_security_config.xml`.
* **One-time pairing code**: 8 characters from a 31-character alphabet, valid 10 minutes, usable once,
  stored only as a hash. Wrong codes are rate-limited (8 per 15 minutes per IP).
* **Per-device secret token** (256-bit random). Only its SHA-256 hash is stored on the server; the app keeps it in
  `EncryptedSharedPreferences` (Android Keystore). Sent in the `X-Device-Token` header (WebSocket: first message,
  never in the URL).
* **Remove = instant revoke.** Removing a phone on the dashboard invalidates its token; the app notices and un-pairs.
* No hardware identifiers are collected — the phone sends a random install id, battery %, network type, SIM
  operator name, app and Android version.
* Optional hardening you can add later: certificate pinning in `net/ApiClient.kt` (`Http`).

## 4. Optional: secure WebSocket (VPS / dedicated server only)

Skip this on shared hosting — the HTTPS channel already works and the app falls back to it automatically.

1. Run the server as a background service (it listens on `127.0.0.1:8081`, plain TCP):

   ```ini
   # /etc/systemd/system/aic-ws.service
   [Unit]
   Description=AI Calling WebSocket server
   After=network.target mysql.service

   [Service]
   User=www-data
   WorkingDirectory=/var/www/YOUR_SITE
   ExecStart=/usr/bin/php bin/ai-calling-ws-server.php
   Restart=always
   RestartSec=3

   [Install]
   WantedBy=multi-user.target
   ```
   `sudo systemctl enable --now aic-ws`

2. Put TLS in front with your existing web server.

   **Apache** (`a2enmod proxy proxy_wstunnel`):
   ```apache
   ProxyPass        "/aic-ws" "ws://127.0.0.1:8081/"
   ProxyPassReverse "/aic-ws" "ws://127.0.0.1:8081/"
   ```
   **Nginx**:
   ```nginx
   location /aic-ws {
       proxy_pass http://127.0.0.1:8081;
       proxy_http_version 1.1;
       proxy_set_header Upgrade $http_upgrade;
       proxy_set_header Connection "upgrade";
       proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
       proxy_read_timeout 120s;
   }
   ```

3. In `config/ai_calling.php` set `AIC_WS_PUBLIC_URL` to `wss://YOUR_DOMAIN/aic-ws`.
   Phones pick it up automatically on their next poll; the dashboard shows "Secure WebSocket — server process is running".

## 5. API reference (for later steps)

`POST https://YOUR_DOMAIN/ai-calling-api.php?action=…` — JSON in, JSON out, header `X-Device-Token` after pairing.

| action | body | returns |
|---|---|---|
| `ping` | – | `{ok, time}` |
| `pair` | `code, device_uid, device_name, sim_label, app_version, android_version` | `{token, device_id, institute_name, config}` |
| `heartbeat` | `info{battery, charging, network, sim_label, app_version}` | `{config}` |
| `poll` | `info{…}, hold` | `{commands:[{id, command, payload}], config}` (long-poll) |
| `ack` | `id, ok, result{}, error` | `{accepted}` |
| `unpair` | – | `{ok}` |

Errors: `{ok:false, error:"code", message:"text"}`; HTTP 401 means the phone is no longer paired.

WebSocket frames are listed at the top of `bin/ai-calling-ws-server.php`.

## 6. Troubleshooting

| Symptom | Fix |
|---|---|
| "Unexpected reply from the server" | Wrong server address — it must be the site root, e.g. `https://shishyams.com`. |
| "https_required" | Open the address with `https://`. Behind a proxy make sure `X-Forwarded-Proto: https` is passed. |
| Pairing code invalid | It expires after 10 minutes and works once. Generate a new one. |
| Phone shows *Offline* on the dashboard after a while | Allow background activity in the app, and set the battery mode for the app to *Unrestricted* (some brands hide this under "Autostart" / "App launch"). |
| Long-poll uses too many PHP workers | Lower `AIC_POLL_HOLD_SEC` in `config/ai_calling.php` (e.g. 8), or use the WebSocket channel. |
| `Authorization` header stripped by host | Not an issue — the app uses `X-Device-Token`. |

## 7. What comes next

Step 6 added SIM calling (`CallManager.kt`). **Step 7 (v1.2.0)** lets the AI talk during a call:

* `call/VoiceModeService.kt` — "Start AI voice mode": a microphone foreground service that must be started from the app screen
  (Android only allows background microphone use for services started while the app is visible).
* `call/VoiceBridge.kt` — per call: loudspeaker on, microphone sentence detection, upload to `ai-calling-api.php?action=rt_turn`,
  play the MP3 reply, listen again (strict turn-taking). The `dial` command's `realtime` block configures it.
* New permissions: `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `FOREGROUND_SERVICE_MICROPHONE`. The app reports `voice_ready` with
  every heartbeat; the dashboard only dials AI calls through a phone that reports it.
* The API gained `rt_turn` (raw WAV body, query-string details): see the top of `ai-calling-api.php`.

Android cannot inject audio into a SIM call, so quality depends on the phone, the room and the call volume — see the main README.
