#!/usr/bin/env python3
"""Reject truncated, transparent or wrongly wired HoraTrack launcher icons."""

from __future__ import annotations

import binascii
import hashlib
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
import zlib


ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "app/src/main/res/mipmap-nodpi"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
COLORS = ("red", "green", "orange")
ANDROID = "{http://schemas.android.com/apk/res/android}"


def verify_png(path: Path) -> str:
    data = path.read_bytes()
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise ValueError(f"{path}: signature PNG absente")

    offset = 8
    header: tuple[int, int, int, int, int, int, int] | None = None
    compressed = bytearray()
    found_end = False

    while offset < len(data):
        if offset + 12 > len(data):
            raise ValueError(f"{path}: bloc PNG tronque")
        length = struct.unpack(">I", data[offset : offset + 4])[0]
        chunk_type = data[offset + 4 : offset + 8]
        chunk_end = offset + 12 + length
        if chunk_end > len(data):
            raise ValueError(f"{path}: donnees PNG tronquees")
        payload = data[offset + 8 : offset + 8 + length]
        stored_crc = struct.unpack(">I", data[offset + 8 + length : chunk_end])[0]
        actual_crc = binascii.crc32(chunk_type + payload) & 0xFFFFFFFF
        if stored_crc != actual_crc:
            raise ValueError(f"{path}: CRC PNG invalide pour {chunk_type!r}")

        if chunk_type == b"IHDR":
            header = struct.unpack(">IIBBBBB", payload)
        elif chunk_type == b"IDAT":
            compressed.extend(payload)
        elif chunk_type == b"IEND":
            found_end = True
            if chunk_end != len(data):
                raise ValueError(f"{path}: octets inattendus apres IEND")
            break
        offset = chunk_end

    if header != (512, 512, 8, 2, 0, 0, 0):
        raise ValueError(
            f"{path}: attendu 512x512 RGB opaque 8 bits non entrelace, obtenu {header}"
        )
    if not found_end:
        raise ValueError(f"{path}: bloc IEND absent")

    pixels = zlib.decompress(compressed)
    row_size = 1 + 512 * 3
    if len(pixels) != 512 * row_size:
        raise ValueError(f"{path}: flux pixels incomplet")
    if any(pixels[row * row_size] > 4 for row in range(512)):
        raise ValueError(f"{path}: filtre de ligne PNG invalide")

    return hashlib.sha256(data).hexdigest()


def verify_manifest() -> None:
    tree = ET.parse(MANIFEST)
    application = tree.getroot().find("application")
    if application is None:
        raise ValueError("balise application absente du manifeste")

    red_resource = "@mipmap/horatrack_icon_red_v2"
    if application.get(ANDROID + "icon") != red_resource:
        raise ValueError("l icone application ne pointe pas vers la nouvelle ressource rouge")
    if application.get(ANDROID + "roundIcon") != red_resource:
        raise ValueError("roundIcon ne pointe pas vers la nouvelle ressource rouge")

    aliases = {
        alias.get(ANDROID + "name"): alias.get(ANDROID + "icon")
        for alias in application.findall("activity-alias")
    }
    expected = {
        ".IconRedV2": red_resource,
        ".IconGreenV2": "@mipmap/horatrack_icon_green_v2",
        ".IconOrangeV2": "@mipmap/horatrack_icon_orange_v2",
    }
    if aliases != expected:
        raise ValueError(f"activity-alias launcher inattendus: {aliases}")


def main() -> None:
    digests = []
    for color in COLORS:
        path = RESOURCES / f"horatrack_icon_{color}_v2.png"
        digests.append(verify_png(path))
        print(f"OK {color}: {path.name}")
    if len(set(digests)) != len(COLORS):
        raise ValueError("les trois icones doivent etre distinctes")
    verify_manifest()
    print("OK manifeste: nouvelles ressources et nouveaux alias launcher")


if __name__ == "__main__":
    main()
