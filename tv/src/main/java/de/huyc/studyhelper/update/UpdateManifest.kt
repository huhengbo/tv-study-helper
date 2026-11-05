package de.huyc.studyhelper.update

import org.json.JSONObject

/**
 * 升级清单 update.json，由发版流水线（scripts/version.sh manifest）生成并上传到 GitHub Release：
 * ```
 * { "format": "studyhelper-update", "version": "0.2.0", "versionCode": 200, "date": "2026-11-05",
 *   "notes": "CHANGELOG 里这个版本的内容",
 *   "apk": { "name": "tv-study-helper-0.2.0.apk", "url": "https://github.com/…/tv-study-helper-0.2.0.apk",
 *            "size": 4751367, "sha256": "…" } }
 * ```
 * [ApkFile.url] 是 GitHub 原始地址，下载时按 [UpdateSource.MIRRORS] 加镜像前缀。
 */
data class UpdateManifest(
    val version: String,
    val versionCode: Int,
    val date: String,
    val notes: String,
    val apk: ApkFile,
) {
    data class ApkFile(val name: String, val url: String, val size: Long, val sha256: String)

    companion object {
        const val FORMAT = "studyhelper-update"
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val NAME = Regex("[A-Za-z0-9._-]+\\.apk")

        /** 格式不对时抛出 [IllegalArgumentException]（镜像可能返回错误页面）。 */
        fun parse(o: JSONObject): UpdateManifest {
            require(o.optString("format") == FORMAT) { "不是升级清单" }
            val apk = o.optJSONObject("apk") ?: throw IllegalArgumentException("升级清单缺少安装包信息")
            val m = UpdateManifest(
                version = o.optString("version"),
                versionCode = o.optInt("versionCode"),
                date = o.optString("date"),
                notes = o.optString("notes"),
                apk = ApkFile(apk.optString("name"), apk.optString("url"), apk.optLong("size"), apk.optString("sha256").lowercase()),
            )
            require(m.version.isNotBlank() && m.versionCode > 0) { "升级清单缺少版本号" }
            require(NAME.matches(m.apk.name)) { "升级清单里的文件名不对" }
            require(m.apk.url.startsWith(UpdateSource.RELEASE_PREFIX)) { "升级清单里的下载地址不是本项目的发布页" }
            require(m.apk.size in 1..MAX_APK_BYTES) { "升级清单里的文件大小不对" }
            require(SHA256.matches(m.apk.sha256)) { "升级清单里的校验码不对" }
            return m
        }

        const val MAX_APK_BYTES = 100L * 1024 * 1024
    }
}

object UpdateSource {
    const val REPO = "huhengbo/tv-study-helper"
    const val RELEASE_PREFIX = "https://github.com/$REPO/releases/download/"
    const val MANIFEST_URL = "https://github.com/$REPO/releases/latest/download/update.json"

    /**
     * GitHub 地址前缀镜像，空串为直连。国内直连 GitHub 常常很慢，检查时同时请求、谁先返回先用谁。
     * 镜像是第三方服务，安装前会核对校验码和签名，被篡改的安装包装不上。
     * 2026-11 在国内家庭宽带实测可用；失效时从这里删掉、补上新的即可。
     */
    val MIRRORS = listOf(
        "",
        "https://ghfast.top/",
        "https://gh-proxy.org/",
        "https://cdn.gh-proxy.com/",
        "https://gh-proxy.com/",
    )
}
