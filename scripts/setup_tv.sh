#!/usr/bin/env bash
# 用电脑一次性安装并授权「学习小助手」（备用方式；应用本身已带「开启必要权限」引导，见 README）。
# 用法：scripts/setup_tv.sh <电视IP> [apk路径]
# 电视需先打开：设置 → 账号与安全 → ADB 调试（以及「安装未知来源应用」）。
set -euo pipefail

IP="${1:?用法: $0 <电视IP> [apk路径]}"
APK="${2:-$(dirname "$0")/../tv/build/outputs/apk/release/tv-release.apk}"
PKG=de.huyc.studyhelper
A11Y="$PKG/$PKG.core.GuardAccessibilityService"
DEV="$IP:5555"

adb connect "$DEV" >/dev/null
adb -s "$DEV" wait-for-device
sh() { adb -s "$DEV" shell "$@" | tr -d '\r'; }

echo "▶ 安装 $APK（这台电视较慢，约需 3~5 分钟）"
adb -s "$DEV" install -r -g "$APK"

echo "▶ 授权：录音、悬浮窗、使用情况访问"
sh pm grant "$PKG" android.permission.RECORD_AUDIO
sh appops set "$PKG" SYSTEM_ALERT_WINDOW allow
sh appops set "$PKG" GET_USAGE_STATS allow

echo "▶ 开启无障碍服务（保留系统已有的服务，如小爱同学）"
current="$(sh settings get secure enabled_accessibility_services)"
case "$current" in
  *"$A11Y"*) ;;
  ""|null) sh settings put secure enabled_accessibility_services "$A11Y" ;;
  *) sh settings put secure enabled_accessibility_services "$current:$A11Y" ;;
esac
sh settings put secure accessibility_enabled 1

echo "▶ 启动"
sh am start -n "$PKG/.ui.MainActivity" >/dev/null
echo "✅ 完成。电视上会显示管理二维码，用手机扫码即可设置（默认家长密码 1234，请尽快修改）。"
