"""Dependency-free PNG helpers: write grayscale PNGs, parse image dimensions."""

from __future__ import annotations

import struct
import zlib


def _chunk(tag: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + tag
        + data
        + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


def write_gray_png(width: int, height: int, pixel_fn) -> bytes:
    """Build a valid 8-bit grayscale PNG. pixel_fn(x, y) -> 0..255."""
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter type: none
        for x in range(width):
            raw.append(max(0, min(255, int(pixel_fn(x, y)))))
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 0, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + _chunk(b"IHDR", ihdr)
        + _chunk(b"IDAT", zlib.compress(bytes(raw), 6))
        + _chunk(b"IEND", b"")
    )


def synthetic_frame(width: int = 96, height: int = 72, seed: int = 0) -> bytes:
    """Deterministic gradient test frame used when no camera is available."""
    return write_gray_png(width, height, lambda x, y: (x * 2 + y * 3 + seed) % 256)


def image_dimensions(data: bytes) -> tuple[int, int] | None:
    """Parse (width, height) from PNG / GIF / BMP / JPEG headers without PIL."""
    if len(data) < 26:
        return None
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        w, h = struct.unpack(">II", data[16:24])
        return w, h
    if data[:6] in (b"GIF87a", b"GIF89a"):
        w, h = struct.unpack("<HH", data[6:10])
        return w, h
    if data[:2] == b"BM":
        w, h = struct.unpack("<ii", data[18:26])
        return abs(w), abs(h)
    if data[:2] == b"\xff\xd8":  # JPEG: scan for SOF markers
        i = 2
        while i + 9 < len(data):
            if data[i] != 0xFF:
                i += 1
                continue
            marker = data[i + 1]
            if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                h, w = struct.unpack(">HH", data[i + 5:i + 9])
                return w, h
            length = struct.unpack(">H", data[i + 2:i + 4])[0]
            i += 2 + length
        return None
    return None


def mean_gray_of_png(data: bytes) -> float | None:
    """Mean brightness for the simple grayscale PNGs we generate ourselves."""
    try:
        if data[:8] != b"\x89PNG\r\n\x1a\n":
            return None
        width, height = struct.unpack(">II", data[16:24])
        bit_depth, color_type = data[24], data[25]
        if bit_depth != 8 or color_type != 0:
            return None
        idat = b""
        i = 8
        while i + 8 <= len(data):
            (length,) = struct.unpack(">I", data[i:i + 4])
            tag = data[i + 4:i + 8]
            if tag == b"IDAT":
                idat += data[i + 8:i + 8 + length]
            if tag == b"IEND":
                break
            i += 12 + length
        raw = zlib.decompress(idat)
        total = count = 0
        stride = width + 1
        for y in range(height):
            row = raw[y * stride + 1:(y + 1) * stride]
            total += sum(row)
            count += len(row)
        return round(total / count, 2) if count else None
    except Exception:
        return None
