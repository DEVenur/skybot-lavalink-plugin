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

package com.dunctebot.sourcemanagers.internetarchive;

import com.dunctebot.sourcemanagers.AbstractDuncteBotHttpSource;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InternetArchiveAudioSourceManager extends AbstractDuncteBotHttpSource {

    private static final Logger log = LoggerFactory.getLogger(InternetArchiveAudioSourceManager.class);

    // ── URL pattern ───────────────────────────────────────────────────────────

    /**
     * Group 1 = identifier (e.g. "King-Mathers")
     * Group 2 = filename   (e.g. "04.+King+Mathers+(feat.+Cashis).mp3") — optional, URL-encoded
     *
     * Matches:
     *   https://archive.org/details/IDENTIFIER
     *   https://archive.org/details/IDENTIFIER/FILENAME
     *   https://archive.org/download/IDENTIFIER/FILENAME
     */
    private static final Pattern IA_URL_PATTERN = Pattern.compile(
        "https?://archive\\.org/(?:details|download)/([A-Za-z0-9._-]+)(?:/([^?#]+))?"
    );

    /** Search prefix: "iasearch:jazz piano" */
    private static final String SEARCH_PREFIX = "iasearch:";

    // ── API endpoints ─────────────────────────────────────────────────────────

    private static final String METADATA_URL = "https://archive.org/metadata/%s";
    private static final String DOWNLOAD_URL = "https://archive.org/download/%s/%s";
    private static final String DETAILS_URL   = "https://archive.org/details/%s";
    private static final String THUMBNAIL_URL = "https://archive.org/services/img/%s";
    private static final String SEARCH_URL   = "https://archive.org/advancedsearch.php"
        + "?q=%s+AND+mediatype%%3Aaudio"
        + "&fl[]=identifier&fl[]=title&fl[]=creator"
        + "&rows=10&output=json&page=%d";

    private static final int SEARCH_MAX_PAGES = 3;

    /**
     * Audio format preference order — best first.
     * Used only when NO specific filename is given in the URL.
     */
    private static final List<String> FORMAT_PRIORITY = Arrays.asList(
        "VBR MP3", "MP3", "128Kbps MP3", "64Kbps MP3",
        "OGG VORBIS", "OGG",
        "FLAC",
        "MP4", "M4A", "AAC",
        "WAV", "WAVE"
    );

    // ── Source name ───────────────────────────────────────────────────────────

    @Override
    public String getSourceName() {
        return "internetarchive";
    }

    // ── Load item (entry point) ───────────────────────────────────────────────

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        final String identifier = reference.getIdentifier();

        // ── iasearch: branch ─────────────────────────────────────────────────
        if (identifier.startsWith(SEARCH_PREFIX)) {
            final String query = identifier.substring(SEARCH_PREFIX.length()).trim();
            if (query.isEmpty()) return AudioReference.NO_TRACK;

            try {
                return search(query);
            } catch (IOException e) {
                throw new FriendlyException(
                    "Internet Archive search failed: " + e.getMessage(),
                    FriendlyException.Severity.SUSPICIOUS, e
                );
            }
        }

        // ── Direct URL branch ─────────────────────────────────────────────────
        final Matcher m = IA_URL_PATTERN.matcher(identifier);
        if (!m.find()) return null;

        final String iaId     = m.group(1);
        final String rawFile  = m.group(2); // null when no filename in URL

        try {
            if (rawFile != null && !rawFile.isEmpty()) {
                // Archive.org links commonly use '+' for spaces. Protect encoded
                // %2B first so a literal plus sign survives URL decoding.
                final String fileName = URLDecoder.decode(
                    rawFile.replace("+", "%20"),
                    StandardCharsets.UTF_8
                );
                return buildTrackForFile(iaId, fileName);
            } else {
                // Album/item URL — pick best audio file automatically
                return buildTrack(iaId);
            }
        } catch (IOException e) {
            throw new FriendlyException(
                "Could not load Internet Archive item",
                FriendlyException.Severity.SUSPICIOUS, e
            );
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private AudioItem search(String query) throws IOException {
        final String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);

        // Try a few result pages so an item without a supported audio file does
        // not prevent a later matching item from being selected.
        for (int page = 1; page <= SEARCH_MAX_PAGES; page++) {
            final String url = String.format(SEARCH_URL, encodedQuery, page);
            final JsonBrowser response = fetchJson(url);
            if (response == null || response.isNull()) return AudioReference.NO_TRACK;

            final JsonBrowser docs = response.get("response").get("docs");
            if (docs.isNull() || docs.values().isEmpty()) break;

            for (final JsonBrowser doc : docs.values()) {
                final String iaId = doc.get("identifier").text();
                if (iaId == null || iaId.isEmpty()) continue;

                try {
                    final AudioItem item = buildTrack(iaId);
                    if (item != null) return item;
                } catch (FriendlyException | IOException e) {
                    log.debug("IA search: skipping {} — {}", iaId, e.getMessage());
                }
            }
        }

        return AudioReference.NO_TRACK;
    }

    // ── Track building ────────────────────────────────────────────────────────

    /**
     * User specified a filename in the URL (e.g. /details/King-Mathers/04.+King+Mathers+....mp3).
     * Fetch metadata, find that exact file, and build a track for it.
     */
    private AudioItem buildTrackForFile(String iaId, String requestedFileName) throws IOException {
        log.info("IA: loading specific file '{}' from '{}'", requestedFileName, iaId);

        final JsonBrowser meta = fetchJson(String.format(METADATA_URL, iaId));
        if (meta == null || meta.isNull()) {
            throw new FriendlyException(
                "Internet Archive item not found: " + iaId,
                FriendlyException.Severity.COMMON, null
            );
        }

        final JsonBrowser metadata = meta.get("metadata");
        String title  = metadata.get("title").text();
        String author = metadata.get("creator").text();
        if (title  == null || title.isEmpty())  title  = iaId;
        if (author == null || author.isEmpty()) author = "Internet Archive";

        // Find the exact file by name (case-insensitive for safety)
        JsonBrowser matchedFile = null;
        for (final JsonBrowser file : meta.get("files").values()) {
            final String name = file.get("name").text();
            if (name != null && name.equalsIgnoreCase(requestedFileName)) {
                matchedFile = file;
                break;
            }
        }

        if (matchedFile == null) {
            throw new FriendlyException(
                "File '" + requestedFileName + "' not found in Internet Archive item: " + iaId,
                FriendlyException.Severity.COMMON, null
            );
        }

        return buildTrackFromFile(iaId, matchedFile, title, author);
    }

    /**
     * No specific file requested — fetch metadata, pick the best audio file automatically.
     */
    private AudioItem buildTrack(String iaId) throws IOException {
        final JsonBrowser meta = fetchJson(String.format(METADATA_URL, iaId));
        if (meta == null || meta.isNull()) {
            throw new FriendlyException(
                "Internet Archive item not found: " + iaId,
                FriendlyException.Severity.COMMON, null
            );
        }

        final JsonBrowser metadata = meta.get("metadata");
        String title  = metadata.get("title").text();
        String author = metadata.get("creator").text();
        if (title  == null || title.isEmpty())  title  = iaId;
        if (author == null || author.isEmpty()) author = "Internet Archive";

        final JsonBrowser files = meta.get("files");
        if (files.isNull() || files.values().isEmpty()) {
            throw new FriendlyException(
                "No files found in Internet Archive item: " + iaId,
                FriendlyException.Severity.COMMON, null
            );
        }

        JsonBrowser bestFile = null;
        int bestPriority = Integer.MAX_VALUE;
        for (final JsonBrowser file : files.values()) {
            final String format = file.get("format").text();
            if (format == null) continue;
            final int priority = formatPriority(format);
            if (priority < bestPriority) {
                bestPriority = priority;
                bestFile = file;
            }
        }

        if (bestFile == null) {
            throw new FriendlyException(
                "No supported audio file found in Internet Archive item: " + iaId,
                FriendlyException.Severity.COMMON, null
            );
        }

        return buildTrackFromFile(iaId, bestFile, title, author);
    }

    /**
     * Shared logic: given an IA item id, a file JsonBrowser node, and album-level
     * title/author, build and return an InternetArchiveAudioTrack.
     */
    private AudioItem buildTrackFromFile(String iaId, JsonBrowser file, String albumTitle, String albumAuthor) {
        final String fileName = file.get("name").text();
        final String format   = file.get("format").text();

        // Per-file title/creator override if present in metadata
        final String fileTitle  = file.get("title").text();
        final String fileAuthor = file.get("creator").text();
        final String title  = (fileTitle  != null && !fileTitle.isEmpty())  ? fileTitle  : albumTitle;
        final String author = (fileAuthor != null && !fileAuthor.isEmpty()) ? fileAuthor : albumAuthor;

        // Duration: IA "length" is in seconds (float string)
        long durationMs = Long.MAX_VALUE;
        final String lengthStr = file.get("length").text();
        if (lengthStr != null) {
            try {
                durationMs = Math.round(Double.parseDouble(lengthStr) * 1000.0);
            } catch (NumberFormatException ignored) { }
        }

        final String streamUrl    = String.format(DOWNLOAD_URL, iaId, urlEncodeFileName(fileName));
        final String detailsUrl   = String.format(DETAILS_URL, iaId);
        final String thumbnailUrl = String.format(THUMBNAIL_URL, iaId);
        final String mimeType     = formatToMime(format, fileName);

        // The API's "size" is bytes; "length" above is playback duration in seconds.
        final long contentLength = parsePositiveLong(file.get("size").text(), Long.MAX_VALUE);

        log.info("IA: resolved {} → {} ({})", iaId, fileName, mimeType);

        final AudioTrackInfo trackInfo = new AudioTrackInfo(
            title,
            author,
            durationMs,
            streamUrl,   // identifier = direct stream URL
            false,
            detailsUrl,
            thumbnailUrl,
            null
        );

        return new InternetArchiveAudioTrack(trackInfo, mimeType, contentLength, this);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int formatPriority(String format) {
        final String upper = format.toUpperCase();
        for (int i = 0; i < FORMAT_PRIORITY.size(); i++) {
            if (upper.contains(FORMAT_PRIORITY.get(i))) return i;
        }
        // Some IA records use a generic format label but still expose a useful
        // filename extension. Keep unsupported formats out of the candidates.
        return Integer.MAX_VALUE;
    }

    public static String formatToMime(String format) {
        return formatToMime(format, null);
    }

    private static String formatToMime(String format, String fileName) {
        final String upper = format == null ? "" : format.toUpperCase();

        if (upper.contains("MP3") || upper.contains("MPEG3")) return "audio/mpeg";
        if (upper.contains("OGG") || upper.contains("VORBIS") || upper.contains("OPUS")) return "audio/ogg";
        if (upper.contains("FLAC")) return "audio/flac";
        if (upper.contains("MP4") || upper.contains("MPEG4") || upper.contains("M4A")
            || upper.contains("AAC") || upper.contains("MPEG-4")) return "audio/mp4";
        if (upper.contains("WAV") || upper.contains("WAVE")) return "audio/wav";

        if (fileName != null) {
            final String name = fileName.toLowerCase();
            if (name.endsWith(".mp3")) return "audio/mpeg";
            if (name.endsWith(".ogg") || name.endsWith(".oga") || name.endsWith(".opus")) return "audio/ogg";
            if (name.endsWith(".flac")) return "audio/flac";
            if (name.endsWith(".mp4") || name.endsWith(".m4a") || name.endsWith(".aac")) return "audio/mp4";
            if (name.endsWith(".wav")) return "audio/wav";
        }

        return null;
    }

    private static long parsePositiveLong(String value, long fallback) {
        if (value == null) return fallback;
        try {
            final long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String urlEncodeFileName(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private JsonBrowser fetchJson(String url) throws IOException {
        final HttpGet get = new HttpGet(url);
        get.setHeader("Accept", "application/json");

        try (CloseableHttpResponse response = getHttpInterface().execute(get)) {
            final int status = response.getStatusLine().getStatusCode();
            if (status == 404) return null;
            if (status != 200) throw new IOException("Unexpected status " + status + " from " + url);
            final String body = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
            return JsonBrowser.parse(body);
        }
    }

    // ── LavaPlayer boilerplate ────────────────────────────────────────────────

    @Override
    public boolean isTrackEncodable(AudioTrack track) { return false; }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) throws IOException { }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
        return new InternetArchiveAudioTrack(trackInfo, "audio/mpeg", this);
    }
}
