# Frequently asked questions

Short answers to common questions about TeslaBatteryBLE. See also
[compatibility](compatibility.md) and the [master plan](master-plan.md).

## Does it need an account or internet?

No. Everything runs on-device over Bluetooth LE. The app has no internet
permission: no Tesla account, no cloud registration, no telemetry, no
analytics. See [PRIVACY.md](../PRIVACY.md).

## Which cars are supported?

Model 3 (all years), Model Y (all years), and Model S/X 2021+ (Palladium).
Pre-2021 Model S/X do not speak this BLE protocol. See
[compatibility](compatibility.md) for the full matrix.

## Why doesn't my car appear while scanning?

Common causes:

- The car advertises a BLE name of the form `S<8 hex chars of sha1(vin)>C`,
  so look for an unfamiliar short name rather than a friendly model name.
- Advertising stops when the car's BLE connection slots are full — "not
  visible" can mean slots are full, not out of range. Move other BLE clients
  (such as keyfobs or phones holding a connection) away or disconnect them.
- The phone must have Bluetooth switched on, and on Android, location
  services must be on for BLE scanning to return results. The app never reads
  or stores device location.

## Does it wake my car?

Only when you explicitly ask it to. The app never wakes the car in the
background just to read battery state. VCSEC status reads work while the car
is asleep; charge state (SOC and related data) requires an awake car, so SOC
reads wait until the car is already awake or you trigger a wake.

## What do the pairing steps look like?

1. Put the car in pairing mode: hold the NFC card on the console until the
   car prompts.
2. The app sends an unsigned `addKey` request over BLE with the default role
   `CHARGING_MANAGER` (least privilege).
3. Confirm on the vehicle screen when it asks. The app then verifies the new
   key appears in the car's key list.

## Where are my vehicle keys stored?

In app-private storage on your phone, which other apps cannot read. Vehicle
keys (P-256) are generated on-device. By default the private keys are encrypted
with this device's hardware-backed Android Keystore (AES) and kept out of
backups, so a new phone requires re-pairing with an NFC card tap. One setting
applies to all paired cars: opting in to "Include vehicle keys in Android backup"
lets Android restore them on a new phone; Android backups are encrypted with your
Google account and device lock, and Android 12+ additionally requires that
encrypted backup is available (Android 11 and below always keep keys out of
backup). The default `CHARGING_MANAGER` role limits impact to read + charge
control — keys cannot unlock or drive the car, and adding new keys always needs
an NFC card tap plus vehicle confirmation. You can remove the app's key from a
car's key list at any time. Uninstalling the app deletes local key material.
Details:
[session and pairing](protocol/session-and-pairing.md),
[ADR-0005](adr/0005-plaintext-key-storage.md).

## Does my pairing survive a reinstall or a new phone?

Only when "Include vehicle keys in Android backup" is on (Settings → Vehicle
keys). With it off - the default - the private key is wrapped by this device's
Android Keystore, which Android deletes on uninstall, so you re-pair with one
NFC card tap. With it on:

- A new phone can restore pairing during setup or a device-to-device transfer
  (Android 12+; Android 11 and below always keep keys out of backup).
- A store install (Play) can restore app data automatically.
- Installing an APK by hand (Obtainium, a browser download, `adb install`) is
  restored automatically when Android's automatic restore is on (it is by
  default); otherwise see the next question.

Battery history is not part of Android backup: it stays on the device and a
restored install starts collecting fresh readings, so the app never shows a
stale battery percentage as if it were current.

## How do I know Android backed up my data, and how do I restore it after a manual install?

Android does not tell apps when a backup last ran. The device-level last backup
is shown in system settings - usually Settings → System → Backup → Back up now -
and Android runs backups in the background, typically daily while the phone is
idle and charging.

For a manually installed APK, restore app data over adb:

    adb shell bmgr list transports
    adb shell bmgr transport <transport from the list>
    adb shell bmgr list sets          # note the token for this device
    adb shell bmgr restore <token> com.dzid26.teslable

Use the Google transport (for example
`com.google.android.gms/.backup.BackupTransportService`). The device must be
signed in to the account that holds the backup and have backup enabled
(`bmgr enabled`). You can also force a backup with
`adb shell bmgr backupnow com.dzid26.teslable`. Automatic restore can be
checked with `adb shell settings get secure backup_auto_restore` (`1` = on);
when it is off, use the `bmgr restore` steps above.

## Why is background tracking sometimes killed?

Android OEM battery management can stop background work aggressively. The app
uses a foreground service with a persistent notification to keep SOC tracking
alive while the screen is off. If tracking still stops, exempt the app from
battery optimization in system settings and keep the persistent notification
enabled.

## Is it on F-Droid or Play?

Not yet. Right now the preview APK is published as a rolling GitHub preview
release (debug builds). Signed stable releases and F-Droid are planned — see
the [master plan](master-plan.md).

## How do I report a bug / security issue?

- Bugs and feature requests: use the issue forms in
  [`.github/ISSUE_TEMPLATE/`](../.github/ISSUE_TEMPLATE/). Include car
  model/year, vehicle software version, Android version, and logs with VINs
  and keys redacted.
- Security issues: do **not** open a public issue — follow
  [SECURITY.md](../SECURITY.md) and report privately.
