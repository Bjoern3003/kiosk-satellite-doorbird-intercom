// SPDX-License-Identifier: Apache-2.0
package io.github.bjoern3003.doorbird;

import java.util.HashMap;
import java.util.Map;

public final class SettingsTest {
    public static void main(String[] args) {
        Map<String, Object> values = new HashMap<>();
        DoorbirdPlugin.Settings settings = new DoorbirdPlugin.Settings(values);
        check(settings.configured(), false);
        check(settings.streamPath, "/mpeg/media.amp");
        check(settings.rtspPort, 554);
        check(settings.callVolume, 70);
        check(settings.chimeVolume, 40);
        check(settings.micRate, "Automatic");
        values.put("host", "192.168.1.50");
        values.put("username", "abc1230001");
        values.put("password", "secret");
        values.put("stream", "720p");
        values.put("rtspPort", 8557);
        settings = new DoorbirdPlugin.Settings(values);
        check(settings.configured(), true);
        check(settings.streamPath, "/mpeg/720p/media.amp");
        check(settings.rtspPort, 8557);
        values.put("stream", "1080p");
        check(new DoorbirdPlugin.Settings(values).streamPath, "/mpeg/1080p/media.amp");
        System.out.println("SettingsTest passed");
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
