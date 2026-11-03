package com.dunctebot.sourcemanagers.tiktok;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TikTokAudioSourceManagerTest {

    @Test
    void removesTrailingHashtagsFromTitle() {
        assertEquals(
                "Icon For Hire - Get Well",
                TikTokAudioSourceManager.cleanTitle(
                        "Icon For Hire - Get Well #punkrock #rock #rockbr #rockstar #metal #poppunk #emo #punk #alternativ"
                )
        );
    }

    @Test
    void preservesHashtagsInsideTitle() {
        assertEquals(
                "Song #Live Version",
                TikTokAudioSourceManager.cleanTitle("Song #Live Version")
        );
    }

    @Test
    void usesFallbackWhenTitleContainsOnlyHashtags() {
        assertEquals(
                "TikTok video",
                TikTokAudioSourceManager.cleanTitle("#music #rock")
        );
    }

    @Test
    void usesFallbackForBlankTitle() {
        assertEquals("TikTok video", TikTokAudioSourceManager.cleanTitle("  "));
    }
}
