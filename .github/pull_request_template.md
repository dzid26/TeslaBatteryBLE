## Summary

<!-- What does this change and why? Link related issues. -->

## Type

- [ ] Bug fix
- [ ] Feature
- [ ] Protocol / crypto change
- [ ] Docs / CI / tooling
- [ ] Refactor (no behavior change)

## Testing

<!-- How was this verified? Unit tests, simulated car, real car? -->

- [ ] `./gradlew :protocol:test :core:test`
- [ ] `./gradlew :app:testDebugUnitTest`
- [ ] `./gradlew :app:lintDebug`
- [ ] Real car (describe what was tested) — optional

## Checklist

- [ ] Protocol changes ship test vectors; `expected.txt` regenerated, not hand-edited
- [ ] No VINs, keys, or decrypted payloads in logs
- [ ] Docs/master-plan updated where relevant
- [ ] Docs and ADRs affected by this change are updated
- [ ] New source files carry `SPDX-License-Identifier: AGPL-3.0-only`
