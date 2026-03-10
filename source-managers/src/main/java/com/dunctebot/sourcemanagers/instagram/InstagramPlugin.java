package com.dunctebot.sourcemanagers.instagram;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.source.DelegatedAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourcePlugin;

public class InstagramPlugin implements AudioSourcePlugin {

    private final InstagramSourceManager sourceManager;

    public InstagramPlugin() {
        this.sourceManager = new InstagramSourceManager();
    }

    @Override
    public void load(AudioPlayerManager manager) {
        if (manager instanceof DefaultAudioPlayerManager defaultManager) {
            defaultManager.registerSourceManager(sourceManager);
        } else {
            throw new FriendlyException(
                "InstagramPlugin requires DefaultAudioPlayerManager",
                FriendlyException.Severity.SUSPICIOUS,
                null
            );
        }
    }

    @Override
    public void shutdown() {
        sourceManager.shutdown();
    }

    @Override
    public String getName() {
        return "InstagramPlugin";
    }

    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }
}
