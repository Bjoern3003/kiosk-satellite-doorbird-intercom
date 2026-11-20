// SPDX-License-Identifier: Apache-2.0
package io.github.bjoern3003.doorbird;

import android.content.Context;
import android.media.AudioManager;

/** Restores the voice-call volume, including after a plugin process crash. */
final class CallVolume {
    interface Listener {
        void onPrevious(int index);
    }

    private final AudioManager audio;
    private final Listener listener;
    private int previous;
    private int reached = 100;

    CallVolume(Context context, int savedPrevious, Listener listener) {
        audio = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        previous = savedPrevious;
        this.listener = listener;
    }

    synchronized String apply(int percent) {
        reached = 100;
        try {
            if (previous < 0) {
                previous = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
                listener.onPrevious(previous);
            }
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
            int min = android.os.Build.VERSION.SDK_INT >= 28 ? audio.getStreamMinVolume(AudioManager.STREAM_VOICE_CALL) : 0;
            int index = Math.max(min, Math.min(max, Math.round(max * Math.max(0, Math.min(100, percent)) / 100f)));
            if (audio.isVolumeFixed()) return "fixed";
            audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, index, 0);
            int now = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
            if (max > 0) reached = Math.round(100f * now / max);
            return now == index ? null : now + " of " + max + " instead of " + index;
        } catch (RuntimeException refused) {
            return "refused (" + refused.getMessage() + ")";
        }
    }

    synchronized int reachedPercent() {
        return reached;
    }

    synchronized void restore() {
        if (previous < 0) return;
        try {
            audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, previous, 0);
        } catch (RuntimeException ignored) {
            return;
        }
        previous = -1;
        listener.onPrevious(-1);
    }
}
