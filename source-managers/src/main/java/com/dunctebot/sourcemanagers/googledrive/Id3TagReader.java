/*
 * Copyright 2021 Duncan "duncte123" Sterken
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 */

package com.dunctebot.sourcemanagers.googledrive;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Minimal ID3v2 tag parser.
 * Reads title (TIT2), artist (TPE1), duration (TLEN) and cover art (APIC)
 * from raw bytes — no external dependencies required.
 */
public class Id3TagReader {

    public String title;
    public String artist;
    public long durationMs = -1;   // from TLEN frame, in milliseconds
    public String artworkDataUri;  // "data:image/jpeg;base64,..."

    /**
     * Parses ID3v2 tags from the supplied byte array.
     *
     * @param data raw bytes from the start of the audio file (64 KB is usually enough)
     * @return populated Id3TagReader, or an empty one if no ID3 header found
     */
    public static Id3TagReader parse(byte[] data) {
        final Id3TagReader result = new Id3TagReader();

        if (data.length < 10) {
            return result;
        }

        // ID3v2 header: "ID3" + version (2 bytes) + flags (1 byte) + size (4 bytes syncsafe)
        if (data[0] != 'I' || data[1] != 'D' || data[2] != '3') {
            return result;
        }

        final int version = data[3] & 0xFF; // 3 = ID3v2.3, 4 = ID3v2.4
        if (version < 3) {
            // ID3v2.2 uses 3-char frame IDs — too rare to handle here
            return result;
        }

        final int tagSize = decodeSyncsafe(data, 6);
        final int headerSize = 10;
        int pos = headerSize;

        // Skip extended header if present (flag bit 6)
        if ((data[5] & 0x40) != 0 && pos + 4 <= data.length) {
            final int extSize = version == 4
                    ? decodeSyncsafe(data, pos)
                    : readInt(data, pos);
            pos += extSize;
        }

        final int end = Math.min(headerSize + tagSize, data.length);

        while (pos + 10 <= end) {
            final String frameId = new String(data, pos, 4, StandardCharsets.ISO_8859_1);

            // Reached padding
            if (frameId.charAt(0) == '\0') {
                break;
            }

            final int frameSize = version == 4
                    ? decodeSyncsafe(data, pos + 4)
                    : readInt(data, pos + 4);

            // flags: 2 bytes
            pos += 10;

            if (frameSize <= 0 || pos + frameSize > end) {
                break;
            }

            switch (frameId) {
                case "TIT2":
                    result.title = readTextFrame(data, pos, frameSize);
                    break;
                case "TPE1":
                    result.artist = readTextFrame(data, pos, frameSize);
                    break;
                case "TLEN":
                    final String tlen = readTextFrame(data, pos, frameSize);
                    if (tlen != null) {
                        try {
                            result.durationMs = Long.parseLong(tlen.trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    break;
                case "APIC":
                    result.artworkDataUri = readApicFrame(data, pos, frameSize);
                    break;
                default:
                    break;
            }

            pos += frameSize;
        }

        return result;
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Reads a text frame. First byte is the text encoding:
     *   0 = ISO-8859-1, 1 = UTF-16 BOM, 2 = UTF-16BE, 3 = UTF-8
     */
    private static String readTextFrame(byte[] data, int offset, int size) {
        if (size < 2) return null;

        final int encodingByte = data[offset] & 0xFF;
        final Charset charset = charsetFor(encodingByte);
        final int textStart = offset + 1;
        final int textLen = size - 1;

        if (textStart + textLen > data.length) return null;

        String text = new String(data, textStart, textLen, charset).trim();
        // Strip null terminators
        final int nullIdx = text.indexOf('\0');
        if (nullIdx >= 0) {
            text = text.substring(0, nullIdx);
        }
        return text.isEmpty() ? null : text;
    }

    /**
     * Reads an APIC (attached picture) frame and returns a data URI string.
     * Format: encoding(1) + mimeType(n) + \0 + pictureType(1) + description(n) + \0 + imageData
     */
    private static String readApicFrame(byte[] data, int offset, int size) {
        if (size < 4) return null;

        int pos = offset;
        final int end = offset + size;

        // encoding byte
        final int encodingByte = data[pos++] & 0xFF;
        final Charset descCharset = charsetFor(encodingByte);

        // MIME type (ISO-8859-1, null terminated)
        final int mimeEnd = indexOfNull(data, pos, end, 1);
        if (mimeEnd < 0) return null;
        final String mimeType = new String(data, pos, mimeEnd - pos, StandardCharsets.ISO_8859_1);
        pos = mimeEnd + 1;

        if (pos >= end) return null;

        // Picture type byte (3 = cover art, but we accept any)
        pos++; // skip picture type

        // Description (null terminated, encoding-aware: UTF-16 uses 2-byte null)
        final int nullWidth = (encodingByte == 1 || encodingByte == 2) ? 2 : 1;
        final int descEnd = indexOfNull(data, pos, end, nullWidth);
        if (descEnd < 0) return null;
        pos = descEnd + nullWidth;

        if (pos >= end) return null;

        // Remaining bytes = image data
        final int imageLen = end - pos;
        if (imageLen <= 0) return null;

        final String base64 = Base64.getEncoder().encodeToString(
                java.util.Arrays.copyOfRange(data, pos, pos + imageLen)
        );

        final String mime = mimeType.isEmpty() ? "image/jpeg" : mimeType;
        return "data:" + mime + ";base64," + base64;
    }

    private static Charset charsetFor(int encodingByte) {
        switch (encodingByte) {
            case 1:  return StandardCharsets.UTF_16;
            case 2:  return StandardCharsets.UTF_16BE;
            case 3:  return StandardCharsets.UTF_8;
            default: return StandardCharsets.ISO_8859_1;
        }
    }

    /** Finds the index of a null terminator of given width within [start, end). */
    private static int indexOfNull(byte[] data, int start, int end, int width) {
        for (int i = start; i + width <= end; i += width) {
            boolean isNull = true;
            for (int j = 0; j < width; j++) {
                if (data[i + j] != 0) {
                    isNull = false;
                    break;
                }
            }
            if (isNull) return i;
        }
        return -1;
    }

    /** Reads a 4-byte big-endian integer. */
    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    /** Decodes a 4-byte syncsafe integer (ID3v2.4 sizes). */
    private static int decodeSyncsafe(byte[] data, int offset) {
        return ((data[offset] & 0x7F) << 21)
                | ((data[offset + 1] & 0x7F) << 14)
                | ((data[offset + 2] & 0x7F) << 7)
                | (data[offset + 3] & 0x7F);
    }
}
