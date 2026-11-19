// SPDX-License-Identifier: Apache-2.0
package io.github.bjoern3003.doorbird;

import io.github.bjoern3003.doorbird.api.DoorbirdHttp;
import io.github.bjoern3003.doorbird.media.AvcConfig;
import io.github.bjoern3003.doorbird.media.HevcConfig;
import io.github.bjoern3003.doorbird.media.MediaStream;
import io.github.bjoern3003.doorbird.media.SpsInfo;
import io.github.bjoern3003.doorbird.media.StreamHub;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** The settings-page connection check against the real local DoorBird endpoints. */
final class ConnectionCheck {
    static final class Result {
        final boolean ok;
        final String message;
        Result(boolean ok, String message) { this.ok = ok; this.message = message; }
    }

    interface Player {
        Boolean canPlay(boolean hevc, int width, int height);
        Boolean decodes(byte[] keyframe);
    }

    interface Log {
        void line(String text);
    }

    private static final long PICTURE_MILLIS = 12_000;

    private ConnectionCheck() {}

    static Result run(DoorbirdPlugin.Settings settings, boolean headless, Player player, Log log, Texts texts) {
        try {
            if (!settings.configured()) return new Result(false, texts.checkNotConfigured());
            DoorbirdHttp api = api(settings);
            api.session();
            if (headless) return new Result(true, texts.checkOkHeadless());
            byte[] keyframe = firstPicture(settings, log);
            if (keyframe == null) return new Result(false, texts.checkNoVideo());
            boolean hevc = HevcConfig.is(keyframe);
            SpsInfo picture = hevc ? HevcConfig.of(keyframe).size : AvcConfig.of(keyframe).size;
            if (picture != null) {
                Boolean plays = player.canPlay(hevc, picture.width, picture.height);
                if (plays == null) plays = player.decodes(keyframe);
                if (Boolean.FALSE.equals(plays)) {
                    String size = (hevc ? "H.265, " : "") + picture.width + "x" + picture.height;
                    return new Result(false, settings.streamPath.equals("/mpeg/media.amp") ? texts.checkVideoTooLarge(size) : texts.checkMainTooLarge(size));
                }
            }
            try (DoorbirdHttp.Exchange audio = api.openGet("/bha-api/audio-receive.cgi")) {
                if (audio.input.read() < 0) return new Result(false, texts.checkNoTalk());
            } catch (IOException error) {
                log.line("check: listening: " + describe(error));
                return new Result(false, texts.checkNoTalk());
            }
            try (DoorbirdHttp.Exchange talk = api.openPost("/bha-api/audio-transmit.cgi", 1)) {
                talk.output.write(0xff);
                talk.output.flush();
            } catch (IOException error) {
                log.line("check: talking: " + describe(error));
                return new Result(false, texts.checkNoTalk());
            }
            return new Result(true, texts.checkOk());
        } catch (Exception error) {
            if (error instanceof IOException && unreachable((IOException) error)) return new Result(false, texts.checkUnreachable(settings.host));
            if (describe(error).contains("refused the username or password")) return new Result(false, texts.checkLoginRefused());
            return new Result(false, texts.checkFailed(describe(error)));
        }
    }

    private static DoorbirdHttp api(DoorbirdPlugin.Settings settings) {
        return new DoorbirdHttp(settings.host, 80, settings.username, settings.password);
    }

    private static byte[] firstPicture(DoorbirdPlugin.Settings settings, Log log) {
        CountDownLatch done = new CountDownLatch(1);
        byte[][] picture = new byte[1][];
        String[] failed = new String[1];
        MediaStream stream = new StreamHub(settings.host, settings.rtspPort, settings.streamPath, settings.username, settings.password, state -> {
            if (state.startsWith("stream failed") || state.startsWith("stream ended")) {
                failed[0] = state;
                done.countDown();
            }
        });
        stream.attachVideo((annexB, micros, keyframe) -> {
            if (keyframe && picture[0] == null) {
                picture[0] = annexB;
                done.countDown();
            }
        });
        stream.start();
        try {
            if (!done.await(PICTURE_MILLIS, TimeUnit.MILLISECONDS)) failed[0] = "no picture within " + PICTURE_MILLIS + " ms";
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } finally {
            stream.stop();
        }
        if (picture[0] == null) log.line("check: video: " + (failed[0] == null ? "stopped" : failed[0]));
        return picture[0];
    }

    static String problem(String detail, Texts texts) {
        if (detail.contains("refused the username or password")) return texts.problemLogin();
        if (detail.contains("stopped answering") || detail.contains("closed")) return texts.problemNotAnswering();
        for (String unreachable : new String[] {"ConnectException", "NoRouteToHostException", "UnknownHostException", "SocketTimeoutException", "EHOSTUNREACH", "ENETUNREACH"}) {
            if (detail.contains(unreachable)) return texts.problemUnreachable();
        }
        return texts.problemLost();
    }

    private static boolean unreachable(IOException error) {
        return error instanceof ConnectException || error instanceof SocketTimeoutException || error instanceof NoRouteToHostException || error instanceof UnknownHostException;
    }

    private static String describe(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
