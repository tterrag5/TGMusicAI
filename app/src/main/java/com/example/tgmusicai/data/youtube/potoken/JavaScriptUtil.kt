package com.example.tgmusicai.data.youtube.potoken

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject

/*
 * Ported from NewPipe's util/potoken/JavaScriptUtil.kt, which is licensed under the GNU General
 * Public License v3.0. See PoTokenWebViewGenerator.kt for the full attribution note.
 *
 * These are wire-format helpers for BotGuard's two endpoints. The encodings are quirky -- a
 * scrambled challenge, a YouTube-specific base64 alphabet, and payloads that must be emitted as
 * JavaScript source rather than as data -- so the logic is kept faithful to upstream rather than
 * rewritten in a more idiomatic style.
 *
 * Upstream parses with nanojson, which NewPipeExtractor uses internally but does not expose on the
 * compile classpath. These use org.json instead: it is provided by the Android platform, needs no
 * new dependency, and is already what the rest of YouTubeExtractor parses with. okio comes in with
 * OkHttp and works under plain JVM unit tests, which keeps these functions directly testable.
 */

/**
 * Parses the raw challenge data from the Create endpoint into a JSON string that can be embedded
 * directly in a JavaScript snippet and handed to `runBotGuard`.
 */
fun parseChallengeData(rawChallengeData: String): String {
    val scrambled = JSONArray(rawChallengeData)

    // The endpoint returns the challenge in one of two shapes: either scrambled into a string at
    // index 1, or already a nested array at index 0.
    val challengeData = if (scrambled.length() > 1 && scrambled.opt(1) is String) {
        JSONArray(descramble(scrambled.getString(1)))
    } else {
        scrambled.getJSONArray(0)
    }

    val interpreterJavascript = JSONObject()
        .put(
            "privateDoNotAccessOrElseSafeScriptWrappedValue",
            challengeData.optJSONArray(1).firstStringOrNull(),
        )
        .put(
            "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue",
            challengeData.optJSONArray(2).firstStringOrNull(),
        )

    return JSONObject()
        .put("messageId", challengeData.getString(0))
        .put("interpreterJavascript", interpreterJavascript)
        .put("interpreterHash", challengeData.getString(3))
        .put("program", challengeData.getString(4))
        .put("globalName", challengeData.getString(5))
        .put("clientExperimentsStateBlob", challengeData.getString(7))
        .toString()
}

/**
 * Parses the raw response from the GenerateIT endpoint into a JavaScript `Uint8Array` literal
 * holding the integrity token, paired with the token's lifetime in seconds.
 */
fun parseIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
    val integrityTokenData = JSONArray(rawIntegrityTokenData)
    return base64ToU8(integrityTokenData.getString(0)) to integrityTokenData.getLong(1)
}

/**
 * Converts an identifier (a videoId or visitorData) into a JavaScript `Uint8Array` literal, which
 * is the form `obtainPoToken` expects.
 */
fun stringToU8(identifier: String): String = newUint8Array(identifier.toByteArray())

/**
 * Converts the output of JavaScript's `Uint8Array::toString()` -- bytes as comma-separated
 * integers, e.g. "97,98,99" for "abc" -- into the URL-safe base64 form poTokens use.
 */
fun u8ToBase64(poToken: String): String =
    poToken.split(",")
        .map { it.trim().toUByte().toByte() }
        .toByteArray()
        .toByteString()
        .base64()
        .replace("+", "-")
        .replace("/", "_")

/** Returns the first string element of this array, which is where the wrapped values hide. */
private fun JSONArray?.firstStringOrNull(): String? {
    val array = this ?: return null
    for (index in 0 until array.length()) {
        val value = array.opt(index)
        if (value is String) return value
    }
    return null
}

/** Decodes the scrambled challenge: base64-decode, then add 97 to every byte. */
private fun descramble(scrambledChallenge: String): String =
    base64ToByteString(scrambledChallenge)
        .map { (it + 97).toByte() }
        .toByteArray()
        .decodeToString()

private fun base64ToU8(base64: String): String = newUint8Array(base64ToByteString(base64))

private fun newUint8Array(contents: ByteArray): String =
    "new Uint8Array([" + contents.joinToString(separator = ",") { it.toUByte().toString() } + "])"

/** Decodes YouTube's base64 variant, which uses `-`/`_` for `+`/`/` and `.` for padding. */
private fun base64ToByteString(base64: String): ByteArray {
    val base64Mod = base64
        .replace('-', '+')
        .replace('_', '/')
        .replace('.', '=')

    return (base64Mod.decodeBase64() ?: throw PoTokenException("Cannot base64 decode"))
        .toByteArray()
}
