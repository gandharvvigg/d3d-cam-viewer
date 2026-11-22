# HiCam Viewer — Privacy Policy

_Last updated: 22 November 2026_

HiCam Viewer ("the app") is developed by Gandharv. It lets you view, control and set up Wi‑Fi security cameras on your own local network.

## Summary

**The app does not collect, store on any server, sell or share any personal data.** It has no user accounts, no advertising, no analytics and no tracking. Everything stays on your phone and your home network.

## What the app stores on your phone

To connect to your cameras, the app saves the following **only on your device**, in the app's private storage:

- The name, IP address, username and password of each camera you add
- The video stream address that last worked for each camera
- Your app preferences (for example, auto-start and picture-in-picture)
- The name of your home Wi‑Fi network, if you type it during camera setup

This information is never sent to the developer or to any third party. It is excluded from Android cloud backups. Uninstalling the app, or removing a camera inside the app, deletes it.

## Network connections the app makes

- **To your cameras on your local network**, to show live video, control pan/tilt, change camera settings and play recordings from the camera's SD card. Your camera username and password are sent only to the camera itself.
- **To find cameras**, the app scans your local Wi‑Fi network and sends standard discovery messages (ONVIF and similar) that stay on your local network.
- **During Wi‑Fi setup (Smart Link)**, the app broadcasts your Wi‑Fi name and password as local network packets so that a camera in setup mode can join your Wi‑Fi. These packets do not leave your local network.

The app does not connect to any server operated by the developer.

**Automatic camera time:** if you turn this on, the camera itself contacts a public internet time server (pool.ntp.org) to set its clock. The app does not do this for you or send any data about you.

## Photos and videos

Photos you take and recordings you save from the app are written to your phone's gallery (Pictures/HiCam and Movies/HiCam). They are not uploaded anywhere.

## Permissions

- **Internet / network state / Wi‑Fi state**: to connect to cameras on your local network and detect whether you are on Wi‑Fi.
- **Wi‑Fi multicast**: to discover cameras and send Smart Link setup packets on your local network.

The app does not request location, contacts, microphone, phone camera or storage permissions.

## Children

The app is not directed at children under 13 and does not knowingly collect information from anyone.

## Changes

If this policy changes, the new version will be published at this address with a new "Last updated" date.

## Contact

Questions or requests: please open an issue at https://github.com/gandharvvigg/d3d-cam-viewer/issues

---

_HiCam Viewer is an independent app and is not affiliated with, endorsed by or sponsored by any camera manufacturer. Brand names are used only to describe compatibility._
