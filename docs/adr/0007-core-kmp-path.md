// SPDX-License-Identifier: AGPL-3.0-only
# ADR-0007: Keep `core` on a KMP migration path

Status: Accepted
Date: 2026-10-07

## Context

- `core` claims to be multiplatform-ready, but it is built with the
  `kotlin.jvm` plugin and its crypto goes straight to JCA/JCE
  (`KeyAgreement` ECDH, `Cipher` AES/GCM, `Mac` HmacSHA256, `MessageDigest`
  SHA-1/SHA-256, `SecureRandom`, EC key specs, PKCS8).
- A file-by-file audit (2026-10-07) found 14 of 20 main-source files are
  already portable pure Kotlin (framing, history, health math,
  `AntiReplayWindow`, command/session encoding). The 6 non-portable files
  cluster into one seam: ECDH P-256, AES-GCM, HMAC-SHA256, SHA-1/SHA-256,
  secure RNG, UUID (`TeslaCrypto`, `TeslaKeys`, `Metadata`, `TeslaNames`,
  `TeslaGatt`, `TeslaVcsec`). No file is inherently platform-bound.
- Wire is not a blocker: the Wire runtime has published multiplatform
  artifacts since 4.9.0 and okio 3.x is KMP, so the same protos and plugin
  carry over.

## Decision

- Do not migrate the build to KMP now. Keep `core` on the migration path with
  guardrails so no new change makes the move harder.
- Swap the crypto layer to `dev.whyoleg.cryptography`
  (cryptography-kotlin) now, while there are no users to migrate. It is the
  only single KMP library covering the full seam (SHA-1/SHA-256, HMAC,
  AES-GCM, ECDH P-256, EC keygen, SecureRandom) behind one common API, with
  platform backends (OpenSSL/native, JCA on JVM, WebCrypto on JS). The
  build stays `kotlin.jvm` until the plugin switch; this removes the JCA
  blocker first.
- When the build migration happens: apply the `multiplatform` plugin with a
  `jvm()` target first, keep the same protos, move tests to `kotlin.test` in
  `commonTest` (JVM-only vectors stay in `jvmTest`).
- Fallback if cryptography-kotlin is unmaintained at build-migration time:
  an `expect`/`actual` seam keeping JCA on JVM, one actual per target per
  primitive. BouncyCastle alone is rejected (JVM-only).

## Guardrails (binding until migration)

1. No `java.*`/`javax.*` imports in new portable `core` code. Platform APIs
   appear only in `expect` declarations or single-purpose JVM impl files.
2. Seam pattern for crypto/RNG/UUID: capability interfaces live with the
   common logic; the JVM implementation holds the JCA calls; callers receive
   the seam by constructor injection (pattern already proven by
   `HashContext`/`Metadata` and `TeslaSession`'s `nonceGenerator`).
3. New `core` files compile against common stdlib only; if a file needs a
   platform capability it extends a seam instead of importing `java.*`.
4. New `core` runtime dependencies must publish KMP artifacts covering all
   future `core` targets; JVM-only libraries are test-only or hidden behind
   a seam.
5. New tests use `kotlin.test` and avoid JVM-only assertions so they can move
   to `commonTest` unchanged.

## Consequences

- The crypto swap is done pre-users: no data migration, no in-app migration
  code. Existing PKCS8 keys must import through the new library; if they do
  not, the owner re-pairs (acceptable at this stage).
- Contributors get a failing-lint-free rule set today: keep `java.*` out of
  new logic, extend the seam when a capability is missing.
- Build-plugin migration cost stays bounded: one seam to re-target, protos
  and fixtures unchanged, Go-vector cross-checks preserved via `jvmTest`.
- Revisit the crypto-library choice at build-migration time; if
  cryptography-kotlin is unmaintained then, take the `expect`/`actual`
  fallback.
