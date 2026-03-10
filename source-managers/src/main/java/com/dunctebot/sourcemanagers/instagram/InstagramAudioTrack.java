package com.dunctebot.sourcemanagers.instagram;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.delegated.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackState;

import java.io.InputStream;
import java.io.IOException;

public class InstagramAudioTrack extends DelegatedAudioTrack {

    private final InstagramSourceManager sourceManager;
    private final String trackUrl;

    public InstagramAudioTrack(AudioTrackInfo trackInfo, InstagramSourceManager sourceManager) {
        super(trackInfo);
        this.sourceManager = sourceManager;
        this.trackUrl = trackInfo.uri;
    }

    @Override
    public void process(AudioPlayer player, AudioPlayerManager manager) throws Exception {
        try (InputStream stream = sourceManager.loadStream(trackUrl)) {
            if (stream == null) {
                throw new IOException("Não foi possível obter o stream do Instagram: " + trackUrl);
            }
            this.delegate.process(player, stream);
        }
    }

    @Override
    protected AudioTrack makeClone() {
        return new InstagramAudioTrack(info, sourceManager);
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
