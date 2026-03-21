package com.xware

import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

/**
 * XWare Android Bridge
 * ────────────────────────────────────────────────────────────
 * JavaScript(index.html / app.js) ↔ Kotlin 양방향 통신 브리지.
 *
 * JS 측: window.chrome.webview.postMessage(JSON) → 이 클래스의 @JavascriptInterface 메서드 호출
 * Kotlin 측: sendToJs(payload) → webView.evaluateJavascript("window.__xw(...)") 로 JS에 결과 전달
 *
 * 지원 기능:
 *  - YouTube InnerTube API 검색
 *  - YouTube 자동완성 제안
 *  - lrclib.net 가사 fetch
 *  - 오버레이 모드 제어 (Android 시스템 오버레이)
 *  - 앱 제목/알림 업데이트
 */
class AndroidBridge(
    private val activityRef: WeakReference<MainActivity>,
    private val webView: WebView,
    private val scope: CoroutineScope
) {

    // ── JS → Kotlin 메시지 수신 ────────────────────────
    @JavascriptInterface
    fun postMessage(jsonStr: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val msg = JSONObject(jsonStr)
                when (msg.optString("type")) {
                    "search"      -> doSearch(
                        msg.optString("query", ""),
                        msg.optString("id", "0")
                    )
                    "suggest"     -> doSuggest(
                        msg.optString("query", ""),
                        msg.optString("id", "0")
                    )
                    "fetchLyrics" -> doFetchLyrics(
                        msg.optString("title", ""),
                        msg.optString("channel", ""),
                        msg.optDouble("duration", 0.0),
                        msg.optString("id", "0")
                    )
                    "overlayMode" -> {
                        val active = msg.optBoolean("active", false)
                        withContext(Dispatchers.Main) {
                            activityRef.get()?.setOverlayMode(active)
                        }
                    }
                    "overlayLyrics" -> {
                        val prev   = msg.optString("prev", "")
                        val active = msg.optString("active", "")
                        val next1  = msg.optString("next1", "")
                        withContext(Dispatchers.Main) {
                            activityRef.get()?.updateOverlayLyrics(prev, active, next1)
                        }
                    }
                    "setTitle" -> {
                        val title = msg.optString("title", "X-WARE")
                        withContext(Dispatchers.Main) {
                            activityRef.get()?.updateNotificationTitle(title)
                            // 오버레이 버블 타이틀도 동기화
                            activityRef.get()?.syncTrackToOverlay(title, "")
                        }
                    }
                    // bridge.js 에서 updPlay 인터셉트 → 재생상태 → 버블 아이콘 동기화
                    "playState" -> {
                        val playing = msg.optBoolean("playing", false)
                        withContext(Dispatchers.Main) {
                            activityRef.get()?.syncPlayStateToOverlay(playing)
                        }
                    }
                    // bridge.js 에서 playTrack 인터셉트 → 트랙 정보 → 버블 패널 타이틀
                    "trackChanged" -> {
                        val title = msg.optString("title", "")
                        val thumb = msg.optString("thumb", "")
                        withContext(Dispatchers.Main) {
                            activityRef.get()?.syncTrackToOverlay(title, thumb)
                        }
                    }
                    "minimize" -> withContext(Dispatchers.Main) {
                        activityRef.get()?.moveTaskToBack(true)
                    }
                    "close" -> withContext(Dispatchers.Main) {
                        activityRef.get()?.finish()
                    }
                    "drag", "maximize" -> { /* Android에서는 무시 */ }
                }
            } catch (e: Exception) {
                android.util.Log.e("XWareBridge", "postMessage parse error", e)
            }
        }
    }

    // ── Kotlin → JS 결과 전송 ────────────────────────
    // ★ 한국어 깨짐 원인: atob() = Latin-1 반환 → UTF-8 한국어 파괴
    //   TextDecoder('utf-8') 로 올바르게 디코딩
    fun sendToJs(payload: JSONObject) {
        val json = payload.toString()
        val b64  = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val js = buildString {
            append("(function(){")
            append("try{")
            append("var b=atob('"); append(b64); append("');")
            append("var a=new Uint8Array(b.length);")
            append("for(var i=0;i<b.length;i++)a[i]=b.charCodeAt(i);")
            append("window.__xw&&window.__xw(new TextDecoder('utf-8').decode(a));")
            append("}catch(e){console.error('[XW]'+e);}")
            append("})()")
        }
        activityRef.get()?.runOnUiThread {
            webView.evaluateJavascript(js, null)
        }
    }

    // ════════════════════════════════════════════
    //  YouTube InnerTube 검색
    // ════════════════════════════════════════════
    private fun doSearch(query: String, callbackId: String) {
        try {
            val body = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20240101.00.00")
                        put("hl", "ko")
                        put("gl", "KR")
                    })
                })
                put("query", query)
                put("params", "EgIQAQ%3D%3D")
            }

            val url = "https://www.youtube.com/youtubei/v1/search?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false"
            val json = httpPost(url, body.toString(), mapOf(
                "Content-Type"            to "application/json",
                "X-YouTube-Client-Name"   to "1",
                "X-YouTube-Client-Version" to "2.20240101.00.00",
                "Origin"                  to "https://www.youtube.com",
                "Referer"                 to "https://www.youtube.com/"
            ))

            val tracks = parseSearchResults(json)
            val result = JSONObject().apply {
                put("type",    "searchResult")
                put("id",      callbackId)
                put("success", true)
                put("tracks",  tracks)
            }
            sendToJs(result)

        } catch (e: Exception) {
            sendToJs(JSONObject().apply {
                put("type",    "searchResult")
                put("id",      callbackId)
                put("success", false)
                put("error",   e.message ?: "검색 실패")
            })
        }
    }

    private fun parseSearchResults(json: String): JSONArray {
        val list = JSONArray()
        try {
            val doc = JSONObject(json)
            val sections = doc
                .getJSONObject("contents")
                .getJSONObject("twoColumnSearchResultsRenderer")
                .getJSONObject("primaryContents")
                .getJSONObject("sectionListRenderer")
                .getJSONArray("contents")

            for (si in 0 until sections.length()) {
                val sec = sections.getJSONObject(si)
                if (!sec.has("itemSectionRenderer")) continue
                val items = sec.getJSONObject("itemSectionRenderer")
                    .optJSONArray("contents") ?: continue

                for (ii in 0 until items.length()) {
                    if (list.length() >= 20) break
                    val item = items.getJSONObject(ii)
                    if (!item.has("videoRenderer")) continue
                    val vr = item.getJSONObject("videoRenderer")
                    val id = vr.optString("videoId", "")
                    if (id.isEmpty()) continue

                    val title  = vr.optJSONObject("title")
                        ?.optJSONArray("runs")?.optJSONObject(0)
                        ?.optString("text", "") ?: ""

                    val channel = (vr.optJSONObject("ownerText")
                        ?: vr.optJSONObject("shortBylineText"))
                        ?.optJSONArray("runs")?.optJSONObject(0)
                        ?.optString("text", "") ?: ""

                    val durStr = vr.optJSONObject("lengthText")
                        ?.optString("simpleText", "") ?: ""
                    val dur = parseDuration(durStr)

                    if (!isMusicVideo(title, channel, dur)) continue

                    list.put(JSONObject().apply {
                        put("id",      id)
                        put("title",   title)
                        put("channel", channel)
                        put("dur",     dur)
                        put("thumb",   "https://i.ytimg.com/vi/$id/mqdefault.jpg")
                    })
                }
                if (list.length() >= 20) break
            }
        } catch (e: Exception) {
            android.util.Log.e("XWareBridge", "parseSearchResults error", e)
        }
        return list
    }

    private fun isMusicVideo(title: String, channel: String, dur: Int): Boolean {
        val tl = title.lowercase()
        val cl = channel.lowercase()
        if (cl.contains("vevo") || cl.contains("topic") || cl.contains("music") ||
            cl.contains("records") || cl.contains("official")) return true
        if (tl.contains("official") || tl.contains("mv") || tl.contains("m/v") ||
            tl.contains("music video") || tl.contains("lyrics") || tl.contains("live")) return true
        if (dur >= 60) return true
        return false
    }

    private fun parseDuration(s: String): Int {
        if (s.isEmpty()) return 0
        return try {
            val p = s.split(":")
            when (p.size) {
                3 -> p[0].toInt() * 3600 + p[1].toInt() * 60 + p[2].toInt()
                2 -> p[0].toInt() * 60 + p[1].toInt()
                else -> 0
            }
        } catch (e: Exception) { 0 }
    }

    // ════════════════════════════════════════════
    //  YouTube 자동완성 제안
    // ════════════════════════════════════════════
    private fun doSuggest(query: String, callbackId: String) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=$encoded&hl=ko"
            val json = httpGet(url)

            val arr = JSONArray(json)
            val suggestions = JSONArray()
            if (arr.length() > 1) {
                val items = arr.getJSONArray(1)
                for (i in 0 until minOf(8, items.length())) {
                    val s = items.optString(i, "")
                    if (s.isNotEmpty()) suggestions.put(s)
                }
            }

            sendToJs(JSONObject().apply {
                put("type",        "suggestResult")
                put("id",          callbackId)
                put("success",     true)
                put("suggestions", suggestions)
            })

        } catch (e: Exception) {
            sendToJs(JSONObject().apply {
                put("type",        "suggestResult")
                put("id",          callbackId)
                put("success",     false)
                put("suggestions", JSONArray())
                put("error",       e.message ?: "제안 실패")
            })
        }
    }

    // ════════════════════════════════════════════
    //  lrclib.net 가사 fetch
    // ════════════════════════════════════════════
    private fun doFetchLyrics(rawTitle: String, channel: String, ytDuration: Double, callbackId: String) {
        try {
            val cleanTitle  = cleanTitle(rawTitle)
            val cleanArtist = cleanArtist(channel)

            var results = searchLrclib("$cleanTitle $cleanArtist")
            if (results.length() == 0) results = searchLrclib(cleanTitle)

            if (results.length() == 0) {
                sendToJs(JSONObject().apply {
                    put("type",    "lyricsResult")
                    put("id",      callbackId)
                    put("success", false)
                    put("lines",   JSONArray())
                })
                return
            }

            // syncedLyrics 있는 후보 수집
            data class Candidate(val lrc: String, val lrcDur: Double)
            val candidates = mutableListOf<Candidate>()

            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val lrcText = item.optString("syncedLyrics", "")
                if (lrcText.isBlank()) continue
                var lrcDur = getLrcLastTimestamp(lrcText)
                if (lrcDur <= 0) lrcDur = item.optDouble("duration", 0.0)
                candidates.add(Candidate(lrcText, lrcDur))
            }

            if (candidates.isEmpty()) {
                sendToJs(JSONObject().apply {
                    put("type",    "lyricsResult")
                    put("id",      callbackId)
                    put("success", false)
                    put("lines",   JSONArray())
                })
                return
            }

            // YouTube 재생 시간과 가장 가까운 후보 선택
            val bestLrc = if (ytDuration > 0) {
                val withDur = candidates.filter { it.lrcDur > 0 }
                if (withDur.isNotEmpty())
                    withDur.minByOrNull { Math.abs(it.lrcDur - ytDuration) }!!.lrc
                else candidates[0].lrc
            } else candidates[0].lrc

            val lines = parseLrc(bestLrc)
            sendToJs(JSONObject().apply {
                put("type",    "lyricsResult")
                put("id",      callbackId)
                put("success", true)
                put("lines",   lines)
            })

        } catch (e: Exception) {
            sendToJs(JSONObject().apply {
                put("type",    "lyricsResult")
                put("id",      callbackId)
                put("success", false)
                put("lines",   JSONArray())
            })
        }
    }

    private fun searchLrclib(query: String): JSONArray {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://lrclib.net/api/search?q=$encoded"
        return try {
            val json = httpGet(url)
            val el = JSONArray(json)
            el
        } catch (e: Exception) { JSONArray() }
    }

    private fun getLrcLastTimestamp(lrc: String): Double {
        var last = 0.0
        val pattern = Pattern.compile("""^\[(\d+):(\d+)\.(\d+)\]""")
        for (line in lrc.split("\n")) {
            val m = pattern.matcher(line.trim())
            if (!m.find()) continue
            val ms = m.group(3)!!.padEnd(3, '0').substring(0, 3)
            val t = m.group(1)!!.toInt() * 60.0 + m.group(2)!!.toInt() + ms.toInt() / 1000.0
            if (t > last) last = t
        }
        return last
    }

    private fun parseLrc(lrc: String): JSONArray {
        data class LyricLine(val start: Double, val text: String)
        val list = mutableListOf<LyricLine>()
        val pattern = Pattern.compile("""^\[(\d+):(\d+)\.(\d+)\](.*)""")

        for (line in lrc.split("\n")) {
            val l = line.trim()
            if (l.isEmpty()) continue
            val m = pattern.matcher(l)
            if (!m.matches()) continue
            val min  = m.group(1)!!.toInt()
            val sec  = m.group(2)!!.toInt()
            val ms   = m.group(3)!!.padEnd(3, '0').substring(0, 3)
            val t    = min * 60.0 + sec + ms.toInt() / 1000.0
            val text = m.group(4)!!.trim()
            if (text.isEmpty()) continue
            list.add(LyricLine(t, text))
        }
        list.sortBy { it.start }

        val result = JSONArray()
        for (i in list.indices) {
            val start = list[i].start
            val end   = if (i + 1 < list.size) list[i + 1].start else start + 5.0
            result.put(JSONObject().apply {
                put("start", start)
                put("end",   end)
                put("text",  list[i].text)
            })
        }
        return result
    }

    // ── 타이틀 / 아티스트 정제 (C# 버전과 동일 로직) ──
    private fun cleanTitle(t: String): String {
        var s = t
        s = s.replace(Regex("""\((?:official|mv|m/v|video|audio|lyrics?|visualizer|live|performance|hd|4k)[^)]*\)""", RegexOption.IGNORE_CASE), "").trim()
        s = s.replace(Regex("""\[(?:official|mv|m/v|video|audio|lyrics?|visualizer|live|performance|hd|4k)[^\]]*\]""", RegexOption.IGNORE_CASE), "").trim()
        s = s.replace(Regex("""\s*[-|]\s*(official|mv|lyrics?|audio|video)\s*$""", RegexOption.IGNORE_CASE), "").trim()
        s = s.replace(Regex("""\s*[\(\[]?feat\..*$""", RegexOption.IGNORE_CASE), "").trim()
        return s
    }

    private fun cleanArtist(c: String): String {
        var s = c
        s = s.replace(Regex("""\s*[-·]\s*Topic\s*$""", RegexOption.IGNORE_CASE), "").trim()
        s = s.replace(Regex("VEVO$", RegexOption.IGNORE_CASE), "").trim()
        s = s.replace(Regex("""\s*(Records|Entertainment|Music|Official)\s*$""", RegexOption.IGNORE_CASE), "").trim()
        return s
    }

    // ════════════════════════════════════════════
    //  HTTP 유틸 (HttpURLConnection)
    // ════════════════════════════════════════════
    private val CHROME_UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36"

    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod  = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout    = 15_000
            conn.setRequestProperty("User-Agent", CHROME_UA)
            conn.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.connect()
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPost(urlStr: String, body: String, headers: Map<String, String>): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod  = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout    = 15_000
            conn.doOutput       = true
            conn.setRequestProperty("User-Agent", CHROME_UA)
            conn.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
            conn.setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.connect()
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body) }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
