// SPDX-License-Identifier: Apache-2.0
package io.github.bjoern3003.doorbird;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import io.github.bjoern3003.doorbird.api.DoorbirdHttp;
import io.github.bjoern3003.doorbird.talk.G711;

/** DoorBird's live G.711 mu-law audio, decoded to PCM and played as voice-call audio. */
final class DoorbirdAudioRenderer {
    interface Listener {
        void onAudioError(String message);
    }

    private static final int RATE = 8000;
    private final DoorbirdHttp api;
    private final Listener listener;
    private volatile boolean running = true;
    private volatile float gain = 1f, duck = 1f;
    private DoorbirdHttp.Exchange exchange;
    private AudioTrack track;
    private final Thread thread;

    DoorbirdAudioRenderer(DoorbirdHttp api, Listener listener) {
        this.api = api;
        this.listener = listener;
        thread = new Thread(this::run, "doorbird-audio");
        thread.setDaemon(true);
        thread.start();
    }

    void setGain(float gain) {
        this.gain = gain;
    }

    void setDuck(float duck) {
        this.duck = duck;
    }

    void release() {
        running = false;
        DoorbirdHttp.Exchange current;
        synchronized (this) { current = exchange; }
        if (current != null) current.close();
        thread.interrupt();
        try { thread.join(1_000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private void run() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        } catch (RuntimeException refused) {
            // Default thread priority still works.
        }
        try {
            DoorbirdHttp.Exchange opened = api.openGet("/bha-api/audio-receive.cgi");
            synchronized (this) {
                if (!running) { opened.close(); return; }
                exchange = opened;
            }
            track = newTrack();
            track.setVolume(gain * duck);
            track.play();
            byte[] cushion = new byte[RATE * 120 / 1000 * 2];
            track.write(cushion, 0, cushion.length);
            byte[] encoded = new byte[160];
            byte[] pcm = new byte[320];
            while (running) {
                int offset = 0;
                while (offset < encoded.length) {
                    int count = opened.input.read(encoded, offset, encoded.length - offset);
                    if (count < 0) throw new java.io.IOException("The DoorBird closed the audio stream");
                    offset += count;
                }
                for (int i = 0; i < encoded.length; i++) {
                    short sample = G711.ulawToLinear(encoded[i]);
                    pcm[i * 2] = (byte) sample;
                    pcm[i * 2 + 1] = (byte) (sample >> 8);
                }
                track.setVolume(gain * duck);
                int written = 0;
                while (written < pcm.length && running) written += track.write(pcm, written, pcm.length - written);
            }
        } catch (Exception error) {
            if (running) listener.onAudioError("audio: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        } finally {
            DoorbirdHttp.Exchange current;
            synchronized (this) { current = exchange; exchange = null; }
            if (current != null) current.close();
            if (track != null) {
                try { track.stop(); } catch (RuntimeException ignored) {}
                track.release();
            }
        }
    }

    private static AudioTrack newTrack() {
        int minimum = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(Math.max(minimum, RATE * 2 * 300 / 1000))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build();
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try { track.setStartThresholdInFrames(Math.min(RATE * 120 / 1000, track.getBufferSizeInFrames())); } catch (RuntimeException ignored) {}
        }
        return track;
    }
}
