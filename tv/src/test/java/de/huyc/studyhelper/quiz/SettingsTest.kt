package de.huyc.studyhelper.quiz

import de.huyc.studyhelper.data.MathConfig
import de.huyc.studyhelper.data.Settings
import de.huyc.studyhelper.data.VoiceConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {

    @Test fun maskedSecretsAreKeptOnMerge() {
        val s = Settings(voice = VoiceConfig(baiduApiKey = "ak", baiduSecretKey = "sk"))
        val fromPhone = s.toJson(maskSecrets = true)
        val merged = s.merge(fromPhone)
        assertEquals("sk", merged.voice.baiduSecretKey)
        assertEquals("1234", merged.pin) // 掩码后的空 PIN 不修改
    }

    @Test fun roundTripThroughFile() {
        val s = Settings(watchMinutes = 45, pin = "8765", apps = listOf("a.b"))
        assertEquals(s, Settings.fromJson(JSONObject(s.toJson().toString())))
    }

    @Test fun invalidValuesAreClamped() {
        val merged = Settings().merge(JSONObject().put("watchMinutes", 0).put("questionCount", 99).put("pin", "12"))
        assertEquals(1, merged.watchMinutes)
        assertEquals(20, merged.questionCount)
        assertEquals("1234", merged.pin)
    }
}

class MigrationTest {
    @Test fun oldTypesAndHanziChoiceBecomeSubjects() {
        val old = org.json.JSONObject("""{"types":["MATH","HANZI"],"hanziMode":"CHOICE","pin":"5678"}""")
        val s = Settings.fromJson(old)
        assertEquals(setOf(de.huyc.studyhelper.data.Subject.MATH, de.huyc.studyhelper.data.Subject.HANZI, de.huyc.studyhelper.data.Subject.PINYIN), s.subjects)
        assertEquals("5678", s.pin)
    }

    @Test fun oldQuestionJsonWithTypeField() {
        val q = de.huyc.studyhelper.data.Question.fromJson(org.json.JSONObject("""{"id":"a","type":"HANZI","prompt":"p","display":"山","answers":["山"]}"""))
        assertEquals(de.huyc.studyhelper.data.Subject.HANZI, q.subject)
        assertEquals(de.huyc.studyhelper.data.AnswerMode.VOICE, q.mode)
    }

    @Test fun settingsFromBeforeLearningFeaturesGetDefaults() {
        val old = JSONObject().put("watchMinutes", 40).put("math", JSONObject().put("max", 50))
        val s = Settings.fromJson(old)
        assertEquals(true, s.reviewWrong)
        assertEquals(true, s.soundEffects)
        assertEquals(false, s.math.adaptive)
        assertEquals(50, s.math.max)
    }

    @Test fun learningSettingsRoundTrip() {
        val s = Settings(reviewWrong = false, soundEffects = false, math = MathConfig(adaptive = true))
        assertEquals(s, Settings.fromJson(JSONObject(s.toJson().toString())))
    }
}
