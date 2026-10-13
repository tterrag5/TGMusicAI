package com.example.tgmusicai

import com.example.tgmusicai.data.youtube.findContinuationToken
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T032: a playlist page carries two tokens. The section list's `nextContinuationData` leads to the
 * suggestions section (no tracks); the shelf's trailing `continuationItemRenderer` is the next 100
 * tracks. Shape trimmed from a live `VL...` browse response (2026-10-13).
 */
class PlaylistContinuationTest {

    private val page = JSONObject(
        """
        {"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{"sectionListRenderer":{
          "contents":[{"musicPlaylistShelfRenderer":{"contents":[
            {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"a"}}},
            {"continuationItemRenderer":{"trigger":"CONTINUATION_TRIGGER_ON_ITEM_PRESCAN_VISIBLE",
              "continuationEndpoint":{"continuationCommand":{"token":"TRACKS_PAGE_2"}}}}
          ]}}],
          "continuations":[{"nextContinuationData":{"continuation":"SUGGESTIONS"}}]
        }}}}}
        """
    )

    @Test
    fun prefersTheListsOwnContinuationOverTheSectionOne() {
        assertEquals("TRACKS_PAGE_2", findContinuationToken(page))
    }

    @Test
    fun fallsBackToNextContinuationData() {
        val old = JSONObject("""{"continuations":[{"nextContinuationData":{"continuation":"OLD"}}]}""")
        assertEquals("OLD", findContinuationToken(old))
    }

    @Test
    fun lastPageHasNoToken() {
        assertNull(findContinuationToken(JSONObject("""{"contents":[]}""")))
    }
}
