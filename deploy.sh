#!/usr/bin/env bash
# Deploy DjiParam.apk to the DJI RC 2: MTP push to internal storage, then `pm install`
# over telnet (system shell). adb is unusable on the RC (USB-debugging off, no root).
#
#   ./deploy.sh            # push existing build/DjiParam.apk + install
#   ./deploy.sh --build    # build first (build.ps1), then push + install
#
# Configuration via environment variables:
#   RC2_MTP       path to an MTP push helper (WPD/IFileOperation CLI). See README §Deploy.
#   RC2_DEVICE    MTP device name                (default: "DJI RC 2")
#   RC2_STORAGE   internal-storage volume name   (default: "Внутренний общий накопитель")
#   RC2_HOST      telnet host of the RC shell    (used by tools/rc2sh.py)
#
# Must run under Bash (Git Bash on Windows): PowerShell mangles the Cyrillic storage name.
set -e

PROJ="$(cd "$(dirname "$0")" && pwd)"
APK="$PROJ/build/DjiParam.apk"
RC2="${RC2_MTP:-}"
DEV="${RC2_DEVICE:-DJI RC 2}"
INTERNAL="${RC2_STORAGE:-Внутренний общий накопитель}"

if [ "$1" = "--build" ]; then
  echo "[build] build.ps1"
  powershell -ExecutionPolicy Bypass -File "$PROJ/build.ps1"
fi

[ -f "$APK" ] || { echo "no APK at $APK — run with --build first"; exit 1; }

if [ -z "$RC2" ]; then
  echo "RC2_MTP is not set — no MTP push helper configured."
  echo "Either set RC2_MTP to your WPD push CLI, or copy build/DjiParam.apk to the RC"
  echo "internal storage manually (as /sdcard/DjiParam.apk), then run the install step below."
else
  echo "[1/3] MTP push -> internal /sdcard/DjiParam.apk"
  MSYS_NO_PATHCONV=1 "$RC2" pushfile "$DEV" "$(cygpath -w "$APK")" "DjiParam.apk" "$INTERNAL"
fi

echo "[2/3] pm install over telnet"
python "$PROJ/tools/rc2sh.py" "pm install -r -g /sdcard/DjiParam.apk"

echo "[3/3] verify"
python "$PROJ/tools/rc2sh.py" "dumpsys package com.djiparam | grep versionName"
echo "DONE. Launch on the RC, or: python tools/rc2sh.py \"am start -n com.djiparam/.MainActivity\""
