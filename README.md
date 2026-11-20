# DoorBird Intercom for Kiosk Satellite

A local-only [Kiosk Satellite](https://kiosksatellite.com) plugin for DoorBird video door stations. A call-button press wakes the panel, plays a chime and opens a full-screen viewer. Listen, answer with two-way audio, or open the viewer at any time.

The plugin connects directly to the DoorBird. It needs no DoorBird cloud account, Home Assistant automation, go2rtc, Scrypted or restreamer. Optional cards and actions use Kiosk Satellite's existing Home Assistant connection.

**Status:** live-tested on an RK3576S Kiosk Satellite panel with a DoorBird: HTTP-monitor ringing, 1280×720 H.264 hardware video, listening and wake-word mute/restoration were verified. Two-way microphone use, UDP event reception and compatibility with other DoorBird models and Android devices remain untested.

## Features

- **Both local event paths:** a reliable persistent HTTP `monitor.cgi` stream and encrypted DoorBird event-monitoring v2 broadcasts on UDP ports 6524 and 35344. Either path opens the viewer; duplicate rings are suppressed. HTTP reconnects after a dropped connection, and UDP retries setup failures.
- Live H.264/H.265 video over local RTSP, decoded by Android hardware where supported.
- Listening and two-way audio through DoorBird's local G.711 mu-law HTTP API.
- Full-screen ringing and call screen with speaker, microphone, hang-up, volume, push-to-talk and automatic call limits.
- Optional screen wake, screensaver dismissal and restoration after an unanswered ring.
- Configurable chime, music pause, video path and microphone source.
- Custom call buttons and cards with Home Assistant actions and live entity states.
- Home Assistant switches for ringing, chime and screen wake, plus a chime-volume number and status sensors.
- English settings, commands and triggers, plus English and German viewer texts.
- Chime-only operation on headless Kiosk Satellite devices.

## Requirements

- Kiosk Satellite **2026.10.42 or newer**, Android **8.0/API 26 or newer**.
- A DoorBird with current firmware supporting `monitor.cgi`. The optional encrypted UDP path additionally needs **event monitoring v2**.
- A DoorBird user and password. A separate user for the panel is recommended.
- Network access from the panel to the DoorBird on HTTP port **80** and RTSP port **554** (configurable).
- The HTTP event monitor needs only HTTP port **80** and works without UDP broadcast forwarding. For the additional UDP path, DoorBird broadcasts on ports **6524** and **35344** must reach the panel; filtered networks and VLANs may need explicit forwarding. Each monitor connection uses one of DoorBird's limited concurrent monitor streams.
- **Watch always** permission for manual live view outside a recent ring. API-Operator permission is not required.
- A speaker and microphone for calls. No recordings are stored.

## Installation

### Community Plugins (Recommended)

DoorBird Intercom is listed in [Community Plugins](https://kiosksatellite.com/plugins/). Use the button below to open it in your Kiosk Satellite and install it.

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=Bjoern3003/kiosk-satellite-doorbird-intercom)

Or search for `DoorBird Intercom` in **Settings > Plugin Manager > Browse plugins** in Kiosk Satellite.

### Manual

1. In Kiosk Satellite, open **Settings > Plugin Manager** and enable **Enable Plugins**.
2. Choose **Add plugin**, enter `https://github.com/Bjoern3003/kiosk-satellite-doorbird-intercom`, then **Preview**.
3. Review the source, requested capabilities and README, then choose **Trust and install**.
4. Enable **DoorBird Intercom** and open its settings.

For a local build, use **Plugin Manager > Developer Tools > Install from ZIP** and select `dist/doorbird-intercom-1.0.6.zip`. Update without uninstalling to retain settings.

## Setup

1. Enter the DoorBird's IP address or hostname, username and password.
2. Leave **RTSP port** at 554 unless your installation uses another port.
3. Press **Check**. The check validates the login and notification key, receives a video keyframe, checks this Android device's decoder, and opens listening and talking connections. The talking check sends one silent G.711 byte.
4. Use **Test ring** to exercise the panel without pressing the physical button.
5. Optionally expose **Open** in the kiosk drawer or to Home Assistant through Kiosk Satellite's action options.

If **Default** video does not work, try **720p**. The explicit paths depend on model and firmware: `/mpeg/media.amp`, `/mpeg/720p/media.amp` and `/mpeg/1080p/media.amp`. The plugin does not silently change the selected stream.

## Viewer and ringing

- **Speaker** listens without opening the microphone.
- **Answer** enables speaker and microphone. During a call, speaker and microphone can be toggled independently.
- **Push to talk** sends microphone audio only while its button is held and ducks incoming audio to reduce feedback.
- An untouched ring closes after the configured interval. Speaker or Answer stops that countdown. A manually opened viewer stays open until closed.
- **End a call after** limits answered and manually opened calls.
- Add custom actions or entity cards for a door relay, lights, dashboard views and other actions supported by Kiosk Satellite. Use **Hold to run** for door or gate actions.

DoorBird permits only one active audio/video call. The official DoorBird app can interrupt the plugin's stream. Running several panels in the same call is not supported by the door station.

## Home Assistant and actions

With Kiosk Satellite's ESPHome/native entity support, the plugin publishes:

| Entity | Type |
|---|---|
| Ring on this device | Switch |
| Play a chime | Switch |
| Wake the screen | Switch |
| Chime volume | Number, 5–100 |
| Last ring | Text sensor |
| Doorbell connection | Text sensor |
| First picture | Text sensor |

Commands and dashboard links:

| Command | Purpose | Dashboard link |
|---|---|---|
| Open | Open the viewer without a ring | `ks://plugin/doorbird-intercom/show` |
| Test ring | Simulate a button press | `ks://plugin/doorbird-intercom/testRing` |
| Close | Close the viewer | `ks://plugin/doorbird-intercom/dismiss` |

The plugin also fires **Doorbell pressed**, **Call screen opened**, **Call screen closed**, **Answered** and **Missed ring** triggers for Kiosk Satellite gestures. It does not create or change Home Assistant automations.

## Privacy and limitations

- Video, HTTP audio and credentials use DoorBird's unencrypted local HTTP/RTSP protocols. Use a trusted network and a separate, minimally privileged DoorBird user.
- UDP event payloads are authenticated and encrypted with DoorBird's original ChaCha20-Poly1305 construction. The notification key is fetched through the authenticated local session API.
- The password is a Kiosk Satellite secret setting and is masked in supported settings interfaces; this does not imply hardware-backed encryption.
- The microphone runs only after Answer, Microphone or Talk is activated. Closing a call, background cleanup and the call time limit stop it. Push-to-talk keeps the recorder open but muted between holds.
- No analytics, cloud connections, audio recording or video recording are included.
- Audio latency and echo depend on the Android panel. Try **Voice communication**, keep **Android call mode** enabled, lower call volume, or use **Push to talk** if echo occurs.
- If the plugin is **disabled during a call**, a wake word it muted stays muted until the plugin is enabled again and its startup restoration runs. If it is **uninstalled during a call**, it cannot restore the wake word because Kiosk Satellite revokes host commands before `stop()`. In that case, turn off Kiosk Satellite's own wake-word mute setting manually. End active calls before disabling or uninstalling the plugin.
- Automated tests do not prove Android rendering, microphone capture, echo cancellation or compatibility with every DoorBird firmware/model.

## Troubleshooting

| Problem | What to check |
|---|---|
| Login works but no ring arrives | UDP broadcast forwarding on ports 6524/35344, event-monitoring v2 firmware, and that panel and DoorBird share the required broadcast domain. The logs show socket setup, the Wi-Fi multicast lock and whether a UDP v2 packet arrives or is rejected. |
| No video | RTSP port, selected Video path, Watch always permission, and whether the official app has taken the call |
| No sound or the door cannot hear you | DoorBird user permissions, another active call, Kiosk Satellite's microphone permission and microphone source |
| Echo or feedback | Voice communication source, Android call mode, lower volume or Push to talk |
| 204 during manual view | Enable Watch always, or open within DoorBird's permitted period after a real ring |
| 423 login response | DoorBird has temporarily blocked login after wrong credentials; wait and verify the login |
| 503 busy response | Another app or panel is using the DoorBird call |

Kiosk Satellite's plugin logs contain protocol-specific connection details without intentionally logging passwords or stream credentials.

## Build and test

Use JDK 17 or newer, Python 3.9+ and an Android SDK with platform 35 and build-tools including D8. Set `JAVA_HOME` and `ANDROID_HOME`.

```sh
python3 tools/test.py
python3 tools/build.py --android-platform 35
```

The build writes `dist/doorbird-intercom-<version>.zip` and a SHA-256 checksum. Tests use local fake servers and DoorBird's documented encrypted-packet example; they do not contact a real door station.

On Windows, use `py` instead of `python3` if that is how Python is installed.

## Credits and license

The interface, ring state machine, common audio helpers, RTSP/RTP implementation and build tools are derived from [Reolink Doorbell Intercom](https://github.com/patrick11994/ks-reolink-doorbell-intercom), Apache-2.0. The SDK originates from the [Kiosk Satellite plugin template](https://github.com/jxlarrea/kiosk-satellite-plugins). DoorBird protocol behavior follows the public [DoorBird LAN API 0.36](https://www.doorbird.com/downloads/api_lan.pdf?rev=0.36).

Licensed under [Apache-2.0](LICENSE). See [NOTICE](NOTICE). DoorBird is a trademark of Bird Home Automation GmbH. This project is not affiliated with or endorsed by Bird Home Automation, Kiosk Satellite or Home Assistant.
