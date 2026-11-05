package de.huyc.studyhelper.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateManifestTest {

    private fun manifest(edit: JSONObject.() -> Unit = {}) = JSONObject("""
        { "format": "studyhelper-update", "version": "0.2.0", "versionCode": 200, "date": "2026-11-05",
          "notes": "### ✨ 新功能\n\n- 升级", "apk": { "name": "tv-study-helper-0.2.0.apk",
          "url": "https://github.com/huhengbo/tv-study-helper/releases/download/v0.2.0/tv-study-helper-0.2.0.apk",
          "size": 4751367, "sha256": "${"AB".repeat(32)}" } }
    """).apply(edit)

    @Test fun parsesManifestFromReleaseScript() {
        val m = UpdateManifest.parse(manifest())
        assertEquals(200, m.versionCode)
        assertEquals("ab".repeat(32), m.apk.sha256)
        assertEquals(4751367L, m.apk.size)
    }

    @Test fun rejectsErrorPagesAndForeignDownloads() {
        // 镜像出错时可能返回 HTML 或别的 JSON
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(JSONObject("{\"message\":\"Not Found\"}")) }
        // 下载地址必须是本项目的发布页，镜像只能加前缀
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(manifest { getJSONObject("apk").put("url", "https://evil.example/app.apk") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(manifest { getJSONObject("apk").put("sha256", "123") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(manifest { getJSONObject("apk").put("name", "../x.apk") })
        }
    }
}
