#!/usr/bin/env python3
"""生成三组真实 vivo 双文件实况夹具，用于验证「位置」链路（真实 JPEG 像素 +
真字节 EXIF/XMP + vivo streamdata/footer + 伴生 MP4）。

A: EXIF GPS 完整可用（22°50'20"N / 108°10'40"E），XMP 也有坐标
B: EXIF 的 GPS 段**非空但不可解析**（只有 Lat/Lon Ref），XMP 有坐标
   —— 这正是 v2.7.0 零星丢位置的根因形态：
   `hasGpsExif()`（GPS IFD 非空）= true，但 `gpsCoordinates()` = null
C: EXIF / XMP 都没有 GPS（源本身无位置）

用法：
    python3 tools/make_location_fixtures.py --out build/location-fixtures

依赖：Pillow（pip install pillow）。输出为 A/B/C 三组 <name>.jpg + <name>.mp4，
默认文件名 IMG_9101/9102/9103。

判定口径（与 tools/verify-location-exif.sh 一致）：
- 夹具是「源文件」，B 组在**文件层**就应读不出经纬度（平台 ExifInterface
  `getLatLong` = false）——这是用来证明「GPS 段非空 ≠ 可用坐标」的负样本；
- 转换后（本工具 v2.7.1+）B 组产物应从 XMP 恢复坐标并注入 EXIF，
  那时 `getLatLong` 才应为 true。
"""

import argparse
import io
import json
import os
import random
import struct

from PIL import Image

LIVE_ID = "0123456789abcdef0123456789ab"  # 28 chars，vivo 实况 ID
VIVO_PREFIX = b"vivo"
MAGIC = bytes([0x1B, 0x2A, 0x39, 0x48, 0x57, 0x66, 0x75, 0x84, 0x93, 0xA2, 0xB3])
MARKER = b"cameralbum!"
XMP_PREFIX = b"http://ns.adobe.com/xap/1.0/\x00"


def u16(v):
    return struct.pack(">H", v)


def u32(v):
    return struct.pack(">I", v)


def entry(tag, typ, count, value4):
    assert len(value4) == 4
    return u16(tag) + u16(typ) + u32(count) + value4


class Tiff:
    """最小可用的 big-endian TIFF/EXIF 构造器。

    gps: None（无 GPS IFD）/ "junk"（GPS IFD 只有 Ref）/ "full"（完整三元组）
    TIFF 规则：ASCII count<=4 的 value 字段内联；否则用偏移指向数据区。
    """

    def __init__(self, gps=None):
        self.gps = gps
        self.make = b"vivo\x00"
        self.model = b"vivo X200\x00"

    def build(self):
        n0 = 3 if self.gps else 2
        ifd0_off = 8
        ifd0_size = 2 + 12 * n0 + 4
        gps_ifd_off = ifd0_off + ifd0_size
        n_gps = {"junk": 2, "full": 4}.get(self.gps, 0)
        gps_ifd_size = (2 + 12 * n_gps + 4) if self.gps else 0
        data_off = gps_ifd_off + gps_ifd_size

        make_off = data_off
        model_off = make_off + len(self.make)
        lat_off = model_off + len(self.model)
        lon_off = lat_off + 24

        out = bytearray(b"MM\x00\x2a" + u32(ifd0_off))
        out += u16(n0)
        out += entry(0x010F, 2, len(self.make), u32(make_off))
        out += entry(0x0110, 2, len(self.model), u32(model_off))
        if self.gps:
            out += entry(0x8825, 4, 1, u32(gps_ifd_off))
        out += u32(0)

        if self.gps:
            out += u16(n_gps)
            out += entry(0x0001, 2, 2, b"N\x00\x00\x00")           # LatRef 内联
            if self.gps == "full":
                out += entry(0x0002, 5, 3, u32(lat_off))           # Lat 偏移
            out += entry(0x0003, 2, 2, b"E\x00\x00\x00")           # LonRef 内联
            if self.gps == "full":
                out += entry(0x0004, 5, 3, u32(lon_off))           # Lon 偏移
            out += u32(0)

        out += self.make + self.model
        if self.gps == "full":
            for n, d in ((22, 1), (50, 1), (20, 1)):
                out += u32(n) + u32(d)
            for n, d in ((108, 1), (10, 1), (40, 1)):
                out += u32(n) + u32(d)
        return bytes(out)


