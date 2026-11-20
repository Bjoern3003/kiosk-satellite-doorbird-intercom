// SPDX-License-Identifier: Apache-2.0
package io.github.bjoern3003.doorbird;

import android.content.Context;
import android.view.Surface;
import android.view.View;
import io.github.bjoern3003.doorbird.RingController.State;
import io.github.bjoern3003.doorbird.api.DoorbirdEvents;
import io.github.bjoern3003.doorbird.api.DoorbirdHttp;
import io.github.bjoern3003.doorbird.media.MediaStream;
import io.github.bjoern3003.doorbird.media.StreamHub;
import io.github.bjoern3003.doorbird.talk.DoorbirdTalk;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/** DoorBird intercom for Kiosk Satellite, using only the door station's local LAN API. */
public final class DoorbirdPlugin implements KioskPlugin, RingController.Actions {
    static final String OVERLAY = "doorbell";
    private static final String ENTITY_EVENT = "ks.ha.entity.";
    private static final int MAX_DECODER_RESTARTS = 2;
    private static final long DUCK_TAIL_MILLIS = 600;

    private volatile PluginHost host;
    private volatile boolean stopped;
    private volatile Settings settings;
    private volatile Texts texts = Texts.of("Automatic");
    private volatile RingController controller;
    private volatile DoorbellView view;
    private volatile DoorbirdEvents events;
    private ScheduledExecutorService ticker;
    private volatile WakeWordGuard wakeWord;
    private volatile MusicPause music;

    private MediaStream stream;
    private VideoRenderer videoRenderer;
    private Object videoOwner;
    private Surface videoSurface;
    private int streamGeneration;
    private int reconnects;
    private int decoderRestarts;
    private DoorbirdAudioRenderer audioRenderer;
    private DoorbirdTalk talk;
    private int talkGeneration;
    private int talkRetries;
    private int talkLostFor = -1;
    private MicCapture mic;
    private CallVolume callVolume;
    private CallMode callMode;
    private long ringAt;
    private volatile int liveCallVolume;
    private boolean talking;
    private ScheduledFuture<?> unduck;
    private volatile boolean callPushToTalk;
    private volatile long countdownStart, countdownMillis;
    private final Set<String> followed = new HashSet<>();
    private final Map<String, Map<?, ?>> snapshots = new ConcurrentHashMap<>();
    private volatile boolean viewerOpen;
    private final PressGate pressGate = new PressGate();
    private volatile ScreenRestore screenRestore = new ScreenRestore();
    private volatile boolean headless;
    private volatile Map<String, Object> values = new HashMap<>();
    private final Object valuesLock = new Object();

    static final Map<String, String> CONTROLS = new HashMap<>();
    static {
        CONTROLS.put("ring", "ring");
        CONTROLS.put("chime", "chime");
        CONTROLS.put("wake_screen", "wakeScreen");
        CONTROLS.put("chime_volume", "chimeVolume");
    }

