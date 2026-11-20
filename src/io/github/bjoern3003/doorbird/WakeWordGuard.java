// SPDX-License-Identifier: Apache-2.0
package io.github.bjoern3003.doorbird;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Mutes Voice Satellite only while needed and restores only a mute owned by this plugin. */
final class WakeWordGuard {
    interface Host {
        void command(String name, Map<String, Object> arguments, Result result);
        void remember(boolean mutedByUs);
    }

    interface Result {
        void done(boolean ok, Object data);
    }

    private final Host host;
    private boolean wanted;
    private boolean mutedByUs;
    private boolean busy;
    private boolean closed;
    private int unmuteFailures;
    private ScheduledExecutorService retry;

    WakeWordGuard(Host host) {
        this.host = host;
    }

    synchronized void restore(boolean rememberedMutedByUs) {
        if (!rememberedMutedByUs || closed) return;
        mutedByUs = true;
        busy = true;
        command("getWakeWordState", Collections.<String, Object>emptyMap(), (ok, data) -> {
            synchronized (WakeWordGuard.this) {
                busy = false;
                if (closed) return;
                Map<?, ?> state = ok && data instanceof Map ? (Map<?, ?>) data : null;
                if (state == null) {
                    scheduleRestore();
                    return;
                }
                if (!Boolean.TRUE.equals(state.get("available")) || !Boolean.TRUE.equals(state.get("muted"))) {
                    mutedByUs = false;
                    host.remember(false);
                    return;
                }
                settle();
            }
        });
    }

    synchronized void setActive(boolean active) {
        if (closed) return;
        wanted = active;
        if (active) unmuteFailures = 0;
        settle();
    }

    synchronized void close() {
        closed = true;
        wanted = false;
        if (retry != null) retry.shutdownNow();
    }

    private void settle() {
        if (busy || closed) return;
        if (wanted && !mutedByUs) {
            busy = true;
            command("getWakeWordState", Collections.<String, Object>emptyMap(), (ok, data) -> {
                Map<?, ?> state = ok && data instanceof Map ? (Map<?, ?>) data : null;
                boolean canMute = state != null && Boolean.TRUE.equals(state.get("available")) && !Boolean.TRUE.equals(state.get("muted"));
                if (!canMute) {
                    finish(false);
                    return;
                }
                command("setWakeWordMuted", Collections.<String, Object>singletonMap("muted", Boolean.TRUE), (muted, ignored) -> {
                    if (muted) host.remember(true);
                    finish(muted);
                });
            });
        } else if (!wanted && mutedByUs) {
            busy = true;
            command("setWakeWordMuted", Collections.<String, Object>singletonMap("muted", Boolean.FALSE), (unmuted, ignored) -> {
                synchronized (WakeWordGuard.this) {
                    busy = false;
                    if (closed) return;
                    mutedByUs = !unmuted;
                    if (unmuted) {
                        unmuteFailures = 0;
                        host.remember(false);
                        settle();
                    } else {
                        host.remember(true);
                        scheduleUnmute();
                    }
                }
            });
        }
    }

    private synchronized void finish(boolean muted) {
        busy = false;
        mutedByUs = muted;
        if (muted || !wanted) settle();
    }

    private void command(String name, Map<String, Object> arguments, Result result) {
        try {
            host.command(name, arguments, result);
        } catch (Throwable unavailable) {
            result.done(false, null);
        }
    }

    private void scheduleUnmute() {
        if (closed || wanted || unmuteFailures >= 5) return;
        unmuteFailures++;
        try {
            executor().schedule(() -> ThreadGuard.run(() -> {
                synchronized (WakeWordGuard.this) { settle(); }
            }), 1, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    private void scheduleRestore() {
        if (closed || unmuteFailures >= 5) return;
        unmuteFailures++;
        try {
            executor().schedule(() -> ThreadGuard.run(() -> restore(true)), 1, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    private synchronized ScheduledExecutorService executor() {
        if (retry == null) {
            retry = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(() -> ThreadGuard.run(r), "doorbird-wake-word-retry");
                thread.setDaemon(true);
                return thread;
            });
        }
        return retry;
    }
}
