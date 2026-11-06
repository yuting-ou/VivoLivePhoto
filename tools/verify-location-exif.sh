#!/usr/bin/env bash
# tools/verify-location-exif.sh —— 「位置交付」平台机制可重跑验证装置
#
# ① 验证目的：验证「相册显示位置 ⇔ 文件 EXIF 可解析」这一平台机制。
#    相册/第三方应用判定照片有没有位置，依据是**文件里**的 EXIF GPS 能否被
#    平台 ExifInterface 解析出经纬度（getLatLong），而不是 MediaStore 数据库
#    行里的 latitude/longitude 列。
#
# ② 判定纪律：AOSP/Android 10+ 上，MediaStore 行坐标（latitude/longitude 列）
#    为 NULL 是**预期行为、不算失败**——平台早已把坐标列废弃为按行写入的可选
#    附加项。本脚本仍会查询并打印行坐标，但**仅作记录、不参与判定**；
#    判定一律用平台 ExifInterface 读文件字节（tools/android-probe/ExifProbe.java，
#    app_process 里跑系统自身的实现，与相册同一套判据）。
#
# ③ ⚠ 排查纪律：**绝不**用 sqlite3 直读设备正在使用的 external.db。
#    WAL 不一致会导致 MediaProvider SQLiteDiskIOException (code 522) 崩库，
#    实测踩过。离线分析必须先把 db + -wal + -shm 三个文件一起拷到
#    /data/local/tmp 之类的旁路位置再只读打开。
#
# 流程（对 A/B/C 三组夹具）：
#   1) python3 tools/make_location_fixtures.py 生成夹具
#      A = EXIF GPS 完整可用；B = EXIF GPS 段非空但不可解析（XMP 有坐标）；
#      C = 无 GPS。
#   2) adb push 到设备 /sdcard/DCIM/Camera/_vlc_verify/ 并入库（scan_file）。
#   3) content query 记录 MediaStore 行坐标（仅记录，不判失败，见 ②）。
#   4) 编译并运行 ExifProbe（javac + d8 + app_process，见其 docstring），
#      用平台 ExifInterface 判定**文件层**是否解出经纬度。
#      判定口径：A 组文件层 USABLE（getLatLong=true）；B/C 组 =0；
#      B 组转换后才应变为 true（转换器会从 XMP 恢复坐标注入 EXIF）。
#   5) 重扫前后 md5sum 对照，确认验证过程本身未改动文件字节。
#
# 用法：
#   ANDROID_SERIAL=emulator-5554 tools/verify-location-exif.sh
#   ANDROID_SERIAL 必须显式设置（防止误连真机）；VLC_KEEP=1 可保留设备侧夹具。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SERIAL="${ANDROID_SERIAL:-}"
if [ -z "$SERIAL" ]; then
  echo "错误：必须显式设置 ANDROID_SERIAL（如 ANDROID_SERIAL=emulator-5554），防止误连真机" >&2
  exit 2
fi
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
# adb shell 传多个参数会被远端 shell 二次分词（--where 里的空格/等号会被拆散），
# 因此所有带引号语法的命令一律用 axsh 传**单个**完整命令字符串。
ax() { "$ADB" -s "$SERIAL" "$@"; }
axsh() { "$ADB" -s "$SERIAL" shell "$1"; }

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ANDROID_JAR="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
D8="$(ls "$SDK"/build-tools/*/d8 2>/dev/null | sort -V | tail -1)"
if [ -z "${ANDROID_JAR:-}" ] || [ -z "${D8:-}" ]; then
  echo "错误：找不到 android.jar 或 d8（SDK=$SDK）" >&2
  exit 2
fi
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC="$JAVA_HOME/bin/javac"
elif [ -x /opt/homebrew/opt/openjdk/bin/javac ]; then
  JAVAC=/opt/homebrew/opt/openjdk/bin/javac
else
  JAVAC=javac
fi

PREFIX="${VLC_FIXTURE_PREFIX:-IMG_9101}"
STEM="${PREFIX%_*}"
NUM="${PREFIX##*_}"
A_NAME="${STEM}_${NUM}"
B_NAME="${STEM}_$((NUM + 1))"
C_NAME="${STEM}_$((NUM + 2))"
DEV_DIR=/sdcard/DCIM/Camera/_vlc_verify
WORK="build/location-verify-$$"
mkdir -p "$WORK"

echo "== 0) 环境 =="
echo "serial=$SERIAL  android.jar=$ANDROID_JAR"
echo "javac=$($JAVAC -version 2>&1)  d8=$D8"

echo "== 1) 生成夹具 =="
python3 tools/make_location_fixtures.py --out "$WORK" --prefix "$PREFIX"
FIX="$ROOT/$WORK"

echo "== 2) 推送并入库 =="
for f in "$A_NAME.jpg" "$A_NAME.mp4" "$B_NAME.jpg" "$B_NAME.mp4" "$C_NAME.jpg" "$C_NAME.mp4"; do
  ax push "$FIX/$f" "$DEV_DIR/$f" >/dev/null
  axsh "content call --uri content://media/ --method scan_file --arg $DEV_DIR/$f" >/dev/null
