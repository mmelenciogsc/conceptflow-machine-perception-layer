<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Third-party notices

This inventory covers the repository's direct, pinned dependencies at version
0.1.0. Dependency lock files remain authoritative for the complete transitive
graph. Each dependency remains governed by its upstream license; this
repository's `MIT OR Apache-2.0` license does not relicense it.

## Shipped/runtime dependencies

| Ecosystem | Dependency | Version | Upstream license |
| --- | --- | ---: | --- |
| Python | grpcio | 1.74.0 | Apache-2.0 |
| Python | grpcio-tools | 1.74.0 | Apache-2.0 |
| Python | protobuf | 6.33.5 | BSD-3-Clause |
| Python | Pillow | 12.3.0 | MIT-CMU |
| Android | AndroidX AppCompat | 1.7.0 | Apache-2.0 |
| Android | AndroidX Activity KTX | 1.10.0 | Apache-2.0 |
| Android | AndroidX Core KTX | 1.15.0 | Apache-2.0 |
| Android | kotlinx-coroutines-android | 1.9.0 | Apache-2.0 |
| Android | protobuf-javalite | 4.28.3 | BSD-3-Clause |
| Android | grpc-okhttp | 1.68.1 | Apache-2.0 |
| Android | grpc-protobuf-lite | 1.68.1 | Apache-2.0 |
| Android | grpc-stub | 1.68.1 | Apache-2.0 |
| Android compile-only | javax.annotation-api | 1.3.2 | CDDL-1.0 OR GPL-2.0-with-classpath-exception |
| .NET | Google.Protobuf | 3.28.3 | BSD-3-Clause |
| .NET | Grpc.Net.Client | 2.67.0 | Apache-2.0 |
| .NET build | Grpc.Tools | 2.67.0 | Apache-2.0 |

## Direct build, test, and audit dependencies

| Ecosystem | Dependency | Version | Upstream license |
| --- | --- | ---: | --- |
| Python | build | 1.2.2.post1 | MIT |
| Python | hatchling | 1.27.0 | MIT |
| Python | iniconfig | 2.3.0 | MIT |
| Python | mypy | 1.17.1 | MIT |
| Python | mypy_extensions | 1.1.0 | MIT |
| Python | packaging | 26.3 | Apache-2.0 OR BSD-2-Clause |
| Python | pathspec | 1.1.1 | MPL-2.0 |
| Python | pip | 26.2.1 | MIT |
| Python | pip-audit | 2.10.1 | Apache-2.0 |
| Python | pluggy | 1.6.0 | MIT |
| Python | Pygments | 2.21.0 | BSD-2-Clause |
| Python | pyproject-hooks | 1.2.0 | MIT |
| Python | pytest | 9.0.3 | MIT |
| Python | pytest-asyncio | 1.3.0 | Apache-2.0 |
| Python | Ruff | 0.12.7 | MIT |
| Python | setuptools | 84.0.0 | MIT |
| Python | trove-classifiers | 2026.6.1.19 | Apache-2.0 |
| Python | typing_extensions | 4.14.1 | PSF-2.0 |
| Android build | Android Gradle Plugin | 8.10.1 | Apache-2.0 |
| Android build | Kotlin Gradle plugin | 2.0.21 | Apache-2.0 |
| Android build | protobuf Gradle plugin | 0.9.4 | Apache-2.0 |
| Android test | JUnit 4 | 4.13.2 | EPL-1.0 |
| .NET test | Microsoft.NET.Test.Sdk | 17.12.0 | MIT |
| .NET test | xunit | 2.9.2 | Apache-2.0 |
| .NET test | xunit.runner.visualstudio | 2.8.2 | Apache-2.0 |

The versions above are checked against `requirements.lock`,
`gradle/libs.versions.toml`, Gradle dependency lock files,
`apps/desktop-relay/Directory.Packages.props`, and NuGet lock files. License
identifiers were reviewed from installed package metadata and upstream
Maven/NuGet package metadata. Before distributing binaries, regenerate the
complete dependency graph and retain all notices required by transitive
dependencies.

## External platforms and optional integrations

Android, .NET, CUDA, and Rokid hardware/firmware are not redistributed by this
source repository. The Rokid client uses the Android platform directly and has
no Rokid SDK binary dependency. Model weights, datasets, camera captures, and
QUICK utility implementations are also external and are not included. Their
separate terms apply when a downstream build enables them.
