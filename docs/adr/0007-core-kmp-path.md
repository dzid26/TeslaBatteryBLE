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

- The crypto swap landed in #236 (cryptography-kotlin 0.6.0, JDK provider)
  under the pre-1.0 policy: no data migration, no in-app migration code.
  Existing PKCS8 keys must import
  through the new library; if they do not, the owner re-pairs (acceptable at
  this stage).
- Contributors get a failing-lint-free rule set today: keep `java.*` out of
  new logic, extend the seam when a capability is missing.
- Build-plugin migration cost stays bounded: one seam to re-target, protos
  and fixtures unchanged, Go-vector cross-checks preserved via `jvmTest`.
- Revisit the crypto-library choice at build-migration time; if
  cryptography-kotlin is unmaintained then, take the `expect`/`actual`
  fallback.
- Open question (#119): the seam should take a key handle
  (`publicKeyRaw` + `agree(peer)`) rather than PKCS8 bytes, so
  non-exportable keys (Android Keystore `PURPOSE_AGREE_KEY`, Secure Enclave)
  stay possible.

## Update 2026-10-16: build-migration plan (draft)

Status of this section: Proposed. It plans the plugin switch the Decision
section deferred.

Preconditions, in order:

1. Crypto on cryptography-kotlin (done, #236).
2. The move-only split of the protocol code into `:protocol` (#251).
3. Step 1 (#264): main code in `:protocol` and `:core` free of `java.*` and
   `UUID`, every test on `kotlin.test`, and detekt `ForbiddenImport` on both
   modules' main code.

The plugin switch then happens once, on that final layout, for `:protocol`
and `:core` together. `:core` depends on `:protocol`, so both need the same
targets.

### Leftovers from step 1, done in the step-2 PR

- `TeslaCrypto`'s `label.toByteArray(Charsets.US_ASCII)`: `Charsets` is not
  in the common stdlib. It becomes `encodeToByteArray()`, since the labels
  are ASCII literals. The crypto owner reviews that line.
- VIN validation: step 1 switched VIN bytes to `encodeToByteArray()`
  (UTF-8), where `Charsets.US_ASCII` used to map non-ASCII characters to
  `?`. The two are identical for any valid VIN, but nothing checks that a
  VIN is ASCII. Add a `require` at the `TeslaSession`/`TeslaNames` entry
  points (17 characters, `[A-HJ-NPR-Z0-9]`), with a test.

### Targets

- First PR: `jvm()` only. `:app` keeps consuming the JVM artifact, so
  Android needs no `androidTarget()` while the Keystore seam stays in `app`.
  The source sets become `commonMain`, `commonTest` and `jvmTest`. Wire
  generates into `commonMain` once the multiplatform plugin is applied.
- Second PR: `iosArm64()` and `iosSimulatorArm64()`, plus `macosArm64()` so
  `commonTest` runs natively on a macOS CI runner. `iosX64` is skipped
  (Intel simulators are on their way out). Apple klibs only build on macOS,
  so CI gains one `macos-latest` job. Linux jobs stay JVM-only.
- No JS/Wasm target: WebCrypto has no blocking API, and the protocol uses
  the `*Blocking` calls.

### Crypto provider per platform

- `commonMain` depends on `cryptography-core` only.
- JVM uses `cryptography-provider-jdk`, as today.
- Apple uses `cryptography-provider-optimal`, which is CryptoKit first with
  CommonCrypto as the fallback. CryptoKit covers SHA-1, SHA-256, HMAC,
  AES-GCM, ECDH and ECDSA on P-256.
- Known limit: CommonCrypto cannot decode EC private keys without the public
  key field, and cannot decode raw private keys. Generated keys' DER carries
  the public key, so that is fine. A Secure Enclave key handle (#119) makes
  the limit moot.
- The provider is a dependency of the library, not something consumers have
  to choose. Consumers who want another provider can exclude it.

### Go vectors on every target

- `GoVectorTest` reads `expected.txt` through the JVM class loader, which is
  JVM-only.
- Replacement: a Gradle task generates a `commonTest` Kotlin file holding
  `expected.txt` as a string constant, from the same CI-diffed file. The
  vector test then runs unchanged on every target.
- `expected.txt` stays generated by `tools/go-fixtures`, never hand-edited.
  Only its delivery into tests changes.
- Tests that need JVM types (for example `InvalidKeySpecException` in
  `TeslaCryptoTest`) go to `jvmTest`.

### Publishing `:protocol` as KMP

- Use `com.vanniktech.maven.publish` against the Central Portal. It
  publishes the root module with Gradle module metadata plus one artifact
  per target (`teslable-protocol-jvm`, `-iosarm64`, ...), and Gradle
  consumers resolve the right one automatically.
- Signing keys and the Central token live in CI secrets. Publishing runs
  only from a tag and needs the owner's go-ahead (store/account rule).
- ABI: the Kotlin Gradle plugin's built-in ABI validation covers the KMP
  klib ABI and the JVM ABI, with a dump checked in. Explicit API mode goes
  on for `:protocol` in the same PR.
- Swift consumers get an XCFramework through the `XCFramework` Gradle task.
  SKIE/SPM packaging is a later, separate decision.

### Not in scope

- The #119 key-handle seam, Android Keystore and Secure Enclave keys.
- Moving app-level code into the library.
