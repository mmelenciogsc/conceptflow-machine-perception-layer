#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Check archive legal files, package isolation, and the combined wheel demo."""

from __future__ import annotations

import os
from pathlib import Path
from pathlib import PurePosixPath
import subprocess
import sys
import sysconfig
import tarfile
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[2]
PROJECTS = (
    ROOT / "packages/shared-protocol",
    ROOT / "packages/host-runtime",
    ROOT / "services/cuda-cluster",
)
LEGAL_FILES = ("LICENSE", "LICENSE-APACHE", "LICENSE-MIT", "THIRD_PARTY_NOTICES.md")


def _legal_members(names: list[str], archive_name: str) -> dict[str, str]:
    members: dict[str, str] = {}
    for legal_file in LEGAL_FILES:
        matches = [name for name in names if PurePosixPath(name).name == legal_file]
        if len(matches) != 1:
            raise AssertionError(f"expected exactly one {legal_file} in {archive_name}, found {matches}")
        members[legal_file] = matches[0]
    return members


def _check_wheel_archive(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        bad_member = archive.testzip()
        if bad_member is not None:
            raise AssertionError(f"corrupt wheel member: {bad_member}")
        members = _legal_members(archive.namelist(), path.name)
        for legal_file, member in members.items():
            if archive.read(member) != (ROOT / legal_file).read_bytes():
                raise AssertionError(f"{legal_file} in {path.name} differs from the repository copy")


def _check_source_archive(path: Path) -> None:
    with tarfile.open(path, mode="r:gz") as archive:
        names = archive.getnames()
        if not names:
            raise AssertionError(f"empty source archive: {path}")
        members = _legal_members(names, path.name)
        for legal_file, member in members.items():
            extracted = archive.extractfile(member)
            if extracted is None or extracted.read() != (ROOT / legal_file).read_bytes():
                raise AssertionError(f"{legal_file} in {path.name} differs from the repository copy")


def _install_target(target: Path, wheels: list[Path]) -> None:
    subprocess.run(
        [
            sys.executable,
            "-m",
            "pip",
            "install",
            "--disable-pip-version-check",
            "--no-compile",
            "--no-deps",
            "--target",
            str(target),
            *map(str, wheels),
        ],
        cwd=target.parent,
        check=True,
    )


def _run_isolated(target: Path, statement: str) -> None:
    dependency_paths = {
        path for path in (sysconfig.get_path("purelib"), sysconfig.get_path("platlib")) if path is not None
    }
    environment = os.environ.copy()
    environment["PYTHONNOUSERSITE"] = "1"
    environment["PYTHONPATH"] = os.pathsep.join([str(target), *sorted(dependency_paths)])
    subprocess.run([sys.executable, "-S", "-c", statement], cwd=target.parent, env=environment, check=True)


def main() -> int:
    wheels: list[Path] = []
    for project in PROJECTS:
        project_wheels = sorted((project / "dist").glob("*.whl"))
        source_archives = sorted((project / "dist").glob("*.tar.gz"))
        if len(project_wheels) != 1 or len(source_archives) != 1:
            raise AssertionError(f"expected one wheel and source archive in {project / 'dist'}")
        _check_wheel_archive(project_wheels[0])
        _check_source_archive(source_archives[0])
        wheels.extend(project_wheels)

    with tempfile.TemporaryDirectory(prefix="conceptflow-wheel-check-") as temporary:
        temporary_path = Path(temporary)
        protocol_wheel, host_wheel, cluster_wheel = wheels

        protocol_target = temporary_path / "protocol"
        _install_target(protocol_target, [protocol_wheel])
        _run_isolated(
            protocol_target,
            "import importlib.util; import conceptflow.mpl.v1.perception_pb2; "
            "import conceptflow_mpl_protocol; "
            "assert importlib.util.find_spec('conceptflow_mpl_host') is None; "
            "assert importlib.util.find_spec('conceptflow_mpl_cluster') is None",
        )

        host_target = temporary_path / "host"
        _install_target(host_target, [protocol_wheel, host_wheel])
        _run_isolated(
            host_target,
            "import importlib.util; import conceptflow_mpl_host; "
            "assert importlib.util.find_spec('conceptflow_mpl_host.demo') is None; "
            "assert importlib.util.find_spec('conceptflow_mpl_cluster') is None",
        )

        cluster_target = temporary_path / "cluster"
        _install_target(cluster_target, [protocol_wheel, host_wheel, cluster_wheel])
        _run_isolated(cluster_target, "import conceptflow_mpl_cluster; import conceptflow_mpl_cluster.demo")

        combined_target = temporary_path / "combined-demo"
        _install_target(combined_target, wheels)
        _run_isolated(
            combined_target,
            "from conceptflow_mpl_cluster.demo import main; raise SystemExit(main())",
        )
    print("wheel, source archive, independent import, and combined demo checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
