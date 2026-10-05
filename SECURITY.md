# Security policy

## Reporting a vulnerability

Please report vulnerabilities **privately** using GitHub's
[Report a vulnerability](https://github.com/dzid26/TeslaBatteryBLE/security/advisories/new)
flow (Security → Advisories). Do not open a public issue.

Include where possible:

- What is affected (protocol core, key storage, pairing, app UI, CI)
- Steps to reproduce, ideally against the simulated car in debug builds
- Device/Android version and vehicle model/software version if relevant
- Any suggested fix

You can expect an acknowledgement within a few days. This is a spare-time project
with no bug bounty; fixes are prioritized by severity.

## Scope

In scope:

- Cryptographic handling: session handshake, AES-GCM/HMAC usage, nonces, counters,
  metadata/signature construction, anti-replay windows
- Key management: app-private key storage and backup exposure (ADR-0005), key
  generation, enrollment/roles
- Protocol implementation: framing, message parsing, malformed input handling
- Any path that could leak VINs, keys, or plaintext to logs or other apps

Out of scope:

- Tesla's vehicles, servers, or firmware
- Physical access attacks and BLE jamming
- Vulnerabilities in third-party dependencies (report upstream; tell us too)

## Supported versions

Only the latest `main` and the latest preview/stable release receive fixes while
the project is in beta.
