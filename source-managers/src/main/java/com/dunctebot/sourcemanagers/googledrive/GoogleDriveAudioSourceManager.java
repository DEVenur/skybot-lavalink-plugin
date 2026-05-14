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
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GoogleDriveAudioSourceManager extends AbstractDuncteBotHttpSource {

    private static final Logger log = LoggerFactory.getLogger(GoogleDriveAudioSourceManager.class);

    // Cache: fileId -> mimeType
    private final Map<String, String> mimeTypeCache = new ConcurrentHashMap<>();

    // ── Search ────────────────────────────────────────────────────────────────

    /** Prefix used by callers: e.g. "dvsearch:lofi hip hop" */
    private static final String SEARCH_PREFIX = "dvsearch:";

    /** Drive API v3 files.list endpoint. */
    private static final String DRIVE_SEARCH_URL = "https://www.googleapis.com/drive/v3/files";

    /** Max results returned per search query. */
    private static final int SEARCH_MAX_RESULTS = 5;

    /** Google Cloud API Key – nullable, disables dvsearch: when null. */
    private final String driveKey;

    // ── URL / download templates ──────────────────────────────────────────────

    private static final Pattern DRIVE_URL_PATTERN = Pattern.compile(
        "https?://drive\\.google\\.com/(?:file/d/([a-zA-Z0-9_-]+)|open\\?(?:.*&)?id=([a-zA-Z0-9_-]+))"
    );

    private static final String DOWNLOAD_TEMPLATE  = "https://drive.google.com/uc?export=download&id=%s";
    private static final String VIEW_URL_TEMPLATE  = "https://drive.google.com/file/d/%s/view";
    private static final String THUMBNAIL_TEMPLATE = "https://drive.google.com/thumbnail?id=%s&sz=w500";

    // 64 KB is enough to cover virtually all ID3 headers
    private static final int ID3_READ_BYTES = 65536;

    // ── Constructors ──────────────────────────────────────────────────────────

    /** No-arg constructor – dvsearch: disabled. */
    public GoogleDriveAudioSourceManager() {
        this(null);
    }

    /**
     * @param driveKey Google Cloud API Key with Drive API enabled.
     *                 Pass null to disable dvsearch: support.
     */
    public GoogleDriveAudioSourceManager(String driveKey) {
        this.driveKey = driveKey;
        if (driveKey == null) {
            log.info("GoogleDriveAudioSourceManager: driveKey not set — dvsearch: disabled");
        }
    }

    // ── Source name ───────────────────────────────────────────────────────────

    public String getCachedMimeType(String id) {
        return mimeTypeCache.getOrDefault(id, "audio/mpeg");
    }

    @Override
    public String getSourceName() {
        return "googledrive";
    }

    // ── Load item (entry point) ───────────────────────────────────────────────

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        final String identifier = reference.getIdentifier();

        // ── dvsearch: branch ─────────────────────────────────────────────────
        if (identifier.startsWith(SEARCH_PREFIX)) {
            if (driveKey == null) {
                throw new FriendlyException(
                    "dvsearch: is disabled — set plugins.dunctebot.googledrive.driveKey in your application.yml",
                    FriendlyException.Severity.COMMON,
                    null
                );
            }
            final String query = identifier.substring(SEARCH_PREFIX.length()).trim();
            if (query.isEmpty()) return AudioReference.NO_TRACK;

            try {
                return searchDrive(query);
            } catch (IOException e) {
                throw new FriendlyException(
                    "Google Drive search failed: " + e.getMessage(),
                    FriendlyException.Severity.SUSPICIOUS,
                    e
                );
            }
        }

        // ── Direct URL branch ────────────────────────────────────────────────
        final String id = extractId(identifier);
        if (id == null) return null;

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

    // ── Search ────────────────────────────────────────────────────────────────

    /**
     * Searches Google Drive for public audio files matching query.
     * Uses Drive API v3 files.list with an API key (no OAuth required for public files).
     */
    private AudioItem searchDrive(String query) throws IOException {
        final String driveQuery = String.format(
            "fullText contains '%s' and mimeType contains 'audio/' and visibility = 'anyoneCanFind'",
            query.replace("'", "\\'")
        );

        final String url = DRIVE_SEARCH_URL
            + "?q="       + URLEncoder.encode(driveQuery, StandardCharsets.UTF_8)
            + "&key="     + URLEncoder.encode(driveKey, StandardCharsets.UTF_8)
            + "&pageSize=" + SEARCH_MAX_RESULTS
            + "&fields="  + URLEncoder.encode("files(id,name,mimeType)", StandardCharsets.UTF_8)
            + "&orderBy=viewCount+desc";

        final HttpGet get = new HttpGet(url);

        try (CloseableHttpResponse response = getHttpInterface().execute(get)) {
            final int status = response.getStatusLine().getStatusCode();
            final String body = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);

            if (status == 400) {
                log.warn("Drive search bad request (check query syntax): {}", body);
                return AudioReference.NO_TRACK;
            }
            if (status == 403) {
                log.error("Drive search 403 — verify the API key has Drive API enabled: {}", body);
                throw new FriendlyException(
                    "Drive API key rejected (403) — see server logs",
                    FriendlyException.Severity.COMMON, null
                );
            }
            if (status != 200) {
                throw new IOException("Drive search returned unexpected status " + status + ": " + body);
            }

            return parseSearchResults(body);
        }
    }

    /**
     * Parses the files.list JSON response using LavaPlayer's JsonBrowser.
     * Falls back to the next candidate if a file fails to load.
     */
    private AudioItem parseSearchResults(String json) throws IOException {
        final JsonBrowser root = JsonBrowser.parse(json);
        final JsonBrowser files = root.get("files");

        if (files.isNull() || files.values().isEmpty()) {
            return AudioReference.NO_TRACK;
        }

        final List<String> candidateIds = new ArrayList<>();
        for (final JsonBrowser file : files.values()) {
            final String mimeType = file.get("mimeType").text();
            if (mimeType == null || !mimeType.startsWith("audio/")) continue;
            final String id = file.get("id").text();
            if (id != null) candidateIds.add(id);
        }

        if (candidateIds.isEmpty()) return AudioReference.NO_TRACK;

        for (final String id : candidateIds) {
            try {
                final AudioItem item = buildTrack(id);
                if (item != null) return item;
            } catch (FriendlyException | IOException e) {
                log.debug("Drive search: skipping file {} — {}", id, e.getMessage());
            }
        }

        return AudioReference.NO_TRACK;
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
            return new Id3TagReader();
        }
    }

    /**
     * Loads the Drive HTML page and extracts the filename from the title tag.
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
