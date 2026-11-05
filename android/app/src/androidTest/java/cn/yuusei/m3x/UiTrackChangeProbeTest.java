package cn.yuusei.m3x;

import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/**
 * Finds what blocks the Android UI thread (and therefore every WebView frame) during a real
 * song change on the physical M3X. Records each Looper dispatch; a second pass also samples
 * the UI thread's Java stack from another thread and prints a call tree for the long ones.
 * Existing catalog, no scan; playback is muted and the original song, position, playback
 * state and settings are restored.
 */
@androidx.media3.common.util.UnstableApi
public final class UiTrackChangeProbeTest extends InstrumentationTestCase {
    private static final long LONG_NANOS = 30_000_000L;
    private static final int TREE_MIN_PERCENT = 2;
    private MainActivity activity;
    private final StringBuilder report = new StringBuilder();

    private static final class Dispatch { long start, end; String name; }
    private static final class Sample { long at; StackTraceElement[] stack; }
    private static final class Node {
        int count; final TreeMap<String, Node> children = new TreeMap<>();
    }

    private String js(String code) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); String[] result = {""};
        getInstrumentation().runOnMainSync(() -> activity.web.evaluateJavascript(code, r -> { result[0] = r; latch.countDown(); }));
        assertTrue("JS callback", latch.await(20, TimeUnit.SECONDS)); return result[0];
    }
    private void act(String code) throws Exception {
        assertEquals("JS action " + code, "\"ok\"", js("(function(){try{" + code + ";return 'ok'}catch(e){return String(e)}})()"));
    }
    private void waitJs(String code) throws Exception {
        long end = SystemClock.elapsedRealtime() + 30000;
        while (SystemClock.elapsedRealtime() < end) { if ("true".equals(js(code))) return; Thread.sleep(100); }
        fail("Timed out: " + code);
    }
    private Field field(String name) throws Exception { Field f = PlaybackService.class.getDeclaredField(name); f.setAccessible(true); return f; }

    /** Message.callback class without the instance hash, so repeats group together. */
    private static String label(String line) {
        String text = line.substring(line.indexOf("to ") + 3).replaceAll("@[0-9a-f]+", "");
        return text.replaceAll("\\{[0-9a-f]+[^}]*\\}", "{}");
    }

    private void pass(String name, boolean playing, int switches, boolean sample) throws Exception {
        PlayerApp app = PlayerApp.instance;
        getInstrumentation().runOnMainSync(() -> app.playback.player.setPlayWhenReady(playing));
        Thread.sleep(1500);
        report.append("\n=== ").append(name).append(playing ? " (playing, muted)" : " (paused)").append(sample ? " sampled\n" : "\n");
        List<Long> worst = new ArrayList<>(), total = new ArrayList<>();
        List<Dispatch> longOnes = new ArrayList<>();
        List<Sample> samples = Collections.synchronizedList(new ArrayList<>());
        Thread main = Looper.getMainLooper().getThread();
        boolean[] run = {true};
        Thread sampler = new Thread(() -> {
            while (run[0]) {
                Sample s = new Sample(); s.stack = main.getStackTrace(); s.at = System.nanoTime(); samples.add(s);
                try { Thread.sleep(2); } catch (InterruptedException e) { return; }
            }
        }, "m3x-stack-sampler");
        sampler.setPriority(Thread.MAX_PRIORITY);
        if (sample) sampler.start();
        for (int i = 0; i < switches; i++) {
            List<Dispatch> dispatches = new ArrayList<>();
            long[] open = {0}; long origin = System.nanoTime();
            Looper.getMainLooper().setMessageLogging(line -> {
                long now = System.nanoTime();
                if (line.startsWith(">>>>>")) open[0] = now;
                else if (line.startsWith("<<<<<") && open[0] != 0 && now - open[0] >= LONG_NANOS) {
                    Dispatch d = new Dispatch(); d.start = open[0]; d.end = now; d.name = lastName[0]; dispatches.add(d);
                }
                if (line.startsWith(">>>>>")) lastName[0] = label(line);
            });
            act("M3X_UI.next()" + (playing ? "" : ";document.getElementById('audio').pause()"));
            Thread.sleep(1600);
            Looper.getMainLooper().setMessageLogging(null);
            long max = 0, sum = 0;
            report.append("switch ").append(i).append(':');
            for (Dispatch d : dispatches) {
                long ms = (d.end - d.start) / 1_000_000; max = Math.max(max, ms); sum += ms;
                report.append("\n  +").append((d.start - origin) / 1_000_000).append("ms ").append(ms).append("ms ").append(d.name);
            }
            report.append('\n');
            worst.add(max); total.add(sum); longOnes.addAll(dispatches);
        }
        run[0] = false;
        if (sample) sampler.join(2000);
        Collections.sort(worst); Collections.sort(total);
        report.append("worst dispatch per switch ms ").append(worst).append(" | blocked total per switch ms ").append(total).append('\n');
        if (sample) tree(longOnes, samples);
    }
    private final String[] lastName = {""};

    private String shell(String command) throws Exception {
        android.os.ParcelFileDescriptor pfd = getInstrumentation().getUiAutomation().executeShellCommand(command);
        StringBuilder out = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(pfd.getFileDescriptor()), "UTF-8"))) {
            String line; while ((line = reader.readLine()) != null) out.append(line).append('\n');
        } finally { pfd.close(); }
        return out.toString();
    }
    private String currentId() throws Exception { return new JSONObject(js("nativeCommand('poll')")).getString("id"); }
    /** Hardware / Bluetooth media keys and the notification reach the player through the session. */
    private void mediaKey(int code, String name) throws Exception {
        String before = currentId();
        shell("input keyevent " + code);
        long end = SystemClock.elapsedRealtime() + 5000; boolean changed = false;
        while (!changed && SystemClock.elapsedRealtime() < end) { Thread.sleep(100); changed = !before.equals(currentId()); }
        report.append("media key ").append(name).append(changed ? " changed song\n" : " DID NOT change song\n");
        assertTrue("Media key " + name + " changes song", changed);
    }
    private void sessionState() throws Exception {
        report.append("\n=== session\n");
        android.content.Context context = getInstrumentation().getTargetContext();
        report.append("detected capabilities are the sink default: ").append(androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(context, androidx.media3.common.AudioAttributes.DEFAULT, null).equals(androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)).append('\n');
        for (String line : shell("dumpsys media_session").split("\n")) {
            String t = line.trim();
            if (t.startsWith("metadata:") || t.startsWith("state=") || t.startsWith("queueTitle=") || t.startsWith("flags=") || t.startsWith("active=")) report.append(t.length() > 220 ? t.substring(0, 220) : t).append('\n');
        }
        android.app.NotificationManager notifications = (android.app.NotificationManager) context.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
        for (android.service.notification.StatusBarNotification posted : notifications.getActiveNotifications()) {
            android.app.Notification n = posted.getNotification();
            android.graphics.drawable.Icon large = n.getLargeIcon();
            android.graphics.drawable.Drawable art = large == null ? null : large.loadDrawable(context);
            report.append("notification cover=").append(art == null ? "none" : art.getIntrinsicWidth() + "x" + art.getIntrinsicHeight());
            report.append(" title=").append(n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)).append(" actions=");
            if (n.actions != null) for (android.app.Notification.Action action : n.actions) report.append('[').append(action.title).append(']');
            report.append('\n');
        }
    }
    /** The same client path the media notification uses. */
    private void sessionController(PlayerApp app) throws Exception {
        java.util.concurrent.Future<?>[] connection = {null};
        getInstrumentation().runOnMainSync(() -> connection[0] = new androidx.media3.session.MediaController.Builder(app,
            new androidx.media3.session.SessionToken(app, new android.content.ComponentName(app, PlaybackService.class))).buildAsync());
        androidx.media3.session.MediaController controller = (androidx.media3.session.MediaController) connection[0].get(10, TimeUnit.SECONDS);
        try {
            String before = currentId();
            String[] seen = {""}; boolean[] canNext = {false}; int[] windows = {0};
            getInstrumentation().runOnMainSync(() -> {
                seen[0] = controller.getCurrentMediaItem() == null ? "" : controller.getCurrentMediaItem().mediaId;
                canNext[0] = controller.isCommandAvailable(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT);
                windows[0] = controller.getCurrentTimeline().getWindowCount();
            });
            report.append("controller current=").append(seen[0].equals(before)).append(" canNext=").append(canNext[0]).append(" timelineWindows=").append(windows[0]).append('\n');
            assertEquals("Controller sees current song", before, seen[0]);
            assertTrue("Controller can skip", canNext[0]);
            getInstrumentation().runOnMainSync(controller::seekToNext);
            long end = SystemClock.elapsedRealtime() + 5000; boolean changed = false;
            while (!changed && SystemClock.elapsedRealtime() < end) { Thread.sleep(100); changed = !before.equals(currentId()); }
            report.append("controller next ").append(changed ? "changed song\n" : "DID NOT change song\n");
            assertTrue("Controller next changes song", changed);
            Thread.sleep(1000);
            String[] after = {""};
            getInstrumentation().runOnMainSync(() -> after[0] = controller.getCurrentMediaItem() == null ? "" : controller.getCurrentMediaItem().mediaId);
            assertEquals("Controller follows song", currentId(), after[0]);
        } finally { getInstrumentation().runOnMainSync(controller::release); }
    }

    /** One call tree per dispatch kind, outermost frame first, starting below Looper.loop. */
    private void tree(List<Dispatch> dispatches, List<Sample> samples) {
        Map<String, Node> roots = new LinkedHashMap<>();
        synchronized (samples) {
            for (Sample s : samples) {
                for (Dispatch d : dispatches) {
                    if (s.at < d.start || s.at > d.end) continue;
                    Node node = roots.computeIfAbsent(d.name, k -> new Node()); node.count++;
                    boolean inside = false;
                    for (int i = s.stack.length - 1; i >= 0; i--) {
                        StackTraceElement f = s.stack[i];
                        if (!inside) { inside = f.getMethodName().equals("dispatchMessage"); continue; }
                        String key = f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber();
                        node = node.children.computeIfAbsent(key, k -> new Node()); node.count++;
                    }
                    break;
                }
            }
        }
        for (Map.Entry<String, Node> root : roots.entrySet()) {
            report.append("\n--- ").append(root.getKey()).append("  samples=").append(root.getValue().count).append('\n');
            print(root.getValue(), root.getValue().count, 0);
        }
    }
    private void print(Node node, int total, int depth) {
        List<Map.Entry<String, Node>> entries = new ArrayList<>(node.children.entrySet());
        Collections.sort(entries, (a, b) -> b.getValue().count - a.getValue().count);
        for (Map.Entry<String, Node> entry : entries) {
            int percent = entry.getValue().count * 100 / total;
            if (percent < TREE_MIN_PERCENT) continue;
            // Collapse single-child chains that carry the same weight to keep the tree readable.
            boolean sameAsParent = entries.size() == 1 && entry.getValue().count == node.count;
            if (!sameAsParent || entry.getKey().startsWith("cn.yuusei") || entry.getValue().children.size() != 1) {
                for (int i = 0; i < Math.min(depth, 24); i++) report.append(' ');
                report.append(percent).append("% ").append(entry.getKey()).append('\n');
                print(entry.getValue(), total, depth + 1);
            } else print(entry.getValue(), total, depth);
        }
    }

    public void testTrackChangeStalls() throws Exception {
        assertEquals("Physical M3X", "Shanling M3X", android.os.Build.MODEL.replace('_', ' '));
        String label = ((android.test.InstrumentationTestRunner) getInstrumentation()).getArguments().getString("label", "probe");
        assertTrue(label.matches("[a-z0-9-]+"));
        String mode = ((android.test.InstrumentationTestRunner) getInstrumentation()).getArguments().getString("mode", "full");
        PlayerApp app = PlayerApp.instance; app.awaitLibrary();
        activity = (MainActivity) getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        getInstrumentation().runOnMainSync(() -> activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON | android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD));
        waitJs("!!window.M3X_UI&&!!nativeCommand('poll').stats&&M3X_UI.tracks.length>0");
        JSONObject savedUi = new JSONObject(app.settings("ui").toString()), savedTools = new JSONObject(app.settings("tools").toString()), savedAudio = new JSONObject(app.settings("audio").toString());
        JSONObject scan = new JSONObject(app.library.scanStatus().toString()); int tracks = app.library.all().size();
        JSONObject original = new JSONObject(js("nativeCommand('poll')"));
        File directory = new File(app.getExternalFilesDir(null), "frames-" + label); assertTrue(directory.isDirectory() || directory.mkdirs());
        Map<String, Object> savedFields = new LinkedHashMap<>();
        for (String name : new String[]{"manualGain", "rampStart", "pauseAfterRamp", "rampFrom", "rampTarget", "fadeTrackStart", "listenedMs", "counted", "previousAlbum", "statsPlaying"}) savedFields.put(name, field(name).get(app.playback));
        JSONObject savedStats = new JSONObject(original.getJSONObject("stats").toString());
        long[] position = {0}; float[] volume = {1};
        getInstrumentation().runOnMainSync(() -> { position[0] = app.playback.player.getCurrentPosition(); volume[0] = app.playback.player.getVolume(); });
        report.append("label ").append(label).append(" tracks ").append(tracks).append('\n');
        try {
            getInstrumentation().runOnMainSync(() -> { try { app.playback.player.pause(); field("manualGain").setFloat(app.playback, 0); field("rampStart").setLong(app.playback, 0); app.playback.player.setVolume(0); } catch (Exception e) { throw new RuntimeException(e); } });
            act("document.querySelector('.m3x-nav [data-page=player]').click()");
            Thread.sleep(1000);
            pass("durations", true, 6, false);
            pass("durations", false, 4, false);
            if (mode.equals("full")) pass("stacks", true, 8, true);
            getInstrumentation().runOnMainSync(() -> app.playback.player.setPlayWhenReady(true));
            Thread.sleep(1500);
            sessionState();
            mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT, "next");
            Thread.sleep(1200);
            mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS, "previous");
            Thread.sleep(1200);
            sessionController(app);
            sessionState();
            JSONObject live = new JSONObject(js("nativeCommand('poll')"));
            report.append("output rate=").append(live.optInt("outputRate")).append(" encoding=").append(live.optInt("outputEncoding")).append(" decoder=").append(live.optString("decoder")).append(" route=").append(live.optString("route")).append('\n');
            assertTrue("Audio output is open", live.optInt("outputRate") > 0);
            // The route text must recover by polling alone, now that no device callback exists.
            String expectedRoute = live.optString("route");
            getInstrumentation().runOnMainSync(() -> { try { field("route").set(app.playback, "probe"); } catch (Exception e) { throw new RuntimeException(e); } });
            long routeEnd = SystemClock.elapsedRealtime() + 8000; boolean recovered = false;
            while (!recovered && SystemClock.elapsedRealtime() < routeEnd) { Thread.sleep(300); recovered = expectedRoute.equals(new JSONObject(js("nativeCommand('poll')")).optString("route")); }
            report.append("route refreshed by polling ").append(recovered).append('\n');
            assertTrue("Route refreshes by polling", recovered);
            assertEquals("Catalog unchanged", tracks, app.library.all().size());
            assertEquals("No scan", scan.toString(), app.library.scanStatus().toString());
        } finally {
            Looper.getMainLooper().setMessageLogging(null);
            try { act("window.nativeCommand=function(){return {}}"); getInstrumentation().runOnMainSync(() -> { activity.web.loadUrl("about:blank"); activity.finish(); }); getInstrumentation().waitForIdleSync(); }
            finally {
                getInstrumentation().runOnMainSync(() -> { try {
                    app.playback.player.pause(); app.playback.load(original.getString("id"), false); app.playback.player.seekTo(position[0]);
                    for (Map.Entry<String, Object> entry : savedFields.entrySet()) field(entry.getKey()).set(app.playback, entry.getValue());
                    field("stats").set(app.playback, new JSONObject(savedStats.toString())); field("lastStats").setLong(app.playback, SystemClock.elapsedRealtime()); field("lastSave").setLong(app.playback, SystemClock.elapsedRealtime());
                    // The restoring load schedules a deferred history write; it must not outlive the restore.
                    field("historyPending").setBoolean(app.playback, false);
                    app.main.removeCallbacks((Runnable) field("deferredSave").get(app.playback));
                    app.playback.player.setVolume(volume[0]); app.save("ui", savedUi); app.save("tools", savedTools); app.playback.player.setPlayWhenReady(!original.getBoolean("paused"));
                } catch (Exception e) { throw new RuntimeException(e); } });
                Thread.sleep(1200);
                getInstrumentation().runOnMainSync(() -> { try {
                    field("historyPending").setBoolean(app.playback, false);
                    app.main.removeCallbacks((Runnable) field("deferredSave").get(app.playback));
                    app.save("ui", savedUi);
                } catch (Exception e) { throw new RuntimeException(e); } });
                report.append("\nuiRestored ").append(savedUi.toString().equals(app.settings("ui").toString()))
                    .append(" toolsRestored ").append(savedTools.toString().equals(app.settings("tools").toString()))
                    .append(" audioPreserved ").append(savedAudio.toString().equals(app.settings("audio").toString())).append('\n');
                try (FileOutputStream out = new FileOutputStream(new File(directory, "stalls.txt"))) { out.write(report.toString().getBytes("UTF-8")); }
            }
        }
    }
}
