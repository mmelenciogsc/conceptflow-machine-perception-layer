<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Security Policy

## Supported versions

Before a stable release, only the latest published `0.x` minor line receives
security fixes. Older pre-release lines and unmaintained branches are not
supported.

| Version | Supported |
| --- | --- |
| Latest `0.x` minor | Yes |
| Older `0.x` minors | No |
| Unreleased development branches | Best effort |

## Private reporting

Do not open a public issue for a suspected vulnerability. Use this repository's
[GitHub private vulnerability reporting path](../../security/advisories/new),
which creates a private Security Advisory visible only to repository security
maintainers. Include affected versions, impact, reproduction steps, and a
minimal proof of concept using synthetic or redacted data.

If private vulnerability reporting is unavailable in the repository UI, ask a
repository maintainer to enable it without disclosing vulnerability details in
the public request. Do not send camera captures, credentials, device identifiers,
or personal data through public issues.

## Camera and accessibility scope

Security reports include unintended camera-frame capture, persistence or
disclosure; transport authentication or TLS bypass; unsafe device discovery;
secret leakage; unbounded processing that enables denial of service; cue
spoofing or stale-result acceptance; and failures that could suppress, misroute,
or misleadingly present accessibility feedback.

This software is assistive infrastructure, not a substitute for mobility,
medical, emergency, or safety-critical systems. Reports about accessibility
barriers are welcome as private security reports when exploitation or user harm
is plausible; ordinary accessibility defects may use the public bug template
with synthetic evidence.

Maintainers will acknowledge a private report when capacity permits, coordinate
validation and remediation in the advisory, and credit reporters with consent.
No response or disclosure deadline is promised before maintainers confirm scope.
