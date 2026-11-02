package com.dunctebot.sourcemanagers.tiktok;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TikTokUrlTest {
    @Test
    void normalizesStandardVideoUrlAndDropsQueryParameters() throws Exception {
        assertEquals(
                "https://www.tiktok.com/@kallmekris/video/7229737213712436486",
                TikTokAudioSourceManager.normalizeVideoUrl(
                        "https://www.tiktok.com/@kallmekris/video/7229737213712436486?lang=en"
                )
        );
    }

    @Test
    void rejectsUnrecognizedUrls() throws Exception {
        assertNull(TikTokAudioSourceManager.normalizeVideoUrl("https://example.com/video/123"));
        assertNull(TikTokAudioSourceManager.normalizeVideoUrl("not-a-url"));
    }
}