done
echo "已推送 $DEV_DIR/{${A_NAME},${B_NAME},${C_NAME}}.{jpg,mp4} 并逐个 scan_file"

echo "== 3) MediaStore 行坐标（仅记录，不判失败，见头部②）=="
for f in "$A_NAME.jpg" "$B_NAME.jpg" "$C_NAME.jpg"; do
  for col in latitude longitude; do
    axsh "content query --uri content://media/external/images/media \
--projection $col --where \"_display_name = '$f'\"" | sed "s/^/  $f $col /"
  done
done

echo "== 4) 平台 ExifInterface 读文件判定（ExifProbe）=="
"$JAVAC" --release 17 -cp "$ANDROID_JAR" -d "$WORK/classes" tools/android-probe/ExifProbe.java
mkdir -p "$WORK/dex"   # d8 --output 要求目录已存在
"$D8" --release --lib "$ANDROID_JAR" --min-api 29 --output "$WORK/dex" "$WORK/classes/ExifProbe.class"
ax shell mkdir -p /data/local/tmp/vlc_exifprobe
ax push "$WORK/dex/classes.dex" /data/local/tmp/vlc_exifprobe/classes.dex >/dev/null
PROBE_OUT="$WORK/exifprobe.out"
ax shell "CLASSPATH=/data/local/tmp/vlc_exifprobe/classes.dex app_process /system/bin ExifProbe \
  $DEV_DIR/$A_NAME.jpg $DEV_DIR/$B_NAME.jpg $DEV_DIR/$C_NAME.jpg" | tee "$PROBE_OUT"

echo "== 5) 重扫前后 md5 对照（验证本身不改字节）=="
md5_ok=1
for f in "$A_NAME.jpg" "$B_NAME.jpg" "$C_NAME.jpg"; do
  axsh "content call --uri content://media/ --method scan_file --arg $DEV_DIR/$f" >/dev/null
  m1="$(axsh "md5sum $DEV_DIR/$f" | cut -d' ' -f1 | tr -d '\r')"
  axsh "content call --uri content://media/ --method scan_file --arg $DEV_DIR/$f" >/dev/null
  m2="$(axsh "md5sum $DEV_DIR/$f" | cut -d' ' -f1 | tr -d '\r')"
  if [ "$m1" = "$m2" ]; then echo "  $f md5 一致 $m1"; else echo "  $f md5 不一致 $m1 != $m2"; md5_ok=0; fi
done

echo "== 6) 判定 =="
getll() { awk -v file="$DEV_DIR/$1" '
  $1 == "FILE" { cur = $2 }
  /getLatLong=/ { if (cur == file) { sub(/^.*getLatLong=/, ""); print } }
' "$PROBE_OUT"; }
verdict=1
ll_a="$(getll "$A_NAME.jpg")"; ll_b="$(getll "$B_NAME.jpg")"; ll_c="$(getll "$C_NAME.jpg")"
if [ "$ll_a" != "null/false" ]; then echo "  A $A_NAME.jpg：USABLE ($ll_a) —— 符合预期"; else echo "  A $A_NAME.jpg：期望可用坐标，实得 $ll_a —— 不符合"; verdict=0; fi
if [ "$ll_b" = "null/false" ]; then echo "  B $B_NAME.jpg：文件层不可解析 —— 符合预期（GPS 段非空≠可用；转换后应为 true）"; else echo "  B $B_NAME.jpg：期望不可解析，实得 $ll_b —— 不符合"; verdict=0; fi
if [ "$ll_c" = "null/false" ]; then echo "  C $C_NAME.jpg：无位置 —— 符合预期"; else echo "  C $C_NAME.jpg：期望无位置，实得 $ll_c —— 不符合"; verdict=0; fi
if [ "$md5_ok" = 1 ]; then echo "  md5 前后一致 —— 符合预期"; else echo "  md5 前后不一致 —— 不符合"; verdict=0; fi

cleanup() {
  if [ "${VLC_KEEP:-0}" = "1" ]; then
    echo "== 设备侧夹具保留（VLC_KEEP=1）：$DEV_DIR =="
    return
  fi
  for f in "$A_NAME.jpg" "$A_NAME.mp4" "$B_NAME.jpg" "$B_NAME.mp4" "$C_NAME.jpg" "$C_NAME.mp4"; do
    axsh "rm $DEV_DIR/$f" 2>/dev/null || true
    axsh "content call --uri content://media/ --method scan_file --arg $DEV_DIR/$f" >/dev/null 2>&1 || true
  done
  axsh "rmdir $DEV_DIR" 2>/dev/null || true
  axsh "rm -r /data/local/tmp/vlc_exifprobe" 2>/dev/null || true
  echo "== 已清理设备侧夹具与探针 =="
}
cleanup

if [ "$verdict" = 1 ]; then
  echo "结果：PASS —— 相册显示位置 ⇔ 文件 EXIF 可解析（平台 ExifInterface）机制成立"
  exit 0
else
  echo "结果：FAIL —— 见上方各行与 $PROBE_OUT"
  exit 1
fi
