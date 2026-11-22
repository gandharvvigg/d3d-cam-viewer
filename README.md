# HiCam Viewer

Android app to find, view, record and set up HiChip-based Wi‑Fi cameras (for example D3D Security D8801) on your local Wi‑Fi.

Privacy policy: [PRIVACY.md](PRIVACY.md)

- Scans the Wi‑Fi network (port scan + ONVIF discovery) and highlights the camera
- Live video over RTSP (auto-detects the stream path, ONVIF stream lookup, HD/SD switch)
- Falls back to refreshing JPEG pictures if the camera has no RTSP stream
- Pan/tilt by holding the arrow buttons (ONVIF, Hi3510 and VStarcam-style CGI)
- Save a photo to Pictures/D3DCam, mute audio, full screen in landscape
- Camera setup: put a new/reset camera on Wi‑Fi with Smart Link (HiChip multicast provisioning — no hotspot, no D3DCam app), LAN cable or hotspot; change Wi‑Fi; change password
- Picture settings: brightness/contrast/colour, flip/mirror, night vision, video quality
- Device tools: device info, sync time with phone, restart
- Finds the camera again automatically if the router gives it a new IP

Works on the local network only. Remote viewing over the internet is not supported.

The APK is built by GitHub Actions — download it from the latest Release.
