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

import com.sedmelluq.discord.lavaplayer.tools.http.HttpContextFilter;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import org.apache.http.HttpResponse;
import org.apache.http.client.CookieStore;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicCookieStore;

import java.util.stream.Collectors;

import static com.dunctebot.sourcemanagers.Utils.fakeChrome;

public class TikTokAudioTrackHttpManager implements AutoCloseable {

    protected final HttpInterfaceManager httpInterfaceManager;
    private final CookieStore cookieStore = new BasicCookieStore();

    public TikTokAudioTrackHttpManager() {
        this.httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();

        this.httpInterfaceManager.configureBuilder(builder -> {
            builder.setDefaultCookieStore(cookieStore);
        });

        this.httpInterfaceManager.setHttpContextFilter(new TikTokFilter());
    }

    public HttpInterface getHttpInterface() {
        return httpInterfaceManager.getInterface();
    }

    @Override
    public void close() throws Exception {
        httpInterfaceManager.close();
    }

    private class TikTokFilter implements HttpContextFilter {

        @Override
        public void onContextOpen(HttpClientContext context) {
            context.setCookieStore(cookieStore);
        }

        @Override
        public void onContextClose(HttpClientContext context) {
            // nothing to do
        }

        @Override
        public void onRequest(HttpClientContext context, HttpUriRequest request, boolean isRepetition) {

            boolean isVideoRequest =
                    request.getURI().getPath() != null &&
                    request.getURI().getPath().contains("video");

            fakeChrome(request, isVideoRequest);

            request.setHeader(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            );

            request.setHeader("Accept-Language", "en-US,en;q=0.9");
            request.setHeader("Connection", "keep-alive");
            request.setHeader("Referer", "https://www.tiktok.com/");

            String cookies = context.getCookieStore()
                    .getCookies()
                    .stream()
                    .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                    .collect(Collectors.joining("; "));

            if (!cookies.isEmpty()) {
                request.setHeader("Cookie", cookies);
            }
        }

        @Override
        public boolean onRequestResponse(HttpClientContext context, HttpUriRequest request, HttpResponse response) {
            return false;
        }

        @Override
        public boolean onRequestException(HttpClientContext context, HttpUriRequest request, Throwable error) {
            return false;
        }
    }
}
