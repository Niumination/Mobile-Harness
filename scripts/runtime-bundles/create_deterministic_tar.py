#!/usr/bin/env python3
"""Create a deterministic root-owned tar overlay from a prepared directory."""

import os
import sys
import tarfile
from pathlib import Path


def normalized(info: tarfile.TarInfo) -> tarfile.TarInfo:
    info.uid = 0
    info.gid = 0
    info.uname = "root"
    info.gname = "root"
    info.mtime = 1_757_116_800  # 2026-09-06T00:00:00Z
    info.pax_headers = {}
    return info


def main() -> None:
    source = Path(sys.argv[1]).resolve()
    destination = Path(sys.argv[2]).resolve()
    entries: list[Path] = []
    for current, directories, files in os.walk(source, followlinks=False):
        directories[:] = sorted(name for name in directories if not name.startswith("._"))
        files = sorted(name for name in files if not name.startswith("._"))
        current_path = Path(current)
        if current_path != source:
            entries.append(current_path)
        entries.extend(current_path / name for name in files)
    entries.sort(key=lambda path: path.relative_to(source).as_posix())

    with tarfile.open(destination, "w", format=tarfile.GNU_FORMAT, dereference=False) as archive:
        for path in entries:
            archive.add(
                path,
                arcname=path.relative_to(source).as_posix(),
                recursive=False,
                filter=normalized,
            )


if __name__ == "__main__":
    main()
