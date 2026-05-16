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
import com.dunctebot.sourcemanagers.Mp3Track;
import com.sedmelluq.discord.lavaplayer.container.flac.FlacAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mp3.Mp3AudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.ogg.OggAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.wav.WavAudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;

public class InternetArchiveAudioTrack extends Mp3Track {

    private final String mimeType;

    public InternetArchiveAudioTrack(AudioTrackInfo trackInfo, String mimeType, AbstractDuncteBotHttpSource manager) {
        super(trackInfo, manager);
        this.mimeType = mimeType;
    }

    /**
     * The identifier holds the direct stream URL (set by the source manager).
     * Mp3Track.getPlaybackUrl() defaults to trackInfo.identifier — perfect.
     */
    @Override
    public String getPlaybackUrl() {
        return this.trackInfo.identifier;
    }

    /**
     * Duration is set to Long.MAX_VALUE when unknown (live/unknown-length streams).
     * PersistentHttpStream reads until EOF in that case.
     */
    @Override
    protected long getTrackDuration() {
        return this.trackInfo.length;
    }

    @Override
    protected InternalAudioTrack createAudioTrack(AudioTrackInfo trackInfo, SeekableInputStream stream) {
        switch (mimeType) {
            case "audio/mpeg":
                return new Mp3AudioTrack(trackInfo, stream);
            case "audio/ogg":
                return new OggAudioTrack(trackInfo, stream);
            case "audio/flac":
                return new FlacAudioTrack(trackInfo, stream);
            case "audio/mp4":
            case "audio/x-m4a":
                return new MpegAudioTrack(trackInfo, stream);
            case "audio/wav":
            case "audio/x-wav":
                return new WavAudioTrack(trackInfo, stream);
            default:
                throw new FriendlyException(
                    "Unsupported Internet Archive audio format: " + mimeType,
                    FriendlyException.Severity.COMMON, null
                );
        }
    }
}
