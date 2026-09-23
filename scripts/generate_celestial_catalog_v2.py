#!/usr/bin/env python3
"""
Generate HoraTrack Celeste V2 mobile star/constellation assets.

This tool is intentionally OFFLINE. Download/pin the two source files separately,
then provide them as inputs. Runtime Android/iOS code must never contact the
catalogue providers with user GPS data.

BSC5P fixed-width input:
  frostoven/BSC5P-JSON @ 1018c1d32c85cd02d7bbdade5726bc828f5388bd
  original_bsc5p/catalog blob 6a5fe242dd2d7f3014871ec81ba40032d888772a

Constellation input:
  MarcvdSluys/ConstellationLines @ 1505f7acccd8dee279affed224a46281d26f544c
  ConstellationLines.csv
"""

from __future__ import annotations

import argparse
import csv
from pathlib import Path

EXPECTED_STARS = 9096
EXPECTED_PATHS = 90


def parse_bsc5p(path: Path) -> list[tuple[int, float, float, float]]:
    rows: list[tuple[int, float, float, float]] = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        if not raw:
            continue
        try:
            hr = int(raw[0:4].strip())
            rah = int(raw[75:77].strip())
            ram = int(raw[77:79].strip())
            ras = float(raw[79:83].strip())
            sign = raw[83:84]
            dec_d = int(raw[84:86].strip())
            dec_m = int(raw[86:88].strip())
            dec_s = int(raw[88:90].strip())
            magnitude = float(raw[102:107].strip())
        except (ValueError, IndexError):
            # BSC5P contains 14 historic non-stellar/removed entries whose
            # positional fields are blank. They are deliberately not promoted.
            continue

        ra_deg = (rah + ram / 60.0 + ras / 3600.0) * 15.0
        dec_deg = dec_d + dec_m / 60.0 + dec_s / 3600.0
        if sign == "-":
            dec_deg = -dec_deg
        rows.append((hr, ra_deg, dec_deg, magnitude))

    if len(rows) != EXPECTED_STARS:
        raise SystemExit(
            f"BSC5P count mismatch: expected {EXPECTED_STARS}, got {len(rows)}"
        )
    return rows


def parse_constellations(path: Path) -> list[tuple[str, list[int]]]:
    paths: list[tuple[str, list[int]]] = []
    current_abbreviation = ""
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.reader(handle)
        next(reader, None)
        for row in reader:
            if len(row) < 4:
                continue
            abbreviation = row[0].strip() or current_abbreviation
            if row[0].strip():
                current_abbreviation = abbreviation
            try:
                count = int(row[1].strip())
            except ValueError:
                continue
            hrs = [
                int(value.strip())
                for value in row[2:]
                if value.strip()
            ][:count]
            if abbreviation and len(hrs) >= 2:
                paths.append((abbreviation, hrs))

    if len(paths) != EXPECTED_PATHS:
        raise SystemExit(
            f"Constellation path count mismatch: expected {EXPECTED_PATHS}, got {len(paths)}"
        )
    return paths


def star_asset(stars: list[tuple[int, float, float, float]]) -> str:
    lines = [
        "# HoraTrack Celeste V2 - Bright Star Catalogue BSC5P",
        "# Source: HEASARC Bright Star Catalogue 5th Edition preliminary; fixed-width J2000 fields.",
        "# Columns: hr\tra_j2000_deg\tdec_j2000_deg\tvisual_magnitude",
    ]
    lines.extend(
        f"{hr}\t{ra:.6f}\t{dec:.6f}\t{mag:.2f}"
        for hr, ra, dec, mag in stars
    )
    return "\n".join(lines) + "\n"


def line_asset(paths: list[tuple[str, list[int]]]) -> str:
    lines = [
        "# HoraTrack Celeste V2 - constellation stick figures",
        "# Source: Marc van der Sluys, ConstellationLines, DOI 10.5281/zenodo.10397192",
        "# License: CC BY 4.0",
        "# Format: IAU_abbreviation|ordered_HR_numbers",
    ]
    lines.extend(f"{abbr}|{','.join(map(str, hrs))}" for abbr, hrs in paths)
    return "\n".join(lines) + "\n"


def write_identical(content: str, targets: list[Path]) -> None:
    for target in targets:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bsc5p", type=Path, required=True)
    parser.add_argument("--constellations", type=Path, required=True)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()

    stars = parse_bsc5p(args.bsc5p)
    paths = parse_constellations(args.constellations)
    known = {hr for hr, *_ in stars}
    missing = sorted({hr for _, hrs in paths for hr in hrs if hr not in known})
    if missing:
        raise SystemExit(f"Constellation HR references missing from BSC5P: {missing}")

    stars_text = star_asset(stars)
    paths_text = line_asset(paths)
    root = args.repo_root.resolve()

    write_identical(
        stars_text,
        [
            root / "app/src/main/assets/celestial/bsc5p_v2.tsv",
            root / "ios/HPTravail/HPTravail/CelestialV2/Resources/bsc5p_v2.tsv",
        ],
    )
    write_identical(
        paths_text,
        [
            root / "app/src/main/assets/celestial/constellation_lines_v2.txt",
            root / "ios/HPTravail/HPTravail/CelestialV2/Resources/constellation_lines_v2.txt",
        ],
    )

    print(
        f"OK: {len(stars)} stars, {len(paths)} constellation paths, "
        "0 missing HR references, Android/iOS assets identical."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
