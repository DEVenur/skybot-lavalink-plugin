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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InternetArchiveAudioSourceManager extends AbstractDuncteBotHttpSource {

    private static final Logger log = LoggerFactory.getLogger(InternetArchiveAudioSourceManager.class);

    // ── URL patterns ──────────────────────────────────────────────────────────

    /**
     * Matches:
     *   https://archive.org/details/IDENTIFIER
     *   https://archive.org/details/IDENTIFIER/FILENAME (ignored — we pick best file via API)
     *   https://archive.org/download/IDENTIFIER/FILENAME (direct link)
     */
    private static final Pattern IA_URL_PATTERN = Pattern.compile(
        "https?://archive\\.org/(?:details|download)/([A-Za-z0-9._-]+)(?:/[^?#]*)?"
    );

    /** Search prefix: "iasearch:jazz piano" */
    private static final String SEARCH_PREFIX = "iasearch:";

    // ── API endpoints ─────────────────────────────────────────────────────────

    private static final String METADATA_URL  = "https://archive.org/metadata/%s";
    private static final String DOWNLOAD_URL  = "https://archive.org/download/%s/%s";
    private static final String DETAILS_URL   = "https://archive.org/details/%s";
    private static final String SEARCH_URL    = "https://archive.org/advancedsearch.php"
        + "?q=%s+AND+mediatype%%3Aaudio"
        + "&fl[]=identifier&fl[]=title&fl[]=creator"
        + "&rows=5&output=json&page=1";

    /**
     * Audio format preference order — best first.
     * Internet Archive "format" field values (case-insensitive prefix match).
     */
    private static final List<String> FORMAT_PRIORITY = Arrays.asList(
        "VBR MP3", "MP3", "128Kbps MP3", "64Kbps MP3",   // mp3 variants
        "OGG VORBIS", "OGG",                               // ogg
        "FLAC",                                            // flac
        "MP4", "M4A", "AAC",                               // mpeg4
        "WAV", "WAVE"                                      // wav
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
        final String iaId = extractIdentifier(identifier);
        if (iaId == null) return null;

        try {
            return buildTrack(iaId);
        } catch (IOException e) {
            throw new FriendlyException(
                "Could not load Internet Archive item",
                FriendlyException.Severity.SUSPICIOUS, e
            );
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private AudioItem search(String query) throws IOException {
        final String url = String.format(
            SEARCH_URL,
            URLEncoder.encode(query, StandardCharsets.UTF_8)
        );

        final JsonBrowser response = fetchJson(url);
        if (response == null || response.isNull()) return AudioReference.NO_TRACK;

        final JsonBrowser docs = response.get("response").get("docs");
        if (docs.isNull() || docs.values().isEmpty()) return AudioReference.NO_TRACK;

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

        return AudioReference.NO_TRACK;
    }

    // ── Metadata + track building ─────────────────────────────────────────────

    /**
     * Fetches archive.org/metadata/{id}, picks the best audio file from the listing,
     * and returns an AudioTrackInfo.
     */
    private AudioItem buildTrack(String iaId) throws IOException {
        final JsonBrowser meta = fetchJson(String.format(METADATA_URL, iaId));

        if (meta == null || meta.isNull()) {
            throw new FriendlyException(
                "Internet Archive item not found: " + iaId,
                FriendlyException.Severity.COMMON, null
            );
        }

        // ── title and author ─────────────────────────────────────────────────
        final JsonBrowser metadata = meta.get("metadata");
        String title  = metadata.get("title").text();
        String author = metadata.get("creator").text();

        if (title  == null || title.isEmpty())  title  = iaId;
        if (author == null || author.isEmpty()) author = "Internet Archive";

        // ── pick best audio file ──────────────────────────────────────────────
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

        // ── file metadata ─────────────────────────────────────────────────────
        final String fileName = bestFile.get("name").text();
        final String format   = bestFile.get("format").text();

        // Per-file title/creator override if present
        final String fileTitle  = bestFile.get("title").text();
        final String fileAuthor = bestFile.get("creator").text();
        if (fileTitle  != null && !fileTitle.isEmpty())  title  = fileTitle;
        if (fileAuthor != null && !fileAuthor.isEmpty()) author = fileAuthor;

        // Duration: IA "length" is in seconds (float string), convert to ms
        long durationMs = 0;
        final String lengthStr = bestFile.get("length").text();
        if (lengthStr != null) {
            try {
                durationMs = Math.round(Double.parseDouble(lengthStr) * 1000.0);
            } catch (NumberFormatException ignored) {
                // leave as 0 → LavaPlayer will stream without known duration
            }
        }

        final String streamUrl  = String.format(DOWNLOAD_URL, iaId, urlEncodeFileName(fileName));
        final String detailsUrl = String.format(DETAILS_URL, iaId);
        final String mimeType   = formatToMime(format);

        log.info("IA: resolved {} → {} ({})", iaId, fileName, mimeType);

        final AudioTrackInfo trackInfo = new AudioTrackInfo(
            title,
            author,
            durationMs > 0 ? durationMs : Long.MAX_VALUE,
            streamUrl,   // identifier = the direct stream URL
            false,
            detailsUrl,
            null,        // no thumbnail — archive.org has no reliable cover API
            null
        );

        return new InternetArchiveAudioTrack(trackInfo, mimeType, this);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String extractIdentifier(String url) {
        final Matcher m = IA_URL_PATTERN.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Returns the priority index of a given IA format string.
     * Lower = more preferred. Integer.MAX_VALUE = unsupported.
     */
    private int formatPriority(String format) {
        final String upper = format.toUpperCase();
        for (int i = 0; i < FORMAT_PRIORITY.size(); i++) {
            if (upper.contains(FORMAT_PRIORITY.get(i))) return i;
        }
        return Integer.MAX_VALUE; // unsupported format
    }

    /**
     * Maps an IA format string to a MIME type string used by InternetArchiveAudioTrack.
     */
    public static String formatToMime(String format) {
        if (format == null) return "audio/mpeg";
        final String upper = format.toUpperCase();

        if (upper.contains("MP3")) return "audio/mpeg";
        if (upper.contains("OGG")) return "audio/ogg";
        if (upper.contains("FLAC")) return "audio/flac";
        if (upper.contains("MP4") || upper.contains("M4A") || upper.contains("AAC")) return "audio/mp4";
        if (upper.contains("WAV")) return "audio/wav";

        return "audio/mpeg"; // safe default
    }

    /**
     * URL-encodes a filename but preserves slashes and dots.
     * IA filenames can contain spaces — these must be percent-encoded.
     */
    private static String urlEncodeFileName(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private JsonBrowser fetchJson(String url) throws IOException {
        final HttpGet get = new HttpGet(url);
        get.setHeader("Accept", "application/json");

        try (CloseableHttpResponse response = getHttpInterface().execute(get)) {
            final int status = response.getStatusLine().getStatusCode();
            if (status == 404) return null;
            if (status != 200) {
                throw new IOException("Unexpected status " + status + " from " + url);
            }
            final String body = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);
            return JsonBrowser.parse(body);
        }
    }

    // ── LavaPlayer boilerplate ────────────────────────────────────────────────

    @Override
    public boolean isTrackEncodable(AudioTrack track) {
        return false;
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) throws IOException {
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
        // mimeType not persisted; default to mp3 on decode
        return new InternetArchiveAudioTrack(trackInfo, "audio/mpeg", this);
    }
}
