# SPDX-License-Identifier: MIT OR Apache-2.0
"""Deterministically regenerate Python protobuf and gRPC bindings."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys

from grpc_tools import protoc


PROTO_RELATIVE = Path("conceptflow/mpl/v1/perception.proto")
SPDX_HEADER = "# SPDX-License-Identifier: MIT OR Apache-2.0\n"
MODULE_ROOT = Path(__file__).resolve().parent
WORKTREE_ROOT = MODULE_ROOT.parents[1]
if (WORKTREE_ROOT / "proto" / PROTO_RELATIVE).is_file():
    PROTO_ROOT = WORKTREE_ROOT / "proto"
    SOURCE_ROOT = WORKTREE_ROOT / "src"
else:
    PROTO_ROOT = MODULE_ROOT / "proto"
    SOURCE_ROOT = MODULE_ROOT.parent


def generate(output_root: Path = SOURCE_ROOT) -> tuple[Path, ...]:
    """Generate bindings at *output_root* and return expected output paths."""
    output_root = output_root.resolve()
    output_root.mkdir(parents=True, exist_ok=True)
    include_root = Path(protoc.__file__).resolve().parent / "_proto"
    arguments = [
        "grpc_tools.protoc",
        f"--proto_path={PROTO_ROOT}",
        f"--proto_path={include_root}",
        f"--python_out={output_root}",
        f"--pyi_out={output_root}",
        f"--grpc_python_out={output_root}",
        str(PROTO_RELATIVE),
    ]
    status = protoc.main(arguments)
    if status != 0:
        raise RuntimeError(f"protobuf generation failed with status {status}")
    generated_dir = output_root / PROTO_RELATIVE.parent
    generated = tuple(
        generated_dir / name for name in ("perception_pb2.py", "perception_pb2.pyi", "perception_pb2_grpc.py")
    )
    for path in generated:
        contents = path.read_text(encoding="utf-8")
        if not contents.startswith(SPDX_HEADER):
            path.write_text(SPDX_HEADER + contents, encoding="utf-8", newline="\n")
    return generated


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=SOURCE_ROOT)
    parser.add_argument("--clean", action="store_true")
    args = parser.parse_args(argv)
    target = args.output.resolve() / PROTO_RELATIVE.parent
    if args.clean and target.exists():
        for path in target.glob("perception_pb2*"):
            if path.is_file():
                path.unlink()
    paths = generate(args.output)
    for path in paths:
        print(path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