def app1(prefix, payload):
    # JPEG 段长 = 2（长度字段本身）+ prefix + payload
    return b"\xff\xe1" + u16(len(prefix) + len(payload) + 2) + prefix + payload


def xmp(with_gps: bool):
    gp = (
        ' exif:GPSLatitude="22.8389" exif:GPSLatitudeRef="N"'
        ' exif:GPSLongitude="108.1780" exif:GPSLongitudeRef="E"'
    ) if with_gps else ""
    return (
        f'<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF '
        f'xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" '
        f'xmlns:exif="http://ns.adobe.com/exif/1.0/" '
        f'dc:format="image/jpeg"><rdf:Description rdf:about=""{gp}/></rdf:RDF></x:xmpmeta>'
    ).encode()


def footer(json_bytes, ident, prefix):
    tail = ident.encode() + b"\xff\xff\xff\xff" + MAGIC
    return prefix + json_bytes + u32(len(json_bytes)) + MARKER + u32(len(tail) + 4) + tail


def footer_json(image_time):
    return json.dumps(
        {
            "com.android.camera.imageTime": image_time,
            "com.android.camera.livephoto": LIVE_ID,
            "version": 2107,
        },
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode()


def make_jpeg(kind):
    img = Image.new("RGB", (320, 240))
    px = img.load()
    seed = {"A": 11, "B": 22, "C": 33}[kind]
    rnd = random.Random(seed)
    for y in range(240):
        for x in range(320):
            px[x, y] = ((x + seed) % 256, (y * 2) % 256, rnd.randrange(256))
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=88)
    raw = buf.getvalue()

    gps = {"A": "full", "B": "junk", "C": None}[kind]
    exif = app1(b"Exif\x00\x00", Tiff(gps).build())
    x = app1(XMP_PREFIX, xmp(kind != "C"))
    body = raw[:2] + exif + x + raw[2:]
    stream = b"streamdataDEGS" + bytes(rnd.randrange(256) for _ in range(64))
    return body + stream + VIVO_PREFIX + footer(footer_json(1700000000 + seed), LIVE_ID, VIVO_PREFIX)


def box(typ, payload):
    return u32(len(payload) + 8) + typ + payload


def make_mp4():
    rnd = random.Random(42)
    ftyp = box(b"ftyp", b"isom\x00\x00\x02\x00isommp41")
    mdat = box(b"mdat", bytes(rnd.randrange(256) for _ in range(2048)))
    moov = box(b"moov", b"")
    estream = box(b"uuid", b"vivoMediaEStream" + bytes(rnd.randrange(256) for _ in range(122)))
    extinfo = box(
        b"uuid",
        b"vivoMediaExtInfo" + footer(footer_json(31), LIVE_ID, b"vivoMediaExtInfovivo"),
    )
    return ftyp + mdat + moov + estream + extinfo


def main():
    ap = argparse.ArgumentParser(description="生成 vivo 双文件实况位置验证夹具")
    ap.add_argument("--out", default="build/location-fixtures", help="输出目录")
    ap.add_argument("--prefix", default="IMG_9101", help="A 组文件名前缀（B/C 自动 +1/+2）")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    mp4 = make_mp4()
    base = os.path.basename(args.prefix)
    stem, _, num = base.rpartition("_")
    start = int(num) if num.isdigit() else 1

    for i, kind in enumerate(("A", "B", "C")):
        name = f"{stem}_{start + i}" if num.isdigit() else f"{base}{'' if i == 0 else i}"
        jpg_path = os.path.join(args.out, name + ".jpg")
        mp4_path = os.path.join(args.out, name + ".mp4")
        with open(jpg_path, "wb") as f:
            f.write(make_jpeg(kind))
        with open(mp4_path, "wb") as f:
            f.write(mp4)
        print(f"{kind} {name}: jpg={os.path.getsize(jpg_path)} mp4={os.path.getsize(mp4_path)}")

    print(f"\n输出目录：{os.path.abspath(args.out)}")
    print("A=EXIF 可用；B=GPS 段非空但不可解析（XMP 有坐标）；C=无 GPS")


if __name__ == "__main__":
    main()
