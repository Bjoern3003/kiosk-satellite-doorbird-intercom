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

        Map<String, Object> german = new HashMap<>();
        german.put("language", "Automatisch");
        german.put("microphone", "Sprachkommunikation");
        german.put("micRate", "Automatisch");
        german.put("talkMode", "Push-to-Talk");
        german.put("chimeStream", "Medien");
        german.put("musicPause", "Beim Klingeln");
        german.put("restoreScreen", "Immer");
        german.put("autoClose", "30 Sekunden");
        german.put("maxCall", "30 Minuten");
        german.put("landscapeColumn", "Zentriert");
        german.put("stream", "Standard");
        settings = new DoorbirdPlugin.Settings(german);
        check(settings.language, "Automatic");
        check(settings.microphone, "Voice communication");
        check(settings.micRate, "Automatic");
        check(settings.pushToTalk, true);
        check(settings.chimeStream, "Media");
        check(settings.musicPause, "At the ring");
        check(settings.restoreScreen, ScreenRestore.ALWAYS);
        check(settings.autoCloseMillis, 30_000L);
        check(settings.maxCallMillis, 1_800_000L);
        check(settings.columnPosition, DoorbellView.COLUMN_CENTRE);
        check(settings.streamPath, "/mpeg/media.amp");

        java.util.List<Map<String, Object>> buttons = new java.util.ArrayList<>();
        buttons.add(java.util.Collections.<String, Object>singletonMap("button", "Annehmen / Mikrofon"));
        buttons.add(java.util.Collections.<String, Object>singletonMap("button", "Lautsprecher"));
        buttons.add(java.util.Collections.<String, Object>singletonMap("button", "Ablehnen / Anruf beenden"));
        java.util.List<Cards.Card> parsed = Cards.parseCallButtons(buttons);
        check(parsed.get(0).role, Cards.Role.ANSWER);
        check(parsed.get(1).role, Cards.Role.SPEAKER);
        check(parsed.get(2).role, Cards.Role.HANG_UP);

        Map<String, Object> card = new HashMap<>();
        card.put("entity", "light.porch");
        card.put("style", "Schaltfläche");
        card.put("colorMode", "Feste Farbe");
        card.put("color", "#123456");
        card.put("show", "Im Anruf");
        Cards.Card parsedCard = Cards.parse(java.util.Collections.singletonList(card)).get(0);
        check(parsedCard.button, true);
        check(parsedCard.color, 0xFF123456);
        check(parsedCard.show, Cards.Show.CALL);

        Map<String, Object> english = new HashMap<>();
        english.put("musicPause", "At Speaker or Answer");
        english.put("restoreScreen", "Never");
        english.put("talkMode", "Push to talk");
        settings = new DoorbirdPlugin.Settings(english);
        check(settings.musicPause, "At Speaker or Answer");
        check(settings.restoreScreen, ScreenRestore.NEVER);
        check(settings.pushToTalk, true);
        System.out.println("SettingsTest passed");
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
