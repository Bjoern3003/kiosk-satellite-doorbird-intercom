// SPDX-License-Identifier: Apache-2.0
package io.github.bjoern3003.doorbird;

import static io.github.bjoern3003.doorbird.PressGate.Result.DROP;
import static io.github.bjoern3003.doorbird.PressGate.Result.PASS;
import static io.github.bjoern3003.doorbird.PressGate.Result.SILENT_PRESS;

import java.util.HashMap;
import java.util.Map;

/** A device without a screen, such as an Echo Dot (#20): presses only chime, and nothing reaches the call screen. */
public final class HeadlessTest {
    public static void main(String[] args) {
        // Presses never go to the call screen without a screen, whatever Ring on this device says; with one, as before.
        check(DoorbirdPlugin.ringsHere(settings(true, true), true), false);
        check(DoorbirdPlugin.ringsHere(settings(false, true), true), false);
        check(DoorbirdPlugin.ringsHere(settings(true, true), false), true);
        check(DoorbirdPlugin.ringsHere(settings(false, true), false), false);

        // So a held press counts once, and again after 20 s, as the controller would ring again; the release passes.
        PressGate gate = new PressGate();
        boolean ring = DoorbirdPlugin.ringsHere(settings(true, true), true);
        check(gate.report(true, ring, 0), SILENT_PRESS);
        check(gate.report(true, ring, 5_000), DROP);
        check(gate.report(true, ring, 21_000), SILENT_PRESS);
        check(gate.report(false, ring, 25_000), PASS);
        check(gate.report(true, ring, 26_000), SILENT_PRESS);

        // The chime: a press follows Ring on this device and Play a chime; Test ring follows Play a chime only.
        check(DoorbirdPlugin.headlessChime(settings(true, true), false), true);
        check(DoorbirdPlugin.headlessChime(settings(false, true), false), false);
        check(DoorbirdPlugin.headlessChime(settings(true, false), false), false);
        check(DoorbirdPlugin.headlessChime(settings(false, true), true), true);
        check(DoorbirdPlugin.headlessChime(settings(true, false), true), false);

        // Commands: Test ring chimes, Open and Close do nothing, anything else is unknown, as on a panel.
        check(DoorbirdPlugin.headlessChimes("testRing"), true);
        check(DoorbirdPlugin.headlessChimes("show"), false);
        check(DoorbirdPlugin.headlessChimes("dismiss"), false);
        try {
            DoorbirdPlugin.headlessChimes("open");
            throw new AssertionError("an unknown command was accepted");
        } catch (IllegalArgumentException expected) {
            // as on a panel
        }

        // Home Assistant controls: all of them on a panel; no Wake the screen without a screen.
        for (String key : DoorbirdPlugin.CONTROLS.keySet()) {
            check(key + " " + DoorbirdPlugin.publishes(key, false), key + " true");
            check(key + " " + DoorbirdPlugin.publishes(key, true), key + " " + !key.equals("wake_screen"));
        }
        System.out.println("HeadlessTest passed");
    }

    private static DoorbirdPlugin.Settings settings(boolean ring, boolean chime) {
        Map<String, Object> values = new HashMap<>();
        values.put("ring", ring);
        values.put("chime", chime);
        return new DoorbirdPlugin.Settings(values);
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
