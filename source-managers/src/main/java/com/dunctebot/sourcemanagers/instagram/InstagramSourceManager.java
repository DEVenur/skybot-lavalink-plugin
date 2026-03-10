package com.dunctebot.sourcemanagers.instagram;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;

import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Pattern;
import java.util.Scanner;

public class InstagramSourceManager implements AudioSourceManager {

    private final Pattern[] patterns = new Pattern[]{
            Pattern.compile("^https?://(?:www\\.)?instagram\\.com/reels/audio/(\\d+)"),
            Pattern.compile("^https?://(?:www\\.)?instagram\\.com/p/([\\w-]+)"),
            Pattern.compile("^https?://(?:www\\.)?instagram\\.com/(?:reels?|reel)/([\\w-]+)")
    };

    @Override
    public String getSourceName() {
        return "instagram";
    }

    @Override
    public AudioTrack createTrack(AudioTrackInfo trackInfo, AudioPlayerManager manager) {
        return new InstagramAudioTrack(trackInfo, this);
    }

    @Override
    public void shutdown() {
        // Limpar caches ou recursos abertos se necessário
    }

    public boolean isLinkSupported(String link) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(link).matches()) return true;
        }
        return false;
    }

    public InstagramTrackData resolveTrack(String url) throws FriendlyException {
        try {
            // Fetch real do HTML do post/reel
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36");
            connection.setRequestMethod("GET");
            connection.connect();

            if (connection.getResponseCode() != 200) {
                throw new FriendlyException("Erro ao acessar o Instagram: " + connection.getResponseCode(),
                        FriendlyException.Severity.COMMON, null);
            }

            // Ler conteúdo da página
            Scanner scanner = new Scanner(connection.getInputStream(), "UTF-8");
            StringBuilder html = new StringBuilder();
            while (scanner.hasNextLine()) {
                html.append(scanner.nextLine());
            }
            scanner.close();
            connection.disconnect();

            // Tentar extrair URL do vídeo usando regex simples
            String htmlContent = html.toString();
            String videoUrl = null;
            String title = "Instagram Video";

            // Busca pelo JSON embed do vídeo
            Pattern videoPattern = Pattern.compile("\"video_url\":\"(https:[^\"]+)\"");
            java.util.regex.Matcher matcher = videoPattern.matcher(htmlContent);
            if (matcher.find()) {
                videoUrl = matcher.group(1).replaceAll("\\\\u0026", "&").replaceAll("\\\\/", "/");
            }

            if (videoUrl == null) {
                throw new FriendlyException("Não foi possível encontrar vídeo nesta postagem.",
                        FriendlyException.Severity.COMMON, null);
            }

            return new InstagramTrackData(
                    videoUrl,
                    title,
                    "Autor Desconhecido",
                    0,
                    null
            );

        } catch (IOException e) {
            throw new FriendlyException("Erro ao buscar dados do Instagram: " + e.getMessage(),
                    FriendlyException.Severity.COMMON, e);
        }
    }

    @Override
    public AudioTrack decodeTrack(InputStream inputStream, AudioPlayerManager manager) {
        throw new UnsupportedOperationException("Decodificação não suportada para Instagram");
    }

    @Override
    public boolean isTrackEncodable(AudioTrack track) {
        return track instanceof InstagramAudioTrack;
    }

    @Override
    public void encodeTrack(AudioTrack track, java.io.DataOutput output) {
        throw new UnsupportedOperationException("Codificação não suportada para InstagramAudioTrack");
    }

    @Override
    public AudioTrack decodeTrack(java.io.DataInput input, AudioPlayerManager manager) {
        throw new UnsupportedOperationException("Decodificação não suportada para InstagramAudioTrack");
    }

    /**
     * Classe para armazenar dados resolvidos do post/reel.
     */
    public static class InstagramTrackData {
        private final String url;
        private final String title;
        private final String author;
        private final long duration;
        private final String thumbnail;

        public InstagramTrackData(String url, String title, String author, long duration, String thumbnail) {
            this.url = url;
            this.title = title;
            this.author = author;
            this.duration = duration;
            this.thumbnail = thumbnail;
        }

        public String getUrl() { return url; }
        public String getTitle() { return title; }
        public String getAuthor() { return author; }
        public long getDuration() { return duration; }
        public String getThumbnail() { return thumbnail; }
    }

    /**
     * Load stream do vídeo real.
     */
    public InputStream loadStream(String trackUrl) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(trackUrl).openConnection();
        connection.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36");
        connection.setRequestMethod("GET");
        connection.connect();

        if (connection.getResponseCode() != 200) return null;
        return connection.getInputStream();
    }
                   }
