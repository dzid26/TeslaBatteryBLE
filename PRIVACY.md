# Privacy

**TeslaBatteryBLE is local-first. It has no internet permission and sends nothing
anywhere.**

## What the app stores (on your device only)

- Vehicle advertisements seen during scanning (name, address, RSSI) — in memory while scanning
- Your enrolled key material — stored in app-private storage and included in Android backup (see below)
- Known cars (VIN, advertised BLE name) so you can reconnect
- Battery and connection history (SOC samples, charge sessions) in a local database
- App settings

The app never synchronizes, uploads, or shares anything. Android's system backup
is separate: if you have it enabled, your device may include app data in cloud
backup or device-to-device transfer — including the enrolled key, so pairing
survives a new phone. Backup is protected by your Google account and your device
lock secret; a rooted or forensically extracted device is outside that protection.
The enrolled key is charging-manager scoped: it can read vehicle data and control
charging, but cannot unlock or drive the car, and new keys always need an NFC card
tap plus vehicle confirmation. Uninstalling removes on-device data; a system
backup copy, if any, is managed by your device and Google account settings.

## Permissions and why they exist

| Permission | Why |
| --- | --- |
| Bluetooth scan / connect | Find and talk to the car over BLE |
| Location (fine) | Required by Android for BLE scanning; the app never reads or stores device location |
| Notifications | Foreground-service status and alerts |
| Foreground service | Keep the car connection and SOC tracking alive while the screen is off |

## Network and third parties

- No analytics, no crash reporting, no ads, no accounts.
- Logs are local; if you export or share them for support, redact VINs and keys first.
- Any future feature requiring network access (for example an optional opt-in data
  export) will be off by default, documented here, and clearly separated from the
  core app. The core app will keep working without it.

## Contact

Questions or concerns: open a [security advisory](https://github.com/dzid26/TeslaBatteryBLE/security/advisories/new)
for sensitive matters, or a regular issue otherwise.
