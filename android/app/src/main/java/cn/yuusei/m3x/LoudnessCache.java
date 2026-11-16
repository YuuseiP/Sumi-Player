package cn.yuusei.m3x;

import android.net.Uri;
import android.os.Process;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Per-song loudness measurements, analysed on one low-priority thread and kept in
 * files/loudness.json. The cache is derived data: deleting it only means songs are measured again.
 */
final class LoudnessCache {
    interface Listener { void measured(String id); }

    private final PlayerApp app;
    private final File file;
    private final Listener listener;
    private final Map<String, double[]> values = new HashMap<>();
    private final Set<String> pending = new HashSet<>();
    /** Failed ids and when; network failures are retried after a while. */
    private final Map<String, Long> failed = new HashMap<>();
    private static final long RETRY_MS = 10 * 60000;
    private volatile Set<String> wanted = Collections.emptySet();
    private double median = Double.NaN;
    private boolean medianDirty = true;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(() -> { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run(); }, "M3X-Loudness");
        thread.setDaemon(true); return thread;
    });

    LoudnessCache(PlayerApp app, Listener listener) {
        this.app = app; this.listener = listener; file = new File(app.getFilesDir(), "loudness.json");
        worker.execute(this::load);
    }

    synchronized double[] get(String id) { return values.get(id); }
    synchronized boolean pending(String id) { return pending.contains(id); }
    synchronized boolean failed(String id) {
        Long when = failed.get(id);
        return when != null && SystemClock.elapsedRealtime() - when < RETRY_MS;
    }

    /** Median measured loudness, used as a provisional value until the current song has been analysed. */
    synchronized double provisional() {
        if (medianDirty) {
            double[] all = new double[values.size()]; int i = 0;
            for (double[] value : values.values()) all[i++] = value[0];
            Arrays.sort(all);
            median = all.length < 3 ? Double.NaN : all.length % 2 == 1 ? all[all.length / 2] : (all[all.length / 2 - 1] + all[all.length / 2]) / 2;
            medianDirty = false;
        }
        return median;
    }

    /** Ids still worth analysing; stale queued requests (skipped songs) are dropped. */
    void want(Collection<String> ids) { wanted = new HashSet<>(ids); }

    /** Queues one analysis. Must be called on the main thread. */
    void request(String id, Uri uri, long startMs, long endMs, long durationMs) {
        synchronized (this) {
            if (values.containsKey(id) || pending.contains(id) || failed(id)) return;
            pending.add(id);
        }
        worker.execute(() -> {
            synchronized (this) {
                // The cache file may have supplied this song after the request was queued.
                if (values.containsKey(id) || !wanted.contains(id)) { pending.remove(id); return; }
            }
            long started = SystemClock.elapsedRealtime();
            try {
                double[] result = LoudnessAnalyzer.measure(app, uri, startMs, endMs, durationMs);
                android.util.Log.i("M3X.Loudness", id + " " + String.format(Locale.ROOT, "%.1f LUFS peak %.3f", result[0], result[1])
                    + " in " + (SystemClock.elapsedRealtime() - started) + " ms");
                synchronized (this) { values.put(id, result); pending.remove(id); medianDirty = true; }
                save();
            } catch (Throwable error) { // A failed measurement must never take playback down with it.
                android.util.Log.w("M3X.Loudness", "analysis failed for " + id + ": " + error);
                synchronized (this) { failed.put(id, SystemClock.elapsedRealtime()); pending.remove(id); }
            }
            app.main.post(() -> listener.measured(id));
        });
    }

    private void load() {
        if (!file.isFile()) return;
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[65536];
            for (int n; (n = input.read(buffer)) > 0; ) bytes.write(buffer, 0, n);
            JSONObject root = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            if (root.optInt("version") != LoudnessAnalyzer.VERSION) return;
            JSONObject tracks = root.getJSONObject("tracks");
            Map<String, double[]> loaded = new HashMap<>();
            for (Iterator<String> keys = tracks.keys(); keys.hasNext(); ) {
                String id = keys.next(); JSONArray value = tracks.optJSONArray(id);
                if (value != null && value.length() == 2 && Double.isFinite(value.optDouble(0)) && Double.isFinite(value.optDouble(1)))
                    loaded.put(id, new double[]{value.optDouble(0), value.optDouble(1)});
            }
            synchronized (this) { for (Map.Entry<String, double[]> entry : loaded.entrySet()) values.putIfAbsent(entry.getKey(), entry.getValue()); medianDirty = true; }
            app.main.post(() -> listener.measured(""));
        } catch (Exception error) { android.util.Log.w("M3X.Loudness", "cache ignored: " + error); }
    }

    private void save() {
        try {
            JSONObject tracks = new JSONObject();
            synchronized (this) {
                for (Map.Entry<String, double[]> entry : values.entrySet())
                    tracks.put(entry.getKey(), new JSONArray().put(Math.round(entry.getValue()[0] * 100) / 100d).put(Math.round(entry.getValue()[1] * 10000) / 10000d));
            }
            byte[] data = new JSONObject().put("version", LoudnessAnalyzer.VERSION).put("tracks", tracks).toString().getBytes(StandardCharsets.UTF_8);
            File temporary = new File(file.getPath() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(data); output.getFD().sync(); }
            if (!temporary.renameTo(file)) throw new IOException("无法保存响度缓存");
        } catch (Exception error) { android.util.Log.w("M3X.Loudness", "cache not saved: " + error); }
    }

    void shutdown() { worker.shutdownNow(); }
}
