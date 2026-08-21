<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Contributing

Thank you for improving the CONCEPTFlow machine-perception layer. Contributions
should be small, reviewable, deterministic where possible, and safe for camera
and accessibility use cases.

## Development setup

Use Python 3.12 and the checked-in dependency lock:

```bash
./scripts/bootstrap
./scripts/test python
./scripts/lint
```

Platform-specific changes must also run the relevant target:

```bash
./scripts/test native
./scripts/test android
./scripts/test dotnet
```

The scripts report missing tools and never install system dependencies. Android
work requires Java 17 plus an Android SDK; desktop relay work requires .NET 8;
native work requires CMake and Ninja.

## Change expectations

- Add or update focused tests for behavior changes.
- Keep camera input synthetic or explicitly consented in tests and reports.
- Preserve bounded queues, timeouts, retention-disabled defaults, redaction,
  assistive-only cue semantics, and secure production transport.
- Run `./scripts/repository/check_policy.py` and
  `./scripts/repository/secret_scan.py` before opening a pull request.
- Add `SPDX-License-Identifier: MIT OR Apache-2.0` to new source files.
- Do not commit generated build output, device captures, model weights, secrets,
  signing material, or personal data.

Generated protobuf bindings are checked in. Modify the source `.proto`, run
`./scripts/generate`, and include regenerated bindings in the same change.

## Pull requests

Explain the problem, scope, validation commands, accessibility and camera-data
impact, and any remaining risk. Keep unrelated formatting or refactoring out of
the change. By submitting a contribution, you agree that it may be licensed
under the repository's `MIT OR Apache-2.0` terms.

Report security or privacy vulnerabilities privately as described in
[SECURITY.md](SECURITY.md), not in a public issue.
