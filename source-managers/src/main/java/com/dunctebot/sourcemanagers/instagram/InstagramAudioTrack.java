package com.dunctebot.sourcemanagers.instagram;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import com.sedmelluq.discord.lavaplayer.track.playback.NonAllocatingAudioFrameBuffer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackState;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaces;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * AudioTrack que representa um vídeo/áudio do Instagram.
 */
public class InstagramAudioTrack extends AudioTrack {

    private final InstagramSourceManager sourceManager;
    private final String trackUrl;

    public InstagramAudioTrack(AudioTrackInfo trackInfo, InstagramSourceManager sourceManager, String trackUrl) {
        super(trackInfo);
        this.sourceManager = sourceManager;
        this.trackUrl = trackUrl;
    }

    @Override
    public void process(AudioPlayer player, AudioPlayerManager manager) throws Exception {
        HttpInterface httpInterface = null;
        InputStream stream = null;
        try {
            // Cria HttpInterface padrão
            httpInterface = HttpInterfaces.createDefault();

            // Abre conexão HTTP para o vídeo do Instagram
            HttpURLConnection connection = (HttpURLConnection) new URL(trackUrl).openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            connection.setRequestMethod("GET");
            connection.connect();

            if (connection.getResponseCode() != 200) {
                throw new FriendlyException("Falha ao acessar o vídeo do Instagram",
                        FriendlyException.Severity.COMMON, null);
            }

            stream = connection.getInputStream();

            // Decodifica o stream usando o buffer padrão do Lavaplayer
            AudioFrame frame;
            NonAllocatingAudioFrameBuffer frameBuffer = new NonAllocatingAudioFrameBuffer();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                frameBuffer.add(buffer, 0, read);
            }
            frame = new AudioFrame(frameBuffer, 0, frameBuffer.getLength());

            // Envia o frame para o player
            player.startTrack(this, false);
            while (frame != null) {
                player.provide(frame);
                frame = null; // Não usamos múltiplos frames no exemplo simplificado
            }

        } finally {
            if (stream != null) {
                stream.close();
            }
            if (httpInterface != null) {
                httpInterface.close();
            }
        }
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