    @Override public void start(PluginHost host, Map<String, Object> values) {
        stopped = false;
        this.host = host;
        headless = headless(host);
        Texts.setAssets(host::openAsset);
        settings = new Settings(values);
        this.values = new HashMap<>(values);
        texts = Texts.of(settings.language);
        liveCallVolume = settings.callVolume;
        controller = new RingController(this, () -> System.nanoTime() / 1_000_000, settings.autoCloseMillis);
        controller.setMaxCallMillis(settings.maxCallMillis);
        wakeWord = new WakeWordGuard(new WakeWordGuard.Host() {
            @Override public void command(String name, Map<String, Object> arguments, WakeWordGuard.Result result) {
                PluginHost h = DoorbirdPlugin.this.host;
                if (h == null) { result.done(false, null); return; }
                try { h.executeCommand(name, arguments, (ok, data, error) -> result.done(ok, data)); }
                catch (RuntimeException unavailable) { result.done(false, null); }
            }

            @Override public void remember(boolean mutedByUs) {
                PluginHost h = DoorbirdPlugin.this.host;
                if (h == null) return;
                try {
                    h.updateSettings(Collections.<String, Object>singletonMap("wakeWordRestore", mutedByUs));
                    DoorbirdPlugin.this.remember("wakeWordRestore", mutedByUs);
                } catch (RuntimeException | LinkageError older) {}
            }
        });
        wakeWord.restore(settings.wakeWordRestore);
        music = new MusicPause(new MusicPause.Host() {
            @Override public void command(String name, Map<String, Object> arguments, MusicPause.Result result) {
                PluginHost h = DoorbirdPlugin.this.host;
                if (h == null) { result.done(false, null); return; }
                try { h.executeCommand(name, arguments, (ok, data, error) -> result.done(ok, data)); }
                catch (RuntimeException unavailable) { result.done(false, null); }
            }

            @Override public void remember(boolean pausedByUs) {
                PluginHost h = DoorbirdPlugin.this.host;
                if (h == null) return;
                try {
                    h.updateSettings(Collections.<String, Object>singletonMap("musicRestore", pausedByUs));
                    DoorbirdPlugin.this.remember("musicRestore", pausedByUs);
                } catch (RuntimeException | LinkageError older) {}
            }
        });
        music.restore(settings.musicRestore);
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            return ThreadGuard.create("doorbird-ticker", r);
        });
        ticker.scheduleWithFixedDelay(() -> ThreadGuard.run(() -> {
            RingController c = controller;
            if (c != null) c.tick();
        }), 1, 1, TimeUnit.SECONDS);
        publishText("last_ring", texts.lastRingReading(), texts.noneYet());
        publishControls();
        screenRestore = new ScreenRestore();
        if (!headless) followScreen(host);
        connect();
    }

    private void followScreen(PluginHost h) {
        try {
            h.subscribe("screen.state");
            h.subscribe("screensaver.state");
            ScreenRestore r = screenRestore;
            h.executeCommand("isScreenOn", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
                if (ok && data instanceof Boolean) r.readScreen((Boolean) data);
            });
            h.executeCommand("isScreensaverActive", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
                if (ok && data instanceof Boolean) r.readScreensaver((Boolean) data);
            });
        } catch (RuntimeException unavailable) {
            log("screen: state is unknown, so the screen stays on after a call: " + unavailable.getMessage());
        }
    }

    @Override public void configure(Map<String, Object> values) {
        synchronized (valuesLock) { this.values = new HashMap<>(values); }
        apply(values);
    }

    private void apply(Map<String, Object> values) {
        Settings previous = settings;
        settings = new Settings(values);
        texts = Texts.of(settings.language);
        liveCallVolume = settings.callVolume;
        RingController c = controller;
        if (c != null) {
            c.setAutoCloseMillis(settings.autoCloseMillis);
            c.setMaxCallMillis(settings.maxCallMillis);
        }
        publishControls();
        if (previous == null || !previous.sameConnection(settings)) connect();
        if (viewerOpen) {
            followCards(settings.cards);
            DoorbellView v = view;
            Settings now = settings;
            if (v != null) v.post(() -> {
                v.setColumnPosition(now.columnPosition);
                v.setCallButtons(now.callButtons);
                v.setCards(now.cards);
            });
        }
    }

    @Override public void execute(String command, Map<String, Object> arguments) {
        RingController c = controller;
        if (c == null) return;
        if (headless) {
            if (headlessChimes(command)) headlessPress("Test ring", true);
            return;
        }
        switch (command) {
            case "show": c.open(); break;
            case "testRing": c.onVisitor(false); c.onVisitor(true); break;
            case "dismiss": c.hangUp(); break;
            default: throw new IllegalArgumentException("Unknown command " + command);
        }
    }

    @Override public void onEvent(String event, Map<String, Object> payload) {
        if ("settings.button".equals(event) && "check".equals(payload.get("id"))) {
            check(String.valueOf(payload.get("run")));
            return;
        }
        if (event.startsWith(ENTITY_EVENT)) {
            String entityId = event.substring(ENTITY_EVENT.length());
            synchronized (followed) {
                if (!followed.contains(entityId)) return;
                snapshots.put(entityId, payload);
            }
            DoorbellView v = view;
            if (v != null) v.post(() -> v.entityChanged(entityId, payload));
            return;
        }
        if (event.startsWith("switch.") || event.startsWith("number.")) {
            control(event, payload);
            return;
        }
        if ("ks.screen.state".equals(event)) {
            screenRestore.screen(Boolean.TRUE.equals(payload.get("on")));
            return;
        }
        if ("ks.screensaver.state".equals(event)) {
            screenRestore.screensaver(Boolean.TRUE.equals(payload.get("active")));
            return;
        }
        if ("overlay.closed".equals(event) && OVERLAY.equals(payload.get("key"))) {
            RingController c = controller;
            if (c != null) c.hangUp();
        }
    }

    @Override public void stop() {
        stopped = true;
        WakeWordGuard guard = wakeWord;
        if (guard != null) guard.close();
        host = null;
        DoorbirdEvents client = events;
        events = null;
        if (client != null) client.stop();
        ScheduledExecutorService t = ticker;
        ticker = null;
        if (t != null) t.shutdownNow();
        controller = null;
        view = null;
        viewerOpen = false;
        synchronized (followed) { followed.clear(); }
        snapshots.clear();
        stopMedia();
    }

    private synchronized void connect() {
        DoorbirdEvents previous = events;
        events = null;
        if (previous != null) previous.stop();
        Settings s = settings;
        if (!s.configured()) {
            status(texts.enterSettings(), true);
            publishText("connection", texts.connectionReading(), texts.notConfigured());
            return;
        }
        DoorbirdEvents[] self = new DoorbirdEvents[1];
        DoorbirdHttp api = new DoorbirdHttp(s.host, 80, s.username, s.password);
        DoorbirdEvents client = new DoorbirdEvents(api, s.username, applicationContext(), new DoorbirdEvents.Listener() {
            private String logged = "";

            @Override public void onConnection(boolean connected, String detail) {
                if (events != self[0]) return;
                Texts t = texts;
                boolean connecting = "connecting".equals(detail);
                if (!connecting && !detail.equals(logged)) log("events: " + detail);
                if (!connecting) logged = detail;
                String shown = connected ? t.connected() : connecting ? t.connectingState() : ConnectionCheck.problem(detail, t);
                publishText("connection", t.connectionReading(), shown);
                status(connected ? t.connectedTo(s.name(t)) : t.doorbellProblem(shown), !connected && !connecting);
            }

            @Override public void onDiagnostic(String message) {
                if (events == self[0]) log(message);
            }

            @Override public void onRing(String button) {
                RingController c = controller;
                if (c == null || events != self[0]) return;
                log("ring: DoorBird button " + button);
                publishText("last_ring", texts.lastRingReading(), new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()));
                visitor(c);
            }
        });
        self[0] = client;
        events = client;
        client.start();
    }

    private void visitor(RingController c) {
        PressGate.Result result;
        synchronized (pressGate) {
            result = pressGate.report(true, ringsHere(settings, headless), System.nanoTime() / 1_000_000);
        }
        if (result == PressGate.Result.PASS) c.onVisitor(true);
        else if (result == PressGate.Result.SILENT_PRESS && headless) headlessPress("a press", false);
        else if (result == PressGate.Result.SILENT_PRESS) {
            log("ring: off on this device (Ring on this device), so only Last ring and Doorbell pressed");
            fireTrigger("doorbellPressed");
        }
    }

    private static Context applicationContext() {
        try {
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (Throwable unavailable) {
            return null;
        }
    }

    private static boolean headless(PluginHost host) {
        try { return host.isHeadless(); } catch (RuntimeException | LinkageError older) { return false; }
    }

    private void headlessPress(String why, boolean test) {
        Settings s = settings;
        boolean chime = headlessChime(s, test);
        if (chime) Chime.play(s.chimeVolume, s.chimeStream, this::log);
        log("ring: " + why + (chime ? ", chime only: this device has no screen" : !s.chime ? ", no chime (Play a chime off), no screen" : ": off on this device (Ring on this device)"));
        fireTrigger("doorbellPressed");
    }

    static boolean headlessChime(Settings s, boolean test) {
        return s.chime && (s.ring || test);
    }

    static boolean ringsHere(Settings s, boolean headless) {
        return s.ring && !headless;
    }

    static boolean headlessChimes(String command) {
        if (command.equals("testRing")) return true;
        if (command.equals("show") || command.equals("dismiss")) return false;
        throw new IllegalArgumentException("Unknown command " + command);
    }

    static boolean publishes(String key, boolean headless) {
        return !(headless && key.equals("wake_screen"));
    }

    @Override public void openViewer(State state, boolean rang) {
        PluginHost h = host;
        if (h == null || headless) return;
        if (rang && settings.wakeScreen) screenRestore.ring();
        else screenRestore.forget();
        if (settings.wakeScreen) {
            command(h, "stopScreensaver");
            command(h, "screenOn");
        }
        final Texts t = texts;
        final String name = settings.name(t);
        log("viewer: open (" + (rang ? "ring" : "by hand") + ")");
        stopMedia();
        callPushToTalk = settings.pushToTalk;
        startStream();
        viewerOpen = true;
        fireTrigger("callScreenOpened");
        followCards(settings.cards);
        h.showOverlay(OVERLAY, OverlaySpec.fullScreen().onTop(true).closeOnBack(true), new OverlayFactory() {
            @Override public View create(Context context, KsTheme theme) {
                RingController c = controller;
                State current = c == null ? state : c.state();
                synchronized (DoorbirdPlugin.this) {
                    if (callVolume == null) callVolume = new CallVolume(context, settings.callVolumeRestore, previous -> {
                        PluginHost currentHost = host;
                        if (currentHost == null) return;
                        try {
                            currentHost.updateSettings(Collections.<String, Object>singletonMap("callVolumeRestore", (double) previous));
                            remember("callVolumeRestore", (double) previous);
                        } catch (RuntimeException | LinkageError unavailable) {}
                    });
                    if (callMode == null) callMode = new CallMode(context);
                }
                DoorbellView created = new DoorbellView(context, theme, t, name, current, liveCallVolume, new DoorbellView.Callback() {
                    @Override public void hangUp() { RingController c = controller; if (c != null) c.hangUp(); }
                    @Override public void speaker() { RingController c = controller; if (c != null) c.speaker(); }
                    @Override public void answer() { RingController c = controller; if (c != null) c.answer(); }
                    @Override public void toggleSpeaker() { RingController c = controller; if (c != null) c.toggleSpeaker(); }
                    @Override public void toggleMic() { RingController c = controller; if (c != null) c.toggleMic(); }
                    @Override public void touched() { RingController c = controller; if (c != null) c.touched(); }
                    @Override public void surfaceReady(Surface surface) { attachVideo(this, surface); }
                    @Override public void surfaceGone() { detachVideo(this); }
                    @Override public void volumeChanged(int percent, boolean done) { setCallVolume(percent, done); }
                    @Override public void cardPressed(Cards.Card card) { runCard(card); }
                    @Override public void holdToTalk(boolean pressed) { DoorbirdPlugin.this.holdToTalk(pressed); }
                });
                view = created;
                for (Map.Entry<String, Map<?, ?>> known : snapshots.entrySet()) created.entityChanged(known.getKey(), known.getValue());
                created.setColumnPosition(settings.columnPosition);
                created.setCallButtons(settings.callButtons);
                created.setCards(settings.cards);
                created.setPushToTalk(callPushToTalk);
                if (c != null) created.update(c.state(), c.speakerOn(), c.micOn());
                created.setCountdown(countdownStart, countdownMillis);
                return created;
            }

            @Override public void onThemeChanged(View changed, KsTheme theme) {
                if (changed instanceof DoorbellView) ((DoorbellView) changed).applyTheme(theme);
            }

            @Override public void onDestroy(View destroyed) {
                if (view == destroyed) view = null;
            }
        });
    }

    @Override public void updateViewer(State state, boolean speakerOn, boolean micOn) {
        DoorbellView v = view;
        if (v != null) v.post(() -> v.update(state, speakerOn, micOn));
    }

    @Override public void countdown(long startedAt, long durationMillis) {
        countdownStart = startedAt;
        countdownMillis = durationMillis;
        DoorbellView v = view;
        if (v != null) v.post(() -> v.setCountdown(startedAt, durationMillis));
    }

    @Override public void closeViewer() {
        log("viewer: close");
        WakeWordGuard guard = wakeWord;
        if (guard != null) guard.setActive(false);
        MusicPause m = music;
        if (m != null) m.setActive(false);
        PluginHost h = host;
        if (h != null) h.hideOverlay(OVERLAY);
        viewerOpen = false;
        ScreenRestore r = screenRestore;
        ScreenRestore.Before before = r.before();
        String restore = r.close(settings.restoreScreen);
        if (before != null) log("screen: before the ring " + before.name().toLowerCase(Locale.ROOT) + ", " + (restore == null ? "left as it is" : restore));
        if (h != null && restore != null) command(h, restore);
        fireTrigger("callScreenClosed");
        followCards(Collections.<Cards.Card>emptyList());
        stopMedia();
    }

    private void followCards(List<Cards.Card> cards) {
        PluginHost h = host;
        Set<String> wanted = new HashSet<>();
        for (Cards.Card card : cards) if (card.hasEntity()) wanted.add(card.entityId);
        synchronized (followed) {
            for (String entityId : new HashSet<>(followed)) {
                if (wanted.contains(entityId)) continue;
                followed.remove(entityId);
                snapshots.remove(entityId);
                try { if (h != null) h.unsubscribe("ha.entity." + entityId); } catch (RuntimeException gone) {}
            }
            for (String entityId : wanted) {
                if (followed.contains(entityId) || h == null) continue;
                try {
                    h.subscribe("ha.entity." + entityId);
                    followed.add(entityId);
                } catch (RuntimeException refused) {
                    log("card: cannot follow " + entityId + ": " + refused.getMessage());
                }
            }
        }
    }

    private void runCard(Cards.Card card) {
        PluginHost h = host;
        DoorbellView v = view;
        if (h == null || !card.action) return;
        Texts t = texts;
        if (v != null) v.cardFeedback(card, DoorbellView.RUNNING);
        try {
            h.runAction(card.actionPath(), (ok, data, error) -> {
                log("card: " + (card.label.isEmpty() ? card.actionPath() : card.label) + (ok ? " ran" : " failed: " + error));
                DoorbellView now = view;
                if (ok && card.hasEntity()) return;
                String shown = ok ? t.cardDone() : t.cardFailed();
                if (now != null) now.post(() -> now.cardFeedback(card, shown));
            });
        } catch (UnsupportedOperationException older) {
            log("card: this Kiosk Satellite version cannot run actions");
            if (v != null) v.cardFeedback(card, t.cardsNeedNewerKs());
        } catch (RuntimeException refused) {
            log("card: " + refused.getMessage());
            if (v != null) v.cardFeedback(card, t.cardFailed());
        }
    }

    @Override public synchronized void setAudio(boolean speakerOn, boolean micOn) {
        if (speakerOn || micOn) screenRestore.answered();
        WakeWordGuard guard = wakeWord;
        if (guard != null) guard.setActive(speakerOn || micOn);
        MusicPause m = music;
        if (m != null && (speakerOn || micOn) && !settings.musicPause.equals("Off")) m.setActive(true);
        log("audio: speaker " + speakerOn + ", microphone " + micOn);
        if ((speakerOn || micOn) && settings.callMode && callMode != null) {
            String entered = callMode.enter();
            if (entered != null) log("audio: " + entered);
        }
        if (speakerOn && audioRenderer == null) {
            audioRenderer = new DoorbirdAudioRenderer(api(settings), message -> {
                showNotice(texts.doorbellSoundStopped());
                log(message);
            });
            applyCallVolume(liveCallVolume, true);
            if (talking) audioRenderer.setDuck(Loudness.DUCK);
        } else if (!speakerOn && audioRenderer != null) {
            releaseLater(audioRenderer::release);
            audioRenderer = null;
        }
        if (micOn && mic == null) {
            if (talk == null) {
                talk = openTalk(settings);
                talk.start();
            }
            if (callPushToTalk) log("microphone: push to talk");
            mic = openMic();
        } else if (!micOn && mic != null) {
            releaseLater(mic::release);
            mic = null;
            if (talk != null) {
                releaseLater(talk::stop);
                talk = null;
                talkGeneration++;
            }
        }
    }

    private MicCapture openMic() {
        return new MicCapture(talk, settings.microphone, settings.micRate, callPushToTalk && !talking, state -> {
            log("microphone: " + state);
            boolean live = state.startsWith("live");
            voiceGoesOut(live);
            if (!live) showNotice(texts.noMicrophone());
        });
    }

    private DoorbirdTalk openTalk(Settings s) {
        final int generation = ++talkGeneration;
        return new DoorbirdTalk(api(s), state -> {
            if (!state.equals("connecting") && !state.equals("open")) log("talk: " + state);
            else if (state.equals("open")) log("talk: open");
            if (state.equals("open")) talkOpened(generation);
            if (state.startsWith("talk failed") || state.startsWith("talk ended")) talkLost(generation);
        });
    }

    private synchronized void talkOpened(int generation) {
        if (generation == talkGeneration) talkRetries = 0;
        voiceGoesOut(true);
    }

    private synchronized void talkLost(int generation) {
        if (generation == talkLostFor) return;
        talkLostFor = generation;
        voiceGoesOut(false);
        showNotice(texts.voiceNotThrough());
        if (generation != talkGeneration || stopped || !viewerOpen || talk == null || mic == null) return;
        ScheduledExecutorService t = ticker;
        if (t == null) return;
        long delay = Math.min(5_000, 1_000L << Math.min(talkRetries, 3));
        talkRetries++;
        log("talk: reopening in " + delay / 1000 + " s");
        try {
            t.schedule(() -> ThreadGuard.run(() -> reopenTalk(generation)), delay, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException stopped) {}
    }

    private synchronized void reopenTalk(int generation) {
        if (generation != talkGeneration || stopped || !viewerOpen || talk == null || mic == null) return;
        final DoorbirdTalk old = talk;
        talk = openTalk(settings);
        talk.start();
        releaseLater(old::stop);
        final MicCapture oldMic = mic;
        releaseLater(oldMic::release);
        mic = openMic();
    }

    private void check(String run) {
        PluginHost h = host;
        if (h == null) return;
        Settings s = settings;
        Texts t = texts;
        Thread thread = ThreadGuard.create(() -> {
            try {
                h.setupResult(run, "running", t.checking());
                ConnectionCheck.Result result = ConnectionCheck.run(s, headless, new ConnectionCheck.Player() {
                    @Override public Boolean canPlay(boolean hevc, int width, int height) {
                        return VideoRenderer.canPlay(hevc ? android.media.MediaFormat.MIMETYPE_VIDEO_HEVC : android.media.MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
                    }
                    @Override public Boolean decodes(byte[] keyframe) { return VideoRenderer.decodes(keyframe); }
                }, DoorbirdPlugin.this::log, t);
                h.setupResult(run, result.ok ? "success" : "error", (result.ok ? "✓ " : "✗ ") + result.message);
            } catch (RuntimeException | LinkageError gone) {}
        }, "doorbird-check");
        thread.setDaemon(true);
        thread.start();
    }

    private static DoorbirdHttp api(Settings s) {
        return new DoorbirdHttp(s.host, 80, s.username, s.password);
    }

    private static void releaseLater(Runnable release) {
        Thread thread = ThreadGuard.create("doorbird-cleanup", release);
        thread.setDaemon(true);
        thread.start();
    }

    private synchronized void startStream() {
        Settings s = settings;
        if (stream != null || !s.configured() || stopped) return;
        ringAt = System.nanoTime() / 1_000_000;
        log("media: start RTSP stream " + s.streamPath);
        publishText("first_frame", texts.firstPictureReading(), texts.waiting());
        reconnects = 0;
        decoderRestarts = 0;
        stream = openStream(s, ++streamGeneration);
        stream.start();
    }

    private MediaStream openStream(Settings s, int generation) {
        return new StreamHub(s.host, s.rtspPort, s.streamPath, s.username, s.password, state -> {
            if (!state.equals("connecting") && !state.equals("streaming")) log("video: " + state);
            if (state.equals("streaming")) streamDelivers(generation);
            if (state.startsWith("stream failed") || state.startsWith("stream ended")) {
                showMessage(videoProblem(state));
                if (!state.contains("H.265")) streamLost(generation);
            }
        });
    }

    private synchronized void streamDelivers(int generation) {
        if (generation == streamGeneration) reconnects = 0;
    }

    private synchronized void streamLost(int generation) {
        if (generation != streamGeneration || stopped || !viewerOpen) return;
        ScheduledExecutorService t = ticker;
        if (t == null) return;
        long delay = Math.min(5_000, 1_000L << Math.min(reconnects, 3));
        reconnects++;
        log("media: reopening the stream in " + delay / 1000 + " s");
        try {
            t.schedule(() -> ThreadGuard.run(() -> reopenStream(generation)), delay, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException stopped) {}
    }

    private synchronized void reopenStream(int generation) {
        if (generation != streamGeneration || stopped || !viewerOpen || stream == null) return;
        final MediaStream old = stream;
        stream = openStream(settings, ++streamGeneration);
        if (videoRenderer != null) stream.attachVideo(videoRenderer);
        stream.start();
        releaseLater(old::stop);
    }

    private String videoProblem(String state) {
        if (state.contains("H.265")) return texts.videoH265();
        return state.startsWith("stream ended") ? texts.videoStopped() : texts.noVideo();
    }

    private synchronized void attachVideo(Object owner, Surface surface) {
        log("viewer: surface ready" + (stream == null ? ", no stream" : ""));
        final VideoRenderer old = videoRenderer;
        final Surface oldSurface = videoSurface;
        videoOwner = owner;
        videoSurface = surface;
        videoRenderer = newRenderer(surface, false);
        if (stream != null) stream.attachVideo(videoRenderer);
        if (old != null || oldSurface != null) releaseLater(() -> release(old, oldSurface));
    }

    private VideoRenderer newRenderer(Surface surface, boolean replacement) {
        final VideoRenderer[] self = new VideoRenderer[1];
        self[0] = new VideoRenderer(surface, new VideoRenderer.Listener() {
            @Override public void onVideoSize(int width, int height) {
                DoorbellView v = view;
                if (v != null) v.post(() -> v.setVideoSize(width, height));
            }

            @Override public void onVideoStats(long firstFrameMillis, double decodeMillis) {
                if (firstFrameMillis >= 0 && !replacement) {
                    long sinceRing = System.nanoTime() / 1_000_000 - ringAt;
                    publishText("first_frame", texts.firstPictureReading(), texts.afterRing(sinceRing));
                }
            }

            @Override public void onVideoError(String message) {
                showMessage(message == null ? texts.noPictureYet() : texts.videoNotShown());
                log(message == null ? texts.noPictureYet() : message);
                if (message != null) decoderFailed(self[0]);
            }

            @Override public void onVideoLog(String message) { log(message); }
        });
        return self[0];
    }

    private synchronized void decoderFailed(VideoRenderer failed) {
        if (failed == null || failed != videoRenderer || videoSurface == null || decoderRestarts >= MAX_DECODER_RESTARTS) return;
        decoderRestarts++;
        final Surface surface = videoSurface;
        log("video decoder: starting a new one");
        releaseLater(() -> {
            failed.release();
            synchronized (DoorbirdPlugin.this) {
                if (videoRenderer != failed || videoSurface != surface) return;
                videoRenderer = newRenderer(surface, true);
                if (stream != null) stream.attachVideo(videoRenderer);
            }
        });
    }

    private static void release(VideoRenderer renderer, Surface surface) {
        if (renderer != null) renderer.release();
        if (surface != null) surface.release();
    }

    private synchronized void detachVideo(Object owner) {
        log("viewer: surface gone" + (owner != videoOwner ? " (an older viewer)" : ""));
        if (owner != videoOwner) return;
        videoOwner = null;
        if (stream != null) stream.attachVideo(null);
        final VideoRenderer oldVideo = videoRenderer;
        final Surface oldSurface = videoSurface;
        releaseLater(() -> release(oldVideo, oldSurface));
        videoRenderer = null;
        videoSurface = null;
    }

    private synchronized void stopMedia() {
        streamGeneration++;
        talkGeneration++;
        talking = false;
        if (unduck != null) unduck.cancel(false);
        unduck = null;
        if (stream == null && videoRenderer == null && audioRenderer == null && talk == null && mic == null) {
            if (callVolume != null) callVolume.restore();
            if (callMode != null) callMode.leave();
            return;
        }
        log("media: stop");
        final MediaStream oldStream = stream;
        final VideoRenderer oldVideo = videoRenderer;
        final Surface oldSurface = videoSurface;
        final DoorbirdAudioRenderer oldAudio = audioRenderer;
        final DoorbirdTalk oldTalk = talk;
        final MicCapture oldMic = mic;
        talk = null;
        mic = null;
        stream = null;
        videoRenderer = null;
        videoSurface = null;
        videoOwner = null;
        audioRenderer = null;
        if (callVolume != null) callVolume.restore();
        if (callMode != null) callMode.leave();
        Thread cleanup = ThreadGuard.create(() -> {
            release(oldVideo, oldSurface);
            try { if (oldStream != null) oldStream.stop(); } catch (RuntimeException ignored) {}
            if (oldMic != null) oldMic.release();
            if (oldTalk != null) oldTalk.stop();
            if (oldAudio != null) oldAudio.release();
        }, "doorbird-cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    private void setCallVolume(int percent, boolean done) {
        liveCallVolume = percent;
        applyCallVolume(percent, done);
        if (!done) return;
        PluginHost h = host;
        if (h == null) return;
        try {
            h.updateSettings(Collections.<String, Object>singletonMap("callVolume", (double) percent));
            remember("callVolume", (double) percent);
        } catch (RuntimeException | LinkageError older) {}
    }

    private synchronized void applyCallVolume(int percent, boolean report) {
        CallVolume volume = callVolume;
        String refused = volume == null ? "unavailable" : volume.apply(percent);
        if (audioRenderer != null) audioRenderer.setGain(refused == null ? 1f : Loudness.remaining(percent, volume == null ? 100 : volume.reachedPercent()));
        if (report) log(refused == null ? "audio: call volume " + percent + "%" : "audio: call volume " + percent + "%, played by the plugin: Android's call volume " + refused);
    }

    private void holdToTalk(boolean pressed) {
        RingController c = controller;
        if (pressed && c != null && !c.micOn()) {
            synchronized (this) { talking = true; }
            c.talk();
        }
        pushToTalk(pressed);
    }

    private synchronized void pushToTalk(boolean pressed) {
        talking = pressed;
        if (mic != null) mic.setMuted(!pressed);
        if (unduck != null) unduck.cancel(false);
        unduck = null;
        if (pressed) {
            if (audioRenderer != null) audioRenderer.setDuck(Loudness.DUCK);
            return;
        }
        ScheduledExecutorService t = ticker;
        if (t == null) return;
        try {
            unduck = t.schedule(() -> ThreadGuard.run(() -> {
                synchronized (DoorbirdPlugin.this) {
                    if (!talking && audioRenderer != null) audioRenderer.setDuck(1f);
                }
            }), DUCK_TAIL_MILLIS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException stopped) {}
    }

    private void showMessage(String message) {
        DoorbellView v = view;
        if (v != null) v.post(() -> v.setMessage(message));
    }

    private void voiceGoesOut(boolean works) {
        DoorbellView v = view;
        if (v != null) v.post(() -> v.setVoiceGoesOut(works));
    }

    private void showNotice(String message) {
        DoorbellView v = view;
        if (v != null) v.post(() -> v.showNotice(message));
    }

    @Override public void ring() {
        MusicPause m = music;
        if (m != null && settings.musicPause.equals("At the ring")) m.setActive(true);
        if (settings.chime) Chime.play(settings.chimeVolume, settings.chimeStream, this::log);
    }

    @Override public void ringTrigger() { fireTrigger("doorbellPressed"); }
    @Override public void answeredTrigger() { fireTrigger("answered"); }
    @Override public void missedTrigger() { fireTrigger("missedRing"); }

    private void fireTrigger(String id) {
        PluginHost h = host;
        if (h == null) return;
        try { h.fireTrigger(id); } catch (RuntimeException | LinkageError older) {}
    }

    private static void command(PluginHost h, String name) {
        try { h.executeCommand(name, Collections.<String, Object>emptyMap(), (ok, data, error) -> {}); } catch (RuntimeException ignored) {}
    }

    private Map<String, Object> remember(String key, Object value) {
        synchronized (valuesLock) {
            Map<String, Object> changed = new HashMap<>(values);
            changed.put(key, value);
            values = changed;
            return changed;
        }
    }

    private void publishControls() {
        PluginHost h = host;
        Settings s = settings;
        Texts t = texts;
        if (h == null || s == null) return;
        try {
            h.publishSwitch("ring", t.ringControl(), s.ring);
            h.publishSwitch("chime", t.chimeControl(), s.chime);
            if (publishes("wake_screen", headless)) h.publishSwitch("wake_screen", t.wakeControl(), s.wakeScreen);
            h.publishNumber("chime_volume", t.chimeVolumeControl(), chimeVolumeRange(), (double) s.chimeVolume);
        } catch (UnsupportedOperationException | LinkageError older) {
        } catch (RuntimeException refused) { log("controls: " + refused.getMessage()); }
    }

    private static Map<String, Object> chimeVolumeRange() {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("min", 5);
        range.put("max", 100);
        range.put("step", 5);
        range.put("unit", "%");
        range.put("mode", "slider");
        return range;
    }

    private void control(String event, Map<String, Object> payload) {
        PluginHost h = host;
        if (h == null) return;
        String key = CONTROLS.get(event.substring(event.indexOf('.') + 1));
        Object value;
        if (key == null) return;
        if (event.startsWith("switch.") && !key.equals("chimeVolume") && payload.get("on") instanceof Boolean) value = payload.get("on");
        else if (event.equals("number.chime_volume") && payload.get("value") instanceof Number) {
            long volume = Math.round(((Number) payload.get("value")).doubleValue() / 5) * 5;
            value = (int) Math.max(5, Math.min(100, volume));
        } else return;
        try { h.updateSettings(Collections.singletonMap(key, value)); }
        catch (RuntimeException | LinkageError failed) {
            log("controls: " + key + " not saved: " + failed.getMessage());
            publishControls();
            return;
        }
        log("controls: " + key + " set to " + value + " from Home Assistant");
        apply(remember(key, value));
    }

    private void publishText(String key, String name, String value) {
        PluginHost h = host;
        if (h == null) return;
        try { h.publishTextSensor(key, name, value.length() > 512 ? value.substring(0, 512) : value); } catch (RuntimeException ignored) {}
    }

    private void status(String message, boolean error) {
        PluginHost h = host;
        if (h != null) h.status(message, error);
    }

    private void log(String message) {
        PluginHost h = host;
        if (h != null) h.log(message);
    }

    static final class Settings {
        final String host, username, password, streamPath, language, microphone, micRate, musicPause, restoreScreen, chimeStream;
        private final String name;
        final boolean ring, chime, wakeScreen, wakeWordRestore, musicRestore, callMode, pushToTalk;
        final long autoCloseMillis, maxCallMillis;
        final int callVolume, chimeVolume, rtspPort, callVolumeRestore;
        final List<Cards.Card> cards;
        final List<Cards.Card> callButtons;
        final int columnPosition;

        Settings(Map<String, Object> values) {
            host = string(values, "host");
            username = string(values, "username");
            password = string(values, "password");
            name = string(values, "name");
            language = string(values, "language").equals("Automatisch") ? "Automatic" : string(values, "language");
            microphone = microphone(string(values, "microphone"));
            String rate = string(values, "micRate");
            micRate = rate.isEmpty() || rate.equals("Automatisch") ? "Automatic" : rate;
            String talkMode = string(values, "talkMode");
            pushToTalk = talkMode.equals("Push to talk") || talkMode.equals("Push-to-Talk");
            chime = !Boolean.FALSE.equals(values.get("chime"));
            Object chimeLevel = values.get("chimeVolume");
            chimeVolume = chimeLevel instanceof Number ? Math.max(0, Math.min(100, ((Number) chimeLevel).intValue())) : 40;
            chimeStream = chimeStream(string(values, "chimeStream"));
            String pause = string(values, "musicPause");
            musicPause = pause.equals("Beim Klingeln") ? "At the ring" : pause.equals("Bei Lautsprecher oder Annehmen") ? "At Speaker or Answer"
                : pause.equals("At the ring") || pause.equals("At Speaker or Answer") ? pause : "Off";
            String restore = string(values, "restoreScreen");
            restoreScreen = restore.equals("Immer") ? ScreenRestore.ALWAYS : restore.equals("Nie") ? ScreenRestore.NEVER
                : restore.equals("Wenn nicht angenommen") ? ScreenRestore.WHEN_NOT_ANSWERED
                : java.util.Arrays.asList(ScreenRestore.MODES).contains(restore) ? restore : ScreenRestore.WHEN_NOT_ANSWERED;
            wakeScreen = !Boolean.FALSE.equals(values.get("wakeScreen"));
            ring = !Boolean.FALSE.equals(values.get("ring"));
            autoCloseMillis = autoClose(string(values, "autoClose"));
            maxCallMillis = maxCall(string(values, "maxCall"));
            wakeWordRestore = Boolean.TRUE.equals(values.get("wakeWordRestore"));
            musicRestore = Boolean.TRUE.equals(values.get("musicRestore"));
            callMode = !Boolean.FALSE.equals(values.get("callMode"));
            String stream = string(values, "stream");
            streamPath = stream.equals("720p") ? "/mpeg/720p/media.amp" : stream.equals("1080p") ? "/mpeg/1080p/media.amp" : "/mpeg/media.amp";
            Object port = values.get("rtspPort");
            rtspPort = port instanceof Number && ((Number) port).intValue() >= 1 && ((Number) port).intValue() <= 65535 ? ((Number) port).intValue() : 554;
            Object volume = values.get("callVolume");
            callVolume = volume instanceof Number ? Math.max(0, Math.min(100, ((Number) volume).intValue())) : 70;
            Object savedVolume = values.get("callVolumeRestore");
            callVolumeRestore = savedVolume instanceof Number && ((Number) savedVolume).intValue() >= 0 && ((Number) savedVolume).intValue() <= 1000
                ? ((Number) savedVolume).intValue() : -1;
            cards = Cards.parse(values.get("cards"));
            callButtons = Cards.parseCallButtons(values.get("callButtons"));
            String column = string(values, "landscapeColumn");
            columnPosition = column.startsWith("Centred") || column.equals("Zentriert") ? DoorbellView.COLUMN_CENTRE
                : column.startsWith("At the top") || column.equals("Oben") ? DoorbellView.COLUMN_TOP : DoorbellView.COLUMN_BOTTOM;
        }

        String name(Texts texts) { return name.isEmpty() ? texts.defaultName() : name; }
        boolean configured() { return !host.isEmpty() && !username.isEmpty() && !password.isEmpty(); }
        boolean sameConnection(Settings other) { return host.equals(other.host) && username.equals(other.username) && password.equals(other.password); }

        private static String microphone(String value) {
            switch (value) {
                case "Sprachkommunikation": return "Voice communication";
                case "Mikrofon": return "Microphone";
                case "Spracherkennung": return "Voice recognition";
                case "Unbearbeitet": return "Unprocessed";
                case "": case "Automatisch": return "Automatic";
                default: return value;
            }
        }
        private static String chimeStream(String value) {
            switch (value) {
                case "Benachrichtigungen": return "Notifications";
                case "Medien": return "Media";
                case "Alarm": return "Alarm";
                case "Notifications": case "Media": return value;
                default: return "Notifications";
            }
        }
        private static long autoClose(String choice) {
            switch (choice) {
                case "Off": case "Aus": return 0;
                case "30 seconds": case "30 Sekunden": return 30_000;
                case "2 minutes": case "2 Minuten": return 120_000;
                case "5 minutes": case "5 Minuten": return 300_000;
                default: return 60_000;
            }
        }
        private static long maxCall(String choice) {
            switch (choice) {
                case "Off": case "Aus": return 0;
                case "5 minutes": case "5 Minuten": return 300_000;
                case "30 minutes": case "30 Minuten": return 1_800_000;
                default: return 600_000;
            }
        }
        private static String string(Map<String, Object> values, String key) {
            Object value = values.get(key);
            return value instanceof String ? ((String) value).trim() : "";
        }
    }
}
