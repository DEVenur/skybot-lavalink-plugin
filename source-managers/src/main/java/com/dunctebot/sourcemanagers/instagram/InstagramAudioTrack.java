package com.dunctebot.sourcemanagers.instagram;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.delegated.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaces;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackState;

import java.io.InputStream;
import java.io.IOException;

public class InstagramAudioTrack extends DelegatedAudioTrack {

    private final InstagramSourceManager sourceManager;
    private final String trackUrl;

    public InstagramAudioTrack(AudioTrackInfo trackInfo, InstagramSourceManager sourceManager, String trackUrl) {
        super(trackInfo);
        this.sourceManager = sourceManager;
        this.trackUrl = trackUrl;
    }

    @Override
    public void process(com.sedmelluq.discord.lavaplayer.player.AudioPlayer player, AudioPlayerManager manager) throws Exception {
        HttpInterface httpInterface = null;
        try {
            httpInterface = HttpInterfaces.createDefault();
            InputStream stream = sourceManager.loadStream(trackUrl);
            if (stream == null) {
                throw new IOException("Failed to retrieve Instagram stream for track: " + trackUrl);
            }
            this.decodeStream(player, stream);
        } finally {
            if (httpInterface != null) {
                httpInterface.close();
            }
        }
    }

    private void decodeStream(com.sedmelluq.discord.lavaplayer.player.AudioPlayer player, InputStream stream) throws IOException {
        // Lavalink expects the stream to be in a compatible format, 
        // typically MP4 audio/video. DelegatedAudioTrack can handle basic MP4 decoding.
        this.delegate.process(player, stream);
    }

    @Override
    protected AudioTrack makeClone() {
        return new InstagramAudioTrack(info, sourceManager, trackUrl);
    }

    @Override
    public void stop() {
        super.stop();
    }

    @Override
    public AudioTrackState getState() {
        return super.getState();
    }
                        }
