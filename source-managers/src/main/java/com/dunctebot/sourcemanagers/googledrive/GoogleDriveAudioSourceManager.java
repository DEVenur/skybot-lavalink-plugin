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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GoogleDriveAudioSourceManager extends AbstractDuncteBotHttpSource {

    // Cache: fileId -> mimeType
    private final Map<String, String> mimeTypeCache = new ConcurrentHashMap<>();

    public String getCachedMimeType(String id) {
        return mimeTypeCache.getOrDefault(id, "audio/mpeg");
    }

    private static final Pattern DRIVE_URL_PATTERN = Pattern.compile(
        "https?://drive\\.google\\.com/(?:file/d/([a-zA-Z0-9_-]+)|open\\?(?:.*&)?id=([a-zA-Z0-9_-]+))"
    );

    private static final String DOWNLOAD_TEMPLATE  = "https://drive.google.com/uc?export=download&id=%s";
    private static final String VIEW_URL_TEMPLATE  = "https://drive.google.com/file/d/%s/view";
    private static final String THUMBNAIL_TEMPLATE = "https://drive.google.com/thumbnail?id=%s&sz=w500";

    // 64 KB is enough to cover virtually all ID3 headers
    private static final int ID3_READ_BYTES = 65536;

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

    // ── helpers ───────────────────────────────────────────────────────────────

    private String extractId(String url) {
        final Matcher matcher = DRIVE_URL_PATTERN.matcher(url);
        if (!matcher.find()) return null;
        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    }

    private AudioItem buildTrack(String id) throws IOException {
        // 1. Fetch HTML page title (filename)
        final String filename = fetchTitle(id);

        if (filename == null) {
            throw new FriendlyException(
                "This Google Drive file is not publicly accessible",
                FriendlyException.Severity.COMMON,
                null
            );
        }

        // 2. HEAD request to get MIME type
        final String downloadUrl = getDownloadUrl(id);
        String mimeType = "audio/mpeg";

        try (CloseableHttpResponse response = getHttpInterface().execute(new HttpHead(downloadUrl))) {
            final int status = response.getStatusLine().getStatusCode();

            if (status == 404) {
                throw new FriendlyException("Google Drive file not found", FriendlyException.Severity.COMMON, null);
            }
            if (status != 200 && status != 302) {
                throw new IOException("Unexpected status from Drive HEAD: " + status);
            }

            final Header contentType = response.getFirstHeader("Content-Type");
            if (contentType != null) {
                mimeType = contentType.getValue().split(";")[0].trim();
            }
        }

        if (!mimeType.startsWith("audio/")) {
            throw new FriendlyException(
                "This Google Drive file is not an audio file (detected: " + mimeType + ")",
                FriendlyException.Severity.COMMON,
                null
            );
        }

        mimeTypeCache.put(id, mimeType);

        // 3. Range request: read first 64 KB to extract ID3 tags
        final Id3TagReader id3 = fetchId3Tags(downloadUrl);

        // Title + Author: ID3 tags > filename parsing > fallback
        final String[] parsed = parseArtistAndTitle(filename);
        final String trackTitle  = id3.title  != null ? id3.title  : parsed[0];
        final String trackAuthor = id3.artist != null ? id3.artist : parsed[1];

        // Duration: ID3 TLEN (ms) > CONTENT_LENGTH_UNKNOWN
        final long duration = id3.durationMs > 0 ? id3.durationMs : Units.CONTENT_LENGTH_UNKNOWN;

        // Thumbnail: ID3 APIC embedded cover > Drive thumbnail URL
        final String thumbnailUrl = id3.artworkDataUri != null
            ? id3.artworkDataUri
            : String.format(THUMBNAIL_TEMPLATE, id);

        final AudioTrackInfo trackInfo = new AudioTrackInfo(
            trackTitle,
            trackAuthor,
            duration,
            id,
            false,
            String.format(VIEW_URL_TEMPLATE, id),
            thumbnailUrl,
            null
        );

        return decodeTrack(trackInfo, null);
    }

    /**
     * Issues a Range request for the first 64 KB of the audio file
     * and parses the ID3 header from those bytes.
     */
    private Id3TagReader fetchId3Tags(String downloadUrl) {
        final HttpGet get = new HttpGet(downloadUrl);
        get.setHeader("Range", "bytes=0-" + (ID3_READ_BYTES - 1));

        try (CloseableHttpResponse response = getHttpInterface().execute(get)) {
            final int status = response.getStatusLine().getStatusCode();

            // 206 = Partial Content (expected), 200 = server ignored Range header
            if (status != 206 && status != 200) {
                return new Id3TagReader();
            }

            try (InputStream stream = response.getEntity().getContent()) {
                final byte[] buffer = new byte[ID3_READ_BYTES];
                int totalRead = 0;
                int read;

                while (totalRead < ID3_READ_BYTES &&
                       (read = stream.read(buffer, totalRead, ID3_READ_BYTES - totalRead)) != -1) {
                    totalRead += read;
                }

                final byte[] data = totalRead == ID3_READ_BYTES
                    ? buffer
                    : Arrays.copyOf(buffer, totalRead);

                return Id3TagReader.parse(data);
            }
        } catch (IOException e) {
            // Non-critical: fall back to filename metadata
            return new Id3TagReader();
        }
    }

    /**
     * Loads the Drive HTML page and extracts the filename from the <title> tag.
     * Returns null if the file is private or not found.
     */
    private String fetchTitle(String id) throws IOException {
        final HttpGet get = new HttpGet(String.format(VIEW_URL_TEMPLATE, id));

        try (CloseableHttpResponse response = getHttpInterface().execute(get)) {
            final int status = response.getStatusLine().getStatusCode();

            if (status == 404) return null;
            if (status != 200) throw new IOException("Unexpected status from Drive view page: " + status);

            final String html = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);

            final int titleStart = html.indexOf("<title>");
            final int titleEnd   = html.indexOf("</title>");

            if (titleStart == -1 || titleEnd == -1) return "Unknown title";

            final String raw = html.substring(titleStart + 7, titleEnd).trim();

            return raw.contains(" - Google Drive")
                ? raw.substring(0, raw.lastIndexOf(" - Google Drive")).trim()
                : raw;
        }
    }

    /**
     * Tries to split "Artist - Title.mp3" into ["Title", "Artist"].
     * Falls back to [filename, "Google Drive"] if no " - " separator found.
     */
    private String[] parseArtistAndTitle(String filename) {
        final String name = filename.replaceAll("(?i)\\.(mp3|mp4|m4a|ogg|wav|flac)$", "").trim();
        final int sep = name.indexOf(" - ");

        if (sep > 0) {
            return new String[]{
                name.substring(sep + 3).trim(),
                name.substring(0, sep).trim()
            };
        }

        return new String[]{name, "Google Drive"};
    }

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
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
        return new GoogleDriveAudioTrack(trackInfo, this);
    }
}
