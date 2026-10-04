# Privacy

**TeslaBatteryBLE is local-first. It has no internet permission and sends nothing
anywhere.**

## What the app stores (on your device only)

- Vehicle advertisements seen during scanning (name, address, RSSI) — in memory while scanning
- Your enrolled key material — encrypted with a Keystore-protected AES key
- Known cars (VIN, advertised BLE name) so you can reconnect
- Battery and connection history (SOC samples, charge sessions) in a local database
- App settings

Uninstalling the app removes this data. Nothing is synchronized, uploaded, or shared.

Android Auto Backup is disabled (`android:allowBackup="false"`), so app data is not
uploaded to Google Drive or transferred by the OS to another device.

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
