#!/usr/bin/env bash
# 版本号以根目录 VERSION 为准（Gradle 从这里读 versionName / versionCode）。
#   scripts/version.sh check [vX.Y.Z]  校验 VERSION 格式；给了 tag 时还校验 tag 一致、CHANGELOG 有对应章节
#   scripts/version.sh notes           输出 CHANGELOG 里当前版本的章节（作为 GitHub Release 说明）
#   scripts/version.sh code            输出 versionCode（x*10000 + y*100 + z，与 tv/build.gradle.kts 相同）
#   scripts/version.sh manifest <apk>  输出升级清单 update.json，电视据此检查和下载新版本（update/UpdateManifest.kt）
set -euo pipefail
cd "$(dirname "$0")/.."

version=$(tr -d '[:space:]' < VERSION)
repo=huhengbo/tv-study-helper

section() {
  # 从「## [x.y.z]」到下一个「## [」之前，去掉标题行
  awk -v v="$version" '
    index($0, "## [" v "]") == 1 { on = 1; next }
    on && /^## \[/ { exit }
    on { print }
  ' CHANGELOG.md
}

case "${1:-}" in
  check)
    if ! [[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
      echo "VERSION 格式应为 x.y.z（各段不超过 99），当前：$version" >&2; exit 1
    fi
    IFS=. read -r x y z <<< "$version"
    if (( y > 99 || z > 99 )); then echo "次版本号和修订号不能超过 99（versionCode = x*10000 + y*100 + z）" >&2; exit 1; fi
    if [[ -n ${2:-} ]]; then
      [[ $2 == "v$version" ]] || { echo "tag $2 与 VERSION（$version）不一致" >&2; exit 1; }
      [[ -n $(section | tr -d '[:space:]') ]] || { echo "CHANGELOG.md 缺少 ## [$version] 章节或内容为空" >&2; exit 1; }
    fi
    echo "版本 $version"
    ;;
  notes)
    section | sed -e '/./,$!d'
    ;;
  code)
    IFS=. read -r x y z <<< "$version"
    echo $(( x * 10000 + y * 100 + z ))
    ;;
  manifest)
    apk=${2:?用法：$0 manifest <apk>}
    name=$(basename "$apk")
    NOTES="$(section | sed -e '/./,$!d')" python3 - "$version" "$("$0" code)" "$name" \
      "https://github.com/$repo/releases/download/v$version/$name" "$(wc -c < "$apk")" "$(sha256sum "$apk" | cut -d' ' -f1)" <<'PY'
import json, os, sys, datetime
version, code, name, url, size, sha = sys.argv[1:]
print(json.dumps({
    "format": "studyhelper-update", "version": version, "versionCode": int(code),
    "date": datetime.date.today().isoformat(), "notes": os.environ["NOTES"],
    "apk": {"name": name, "url": url, "size": int(size), "sha256": sha},
}, ensure_ascii=False, indent=2))
PY
    ;;
  *)
    echo "用法：$0 check [vX.Y.Z] | notes | code | manifest <apk>" >&2; exit 2
    ;;
esac
