/*
 * Copyright 2021 Duncan "duncte123" Sterken
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.dunctebot.sourcemanagers.googledrive;

import com.dunctebot.sourcemanagers.AbstractDuncteBotHttpSource;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GoogleDriveAudioSourceManager extends AbstractDuncteBotHttpSource {

    // Matches:
    //   https://drive.google.com/file/d/{ID}/view
    //   https://drive.google.com/file/d/{ID}/view?usp=sharing
    //   https://drive.google.com/open?id={ID}
    private static final Pattern DRIVE_URL_PATTERN = Pattern.compile(
            "https?://drive\\.google\\.com/(?:file/d/([a-zA-Z0-9_-]+)|open\\?(?:.*&)?id=([a-zA-Z0-9_-]+))"
    );

    // Direct download URL template
    private static final String DOWNLOAD_TEMPLATE = "https://drive.google.com/uc?export=download&id=%s";

    // Metadata API (no key needed for public files — just the HTML page)
    private static final String VIEW_URL_TEMPLATE = "https://drive.google.com/file/d/%s/view";

    @Override
    public String getSourceName() {
        return "googledrive";
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        final String id = extractId(reference.getIdentifier());

        if (id == null) {
            return null;
        }

        try {
            return buildTrack(id);
        } catch (IOException e) {
            throw new FriendlyException(
                    "Could not load Google Drive track",
                    FriendlyException.Severity.SUSPICIOUS,
                    e
            );
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String extractId(String url) {
        final Matcher matcher = DRIVE_URL_PATTERN.matcher(url);

        if (!matcher.find()) {
            return null;
        }

        // group 1 → /file/d/{ID}  |  group 2 → ?id={ID}
        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    }

    private AudioItem buildTrack(String id) throws IOException {
        // 1. Fetch the HTML page to get the file name shown by Drive
        final String title = fetchTitle(id);

        if (title == null) {
            throw new FriendlyException(
                    "This Google Drive file is not publicly accessible",
                    FriendlyException.Severity.COMMON,
                    null
            );
        }

        // 2. HEAD request on the download URL to get Content-Type & Content-Length
        final String downloadUrl = getDownloadUrl(id);
        final HttpHead head = new HttpHead(downloadUrl);

        String mimeType = "audio/mpeg"; // safe default
        long contentLength = Units.CONTENT_LENGTH_UNKNOWN;

        try (CloseableHttpResponse response = getHttpInterface().execute(head)) {
            final int status = response.getStatusLine().getStatusCode();

            if (status == 404) {
                throw new FriendlyException("Google Drive file not found", FriendlyException.Severity.COMMON, null);
            }

            if (status != 200 && status != 302) {
                throw new IOException("Unexpected status from Drive download HEAD: " + status);
            }

            final Header contentType = response.getFirstHeader("Content-Type");
            if (contentType != null) {
                // strip charset if present: "audio/mpeg; charset=utf-8" → "audio/mpeg"
                mimeType = contentType.getValue().split(";")[0].trim();
            }

            final Header contentLengthHeader = response.getFirstHeader("Content-Length");
            if (contentLengthHeader != null) {
                try {
                    contentLength = Long.parseLong(contentLengthHeader.getValue());
                } catch (NumberFormatException ignored) {
                    // keep CONTENT_LENGTH_UNKNOWN
                }
            }
        }

        if (!mimeType.startsWith("audio/")) {
            throw new FriendlyException(
                    "This Google Drive file is not an audio file (detected: " + mimeType + ")",
                    FriendlyException.Severity.COMMON,
                    null
            );
        }

        // We store mimeType in artworkUrl (no thumbnail available for Drive files)
        final AudioTrackInfo trackInfo = new AudioTrackInfo(
                title,
                "Google Drive",
                contentLength,
                id,
                false,
                String.format(VIEW_URL_TEMPLATE, id),
                mimeType, // artworkUrl reused to carry MIME type to the track
                null
        );

        return decodeTrack(trackInfo, null);
    }

    /**
     * Loads the Drive HTML page and extracts the file name from the {@code <title>} tag.
     * Returns {@code null} if the file is private or not found.
     */
    private String fetchTitle(String id) throws IOException {
        final HttpGet get = new HttpGet(String.format(VIEW_URL_TEMPLATE, id));

        try (CloseableHttpResponse response = getHttpInterface().execute(get)) {
            final int status = response.getStatusLine().getStatusCode();

            if (status == 404) {
                return null;
            }

            if (status != 200) {
                throw new IOException("Unexpected status from Drive view page: " + status);
            }

            final String html = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);

            // <title>filename.mp3 - Google Drive</title>
            final int titleStart = html.indexOf("<title>");
            final int titleEnd = html.indexOf("</title>");

            if (titleStart == -1 || titleEnd == -1) {
                return "Unknown title";
            }

            final String raw = html.substring(titleStart + 7, titleEnd).trim();

            // Strip the " - Google Drive" suffix if present
            return raw.contains(" - Google Drive")
                    ? raw.substring(0, raw.lastIndexOf(" - Google Drive")).trim()
                    : raw;
        }
    }

    /** Returns the direct download URL for a given Drive file ID. */
    public static String getDownloadUrl(String id) {
        return String.format(DOWNLOAD_TEMPLATE, id);
    }

    // ── lavaplayer boilerplate ────────────────────────────────────────────────

    @Override
    public boolean isTrackEncodable(AudioTrack track) {
        return false;
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) throws IOException {
        // Nothing to encode
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
        return new GoogleDriveAudioTrack(trackInfo, this);
    }
}
