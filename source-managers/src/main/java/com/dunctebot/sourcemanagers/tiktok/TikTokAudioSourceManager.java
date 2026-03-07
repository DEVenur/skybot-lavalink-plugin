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

package com.dunctebot.sourcemanagers.tiktok;

import com.dunctebot.sourcemanagers.AbstractDuncteBotHttpSource;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.ExceptionTools;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dunctebot.sourcemanagers.Utils.fakeChrome;

public class TikTokAudioSourceManager extends AbstractDuncteBotHttpSource {

    private final TikTokAudioTrackHttpManager httpManager = new TikTokAudioTrackHttpManager();

    private static final Pattern VIDEO_REGEX = Pattern.compile(
            "https://(?:www\\.|m\\.)?tiktok\\.com/@(?<user>[^/]+)/video/(?<video>[0-9]+)"
    );

    public TikTokAudioSourceManager() {
        super(false);
    }

    @Override
    public String getSourceName() {
        return "tiktok";
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {

        Matcher matcher = VIDEO_REGEX.matcher(reference.identifier);

        if (!matcher.find()) {
            return null;
        }

        String user = matcher.group("user");
        String video = matcher.group("video");

        try {

            MetaData metaData = extractData(user, video);

            if (metaData == null) {
                return null;
            }

            return new TikTokAudioTrack(metaData.toTrackInfo(), this);

        } catch (Exception e) {
            throw ExceptionTools.wrapUnfriendlyExceptions(
                    "TikTok extraction failed",
                    Severity.SUSPICIOUS,
                    e
            );
        }
    }

    @Override
    public boolean isTrackEncodable(AudioTrack track) {
        return true;
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) {
        // nothing
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) {
        return new TikTokAudioTrack(trackInfo, this);
    }

    @Override
    public HttpInterface getHttpInterface() {
        return httpManager.getHttpInterface();
    }

    MetaData extractData(String userId, String videoId) throws Exception {

        String url = "https://www.tiktok.com/@" + userId + "/video/" + videoId;

        return extractData(url);
    }

    protected MetaData extractData(String url) throws Exception {

        String api = "https://www.tikwm.com/api/?url=" + url;

        HttpGet request = new HttpGet(api);
        fakeChrome(request);

        try (CloseableHttpResponse response = getHttpInterface().execute(request)) {

            int statusCode = response.getStatusLine().getStatusCode();

            if (statusCode != 200) {
                throw new IOException("TikTok API returned status: " + statusCode);
            }

            String body = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);

            JsonBrowser json = JsonBrowser.parse(body);

            JsonBrowser data = json.get("data");

            if (data.isNull()) {
                throw new FriendlyException("TikTok API returned invalid data", Severity.SUSPICIOUS, null);
            }

            MetaData meta = new MetaData();

            meta.pageUrl = url;
            meta.videoId = data.get("id").safeText();
            meta.title = data.get("title").safeText();
            meta.cover = data.get("cover").safeText();
            meta.videoUrl = data.get("play").safeText();
            meta.uri = meta.videoUrl;

            meta.duration = Integer.parseInt(data.get("duration").safeText("0"));

            meta.musicUrl = data.get("music").safeText();
            meta.uniqueId = data.get("author").get("unique_id").safeText();

            return meta;
        }
    }

    protected static class MetaData {

        String cover;
        String pageUrl;
        String videoId;
        String videoUrl;
        String uri;
        int duration;
        String title;

        String musicUrl;

        String uniqueId;

        AudioTrackInfo toTrackInfo() {

            return new AudioTrackInfo(
                    title,
                    uniqueId,
                    duration * 1000L,
                    videoId,
                    false,
                    pageUrl,
                    cover,
                    null
            );
        }

        @Override
        public String toString() {
            return "MetaData{" +
                    "cover='" + cover + '\'' +
                    ", pageUrl='" + pageUrl + '\'' +
                    ", videoId='" + videoId + '\'' +
                    ", videoUrl='" + videoUrl + '\'' +
                    ", uri='" + uri + '\'' +
                    ", duration=" + duration +
                    ", title='" + title + '\'' +
                    ", uniqueId='" + uniqueId + '\'' +
                    '}';
        }
    }
    }
