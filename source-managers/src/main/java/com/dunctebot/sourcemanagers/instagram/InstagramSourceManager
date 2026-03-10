package com.dunctebot.sourcemanagers.instagram;

import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;

import org.json.*;

import java.io.InputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public class InstagramSourceManager implements AudioSourceManager {

    private final List<Pattern> patterns;
    private final Map<String, String> apiConfig;

    public InstagramSourceManager() {
        patterns = Arrays.asList(
            Pattern.compile("^https?://(?:www\\.)?instagram\\.com/reels/audio/(\\d+)"),
            Pattern.compile("^https?://(?:www\\.)?instagram\\.com/p/([\\w-]+)"),
            Pattern.compile("^https?://(?:www\\.)?instagram\\.com/(?:reels?|reel)/([\\w-]+)")
        );

        apiConfig = new HashMap<>();
        apiConfig.put("apiUrl", "https://www.instagram.com/api/graphql");
        apiConfig.put("audioApiUrl", "https://www.instagram.com/api/v1/clips/music/");
        apiConfig.put("docIdPost", "10015901848480474");
        apiConfig.put("jazoest", "2957");
    }

    private boolean isLinkMatch(String link) {
        return patterns.stream().anyMatch(p -> p.matcher(link).matches());
    }

    private Map<String, String> extractInfo(String url) {
        for (int i = 0; i < patterns.size(); i++) {
            Matcher m = patterns.get(i).matcher(url);
            if (m.find()) {
                if (i == 0) return Map.of("id", m.group(1), "type", "audio");
                String pathSegment = url.contains("/reel/") || url.contains("/reels/") ? "reel" : "p";
                return Map.of("id", m.group(1), "type", "post", "pathSegment", pathSegment);
            }
        }
        return Map.of("id", null, "type", null);
    }

    public boolean setup() {
        try {
            String body = fetchInstagramHomepage();

            String csrfToken = matchRegex(body, "\"csrf_token\":\"(.*?)\"");
            String igAppId = matchRegex(body, "\"appId\":\"(.*?)\"");
            String fbLsd = matchRegex(body, "\"LSD\",\\[\\],\\{\"token\":\"(.*?)\"\\},");
            if (csrfToken == null || igAppId == null || fbLsd == null) return false;

            apiConfig.put("csrfToken", csrfToken);
            apiConfig.put("igAppId", igAppId);
            apiConfig.put("fbLsd", fbLsd);

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String fetchInstagramHomepage() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("https://www.instagram.com/").openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        try (InputStream in = conn.getInputStream(); Scanner s = new Scanner(in, StandardCharsets.UTF_8)) {
            return s.useDelimiter("\\A").next();
        }
    }

    private String matchRegex(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private JSONObject fetchFromGraphQL(String postId, String pathSegment) throws Exception {
        String variables = new JSONObject(Map.of(
            "shortcode", postId,
            "fetch_comment_count", "null",
            "fetch_related_profile_media_count", "null",
            "parent_comment_count", "null",
            "child_comment_count", "null"
        )).toString();

        String payload = "variables=" + URLEncoder.encode(variables, StandardCharsets.UTF_8) +
                "&doc_id=" + apiConfig.get("docIdPost") +
                "&lsd=" + apiConfig.get("fbLsd") +
                "&jazoest=" + apiConfig.get("jazoest");

        HttpURLConnection conn = (HttpURLConnection) new URL(apiConfig.get("apiUrl")).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));

        try (InputStream in = conn.getInputStream(); Scanner s = new Scanner(in, StandardCharsets.UTF_8)) {
            String response = s.useDelimiter("\\A").next();
            return new JSONObject(response);
        }
    }

    private JSONObject fetchFromAudioAPI(String audioId) throws Exception {
        String body = "audio_cluster_id=" + URLEncoder.encode(audioId, StandardCharsets.UTF_8)
                + "&lsd=" + apiConfig.get("fbLsd")
                + "&jazoest=" + apiConfig.get("jazoest")
                + "&__user=0&__a=1";

        HttpURLConnection conn = (HttpURLConnection) new URL(apiConfig.get("audioApiUrl")).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));

        try (InputStream in = conn.getInputStream(); Scanner s = new Scanner(in, StandardCharsets.UTF_8)) {
            String response = s.useDelimiter("\\A").next();
            if (response.startsWith("for (;;);")) response = response.substring("for (;;);".length());
            return new JSONObject(response);
        }
    }

    public AudioTrack resolveTrack(String url) throws FriendlyException {
        Map<String, String> info = extractInfo(url);
        String id = info.get("id");
        String type = info.get("type");
        String pathSegment = info.get("pathSegment");

        if (id == null || type == null) {
            throw new FriendlyException("Invalid Instagram URL", FriendlyException.Severity.COMMON, null);
        }

        try {
            JSONObject data;
            if (type.equals("post")) {
                data = fetchFromGraphQL(id, pathSegment);
            } else if (type.equals("audio")) {
                data = fetchFromAudioAPI(id);
            } else {
                throw new FriendlyException("Unknown URL type", FriendlyException.Severity.SUSPICIOUS, null);
            }

            String videoUrl = type.equals("post") ?
                    data.getJSONObject("data").getJSONObject("xdt_shortcode_media").getString("video_url") :
                    data.getJSONObject("payload").getJSONObject("metadata").getString("progressive_download_url");

            return new DelegatedAudioTrack(videoUrl, new AudioTrackInfo("Instagram Content", "Unknown", 0, url, false, null));

        } catch (Exception e) {
            throw new FriendlyException("Failed to fetch Instagram track", FriendlyException.Severity.COMMON, e);
        }
    }

    @Override
    public String getSourceName() {
        return "instagram";
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, String trackUrl) {
        if (!isLinkMatch(trackUrl)) return null;
        try {
            return resolveTrack(trackUrl);
        } catch (FriendlyException e) {
            return null;
        }
    }

    @Override
    public void shutdown() {
        // Nenhuma ação necessária
    }
}
