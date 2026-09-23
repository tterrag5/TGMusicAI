package com.example.tgmusicai.data.youtube.potoken

/**
 * Something went wrong while minting a Proof-of-Origin token. Ported from NewPipe (GPLv3) --
 * see [PoTokenWebViewGenerator] for the attribution note covering this package.
 */
open class PoTokenException(message: String) : Exception(message)

/**
 * The device's WebView implementation is too old or too broken to run BotGuard's JavaScript.
 *
 * This is deliberately a distinct type rather than a generic failure, because the two causes are
 * indistinguishable from the outside and lead to opposite conclusions: either our code is wrong,
 * or the device simply cannot do this. [PoTokenWebViewGenerator] raises this when the WebView
 * reports an uncaught JavaScript error, which in practice means a syntax error from an outdated JS
 * engine -- everything the page does itself is wrapped in try-catch. [TGPoTokenProvider] treats it
 * as permanent and stops retrying, in line with the containment rules the `ai/` engines follow.
 */
class BadWebViewException(message: String) : PoTokenException(message)
