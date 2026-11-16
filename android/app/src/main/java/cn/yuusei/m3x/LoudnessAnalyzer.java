package cn.yuusei.m3x;

import android.content.Context;
import android.media.*;
import android.net.Uri;
import android.os.SystemClock;
import androidx.media3.common.C;
import androidx.media3.datasource.*;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.decoder.flac.FlacDecoderJni;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.FlacStreamMetadata;
import androidx.media3.extractor.SeekMap;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * Measures a song's loudness. Long songs are sampled in evenly spaced excerpts so one measurement
 * stays around a second on the M3X; online songs download only those excerpts' byte ranges.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class LoudnessAnalyzer {
    static final int VERSION = 3;
    private static final long WINDOW_MS = 4000, FULL_LIMIT_MS = 45000, TIMEOUT_MS = 45000;
    private static final int WINDOWS = 8;
    /** Online songs: fewer, shorter excerpts to keep the extra download small. */
    private static final long REMOTE_WINDOW_MS = 3000, REMOTE_FULL_LIMIT_MS = 20000;
    private static final int REMOTE_WINDOWS = 6;

    /** @return {integrated LUFS, sample peak} */
    static double[] measure(Context context, Uri uri, long startMs, long endMs, long durationMs) throws Exception {
        if (startMs < 0 || (endMs > 0 && endMs <= startMs)) throw new IllegalArgumentException("片段起止时间不正确");
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        String scheme = uri.getScheme();
        if (NeteaseClient.SCHEME.equals(scheme) || "https".equals(scheme) || "http".equals(scheme)) {
            boolean netease = NeteaseClient.SCHEME.equals(scheme);
            Uri url = netease ? Uri.parse(NeteaseClient.get().resolve(uri.getLastPathSegment())) : uri;
            Map<String, String> headers = netease ? Collections.<String, String>emptyMap() : NativeNetwork.headers(url.toString());
            byte[] head = new byte[4]; long size = fetch(url, headers, 0, head.length, new ByteArrayOutputStream(), head);
            if (magic(head) == 0x664c6143) return result(flac(context, url, headers, startMs, endMs, REMOTE_WINDOWS, REMOTE_WINDOW_MS, REMOTE_FULL_LIMIT_MS, deadline));
            boolean mpeg = (head[0] == 'I' && head[1] == 'D' && head[2] == '3') || ((head[0] & 255) == 0xff && (head[1] & 0xe0) == 0xe0);
            if (!mpeg) throw new IOException("此在线格式暂不支持响度分析");
            return result(remoteStream(context, url, headers, size, startMs, endMs > 0 ? endMs : durationMs, deadline));
        }
        int magic = 0;
        try (InputStream input = open(context, uri)) { magic = new DataInputStream(input).readInt(); } catch (IOException ignored) {}
        // libFLAC handles every bit depth and seeks without reading the whole file; Android 7.1 has no AIFF extractor.
        if (magic == 0x664c6143) return result(flac(context, uri, Collections.<String, String>emptyMap(), startMs, endMs, WINDOWS, WINDOW_MS, FULL_LIMIT_MS, deadline));
        if (magic == 0x464f524d) return result(pcm(context, uri, true, startMs, endMs, deadline));
        if (magic == 0x52494646) { // RIFF: read WAV samples directly; fall back to the platform for compressed WAV.
            try { return result(pcm(context, uri, false, startMs, endMs, deadline)); }
            catch (IOException unsupported) { if (unsupported instanceof InterruptedIOException) throw unsupported; }
        }
        return result(platform(extractor -> extractor.setDataSource(context, uri, null), startMs, endMs, WINDOWS, WINDOW_MS, FULL_LIMIT_MS, null,
            () -> { try (InputStream input = open(context, uri)) { return alacCookie(input); } }, deadline));
    }

    static List<long[]> windows(long fromMs, long toMs) { return windows(fromMs, toMs, WINDOWS, WINDOW_MS, FULL_LIMIT_MS); }

    static List<long[]> windows(long fromMs, long toMs, int count, long windowMs, long fullLimitMs) {
        List<long[]> result = new ArrayList<>();
        long span = toMs - fromMs;
        if (toMs <= 0 || span <= fullLimitMs) { result.add(new long[]{fromMs, toMs > 0 ? toMs : Long.MAX_VALUE / 2000}); return result; }
        for (int i = 0; i < count; i++) {
            long start = fromMs + (span - windowMs) * (2L * i + 1) / (2L * count);
            result.add(new long[]{start, start + windowMs});
        }
        return result;
    }

    private static int magic(byte[] head) { return (head[0] & 255) << 24 | (head[1] & 255) << 16 | (head[2] & 255) << 8 | (head[3] & 255); }

    private static double[] result(LoudnessMeter meter) throws IOException {
        if (meter == null || meter.blocks() == 0) throw new IOException("音频太短，无法分析响度");
        double lufs = meter.integrated();
        return new double[]{Double.isNaN(lufs) ? -70 : lufs, meter.peak()};
    }

    private static void check(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("响度分析已取消");
        if (SystemClock.elapsedRealtime() > deadline) throw new IOException("响度分析超时");
    }

    private interface Source { void attach(MediaExtractor extractor) throws IOException; }

    private static LoudnessMeter platform(Source input, long startMs, long endMs, int count, long windowMs, long fullLimitMs,
                                          LoudnessMeter meter, long deadline) throws Exception {
        return platform(input, startMs, endMs, count, windowMs, fullLimitMs, meter, null, deadline);
    }

    private static LoudnessMeter platform(Source input, long startMs, long endMs, int count, long windowMs, long fullLimitMs,
                                          LoudnessMeter meter, java.util.concurrent.Callable<byte[]> alac, long deadline) throws Exception {
        MediaExtractor extractor = new MediaExtractor(); MediaCodec decoder = null;
        try {
            input.attach(extractor);
            MediaFormat format = null; int track = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { format = extractor.getTrackFormat(i); track = i; break; }
            }
            if (format == null) throw new IOException("没有可解码的音轨");
            extractor.selectTrack(track);
            long total = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) / 1000 : 0;
            List<long[]> windows = windows(startMs, endMs > 0 ? endMs : total, count, windowMs, fullLimitMs);
            String mime = format.getString(MediaFormat.KEY_MIME);
            try {
                decoder = MediaCodec.createDecoderByType(mime);
                decoder.configure(format, null, null, 0); decoder.start();
            } catch (Exception unsupported) {
                if (decoder != null) { decoder.release(); decoder = null; }
                if (!androidx.media3.decoder.ffmpeg.FfmpegPcm.supports(mime)) throw unsupported;
                return ffmpeg(extractor, format, windows, meter, alac, deadline);
            }
            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT), encoding = 2;
            double[] frame = null;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean first = true;
            for (long[] window : windows) {
                long fromUs = window[0] * 1000, toUs = window[1] * 1000;
                extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                if (!first) decoder.flush();
                first = false;
                if (meter != null) meter.gap();
                boolean inputEnd = false, done = false;
                while (!done) {
                    check(deadline);
                    if (!inputEnd) {
                        int index = decoder.dequeueInputBuffer(5000);
                        if (index >= 0) {
                            int size = extractor.readSampleData(decoder.getInputBuffer(index), 0); long time = extractor.getSampleTime();
                            if (size < 0 || time > toUs) { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnd = true; }
                            else { decoder.queueInputBuffer(index, 0, size, time, 0); extractor.advance(); }
                        }
                    }
                    int index = decoder.dequeueOutputBuffer(info, 5000);
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat decoded = decoder.getOutputFormat();
                        rate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = decoded.getInteger(MediaFormat.KEY_PCM_ENCODING);
                        if (encoding != 2 && encoding != 4) throw new IOException("响度分析仅支持 PCM16 / PCM Float 解码输出");
                    } else if (index >= 0) {
                        if (meter == null) meter = new LoudnessMeter(rate, channels);
                        if (frame == null || frame.length != channels) frame = new double[channels];
                        ByteBuffer decoded = decoder.getOutputBuffer(index).order(ByteOrder.LITTLE_ENDIAN);
                        int frameSize = channels * (encoding == 4 ? 4 : 2), frames = info.size / frameSize;
                        long from = Math.max(0, (long) Math.ceil((fromUs - info.presentationTimeUs) * (double) rate / 1e6));
                        long to = (long) Math.min(Integer.MAX_VALUE, Math.ceil((toUs - info.presentationTimeUs) * (double) rate / 1e6));
                        int a = (int) Math.min(frames, from), b = (int) Math.max(a, Math.min(frames, to));
                        decoded.position(info.offset + a * frameSize);
                        for (int f = a; f < b; f++) {
                            for (int c = 0; c < channels; c++) frame[c] = encoding == 4 ? decoded.getFloat() : decoded.getShort() / 32768d;
                            meter.frame(frame);
                        }
                        done = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || info.presentationTimeUs + frames * 1e6 / rate >= toUs;
                        decoder.releaseOutputBuffer(index, false);
                    }
                }
            }
            return meter;
        } finally {
            try { if (decoder != null) { try { decoder.stop(); } catch (IllegalStateException ignored) {} decoder.release(); } }
            finally { extractor.release(); }
        }
    }

    /** ALACSpecificConfig: frameLength(4) version(1) bitDepth(1) pb mb kb(3) channels(1) maxRun(2) maxFrameBytes(4) avgBitRate(4) sampleRate(4). */
    static boolean alacConfig(byte[] data, int offset) {
        if (data == null || offset < 0 || data.length < offset + 24) return false;
        int depth = data[offset + 5] & 255, channels = data[offset + 9] & 255;
        return (depth == 16 || depth == 20 || depth == 24 || depth == 32) && channels >= 1 && channels <= 8;
    }

    /** Finds the 'alac' magic cookie inside the moov box of an MP4 / M4A stream, or null. */
    static byte[] alacCookie(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(stream, 65536));
        for (int boxes = 0; boxes < 64; boxes++) {
            long size; int type;
            try { size = Integer.toUnsignedLong(in.readInt()); type = in.readInt(); } catch (EOFException end) { return null; }
            long header = 8;
            if (size == 1) { size = in.readLong(); header = 16; }
            if (size != 0 && size < header) return null;
            if (type != 0x6d6f6f76) { if (size == 0) return null; skip(in, size - header); continue; } // not 'moov'
            if (size == 0 || size - header > 32 * 1024 * 1024) return null;
            byte[] moov = new byte[(int) (size - header)]; in.readFully(moov);
            // Child atom: size 36, 'alac', version/flags 0, then the 24-byte config.
            for (int i = 0; i + 36 <= moov.length; i++) {
                if (moov[i] == 0 && moov[i + 1] == 0 && moov[i + 2] == 0 && moov[i + 3] == 36
                        && moov[i + 4] == 'a' && moov[i + 5] == 'l' && moov[i + 6] == 'a' && moov[i + 7] == 'c' && alacConfig(moov, i + 12))
                    return Arrays.copyOfRange(moov, i + 12, i + 36);
            }
            return null;
        }
        return null;
    }

    private static LoudnessMeter ffmpeg(MediaExtractor extractor, MediaFormat format, List<long[]> windows, LoudnessMeter meter,
                                        java.util.concurrent.Callable<byte[]> alac, long deadline) throws Exception {
        String mime = format.getString(MediaFormat.KEY_MIME);
        List<byte[]> init = new ArrayList<>();
        for (int i = 0; format.containsKey("csd-" + i); i++) {
            ByteBuffer csd = format.getByteBuffer("csd-" + i).duplicate(); byte[] bytes = new byte[csd.remaining()]; csd.get(bytes); init.add(bytes);
        }
        if ("audio/alac".equals(mime)) {
            // FFmpeg wants the 24-byte ALACSpecificConfig. Prefer the file's own 'alac' atom; Android 7.1's csd-0 layout varies.
            byte[] cookie = alac == null ? null : alac.call();
            for (int i = 0; cookie == null && !init.isEmpty() && i < 3; i++) {
                int offset = new int[]{0, 4, 12}[i];
                if (alacConfig(init.get(0), offset)) cookie = Arrays.copyOfRange(init.get(0), offset, offset + 24);
            }
            if (cookie == null) throw new IOException("ALAC 缺少解码参数");
            init.clear(); init.add(cookie);
        }
        int maxInput = Math.max(256 * 1024, format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0);
        try (androidx.media3.decoder.ffmpeg.FfmpegPcm decoder = new androidx.media3.decoder.ffmpeg.FfmpegPcm(mime,
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT), format.getInteger(MediaFormat.KEY_SAMPLE_RATE), init, maxInput)) {
            double[] frame = null; boolean first = true;
            for (long[] window : windows) {
                long fromUs = window[0] * 1000, toUs = window[1] * 1000;
                extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                if (!first) decoder.flush();
                first = false;
                if (meter != null) meter.gap();
                boolean inputEnd = false, done = false;
                while (!done) {
                    check(deadline);
                    androidx.media3.decoder.DecoderInputBuffer input = inputEnd ? null : decoder.input();
                    if (input != null) {
                        // MediaExtractor.getSampleSize() is API 28; read into a buffer sized for the largest sample instead.
                        ByteBuffer data = androidx.media3.decoder.ffmpeg.FfmpegPcm.prepare(input, maxInput);
                        int read = extractor.readSampleData(data, 0); long time = extractor.getSampleTime();
                        if (read < 0 || time > toUs) { data.clear(); data.limit(0); androidx.media3.decoder.ffmpeg.FfmpegPcm.endOfStream(input); inputEnd = true; }
                        else { data.position(0); data.limit(read); input.timeUs = time; extractor.advance(); }
                        decoder.queue(input);
                    }
                    androidx.media3.decoder.SimpleDecoderOutputBuffer output = decoder.output();
                    if (output == null) { if (input == null) Thread.sleep(2); continue; }
                    try {
                        if (output.isEndOfStream()) { done = true; continue; }
                        int rate = decoder.sampleRate(), channels = decoder.channels();
                        if (meter == null) meter = new LoudnessMeter(rate, channels);
                        if (frame == null || frame.length != channels) frame = new double[channels];
                        ByteBuffer pcm = output.data.order(ByteOrder.LITTLE_ENDIAN);
                        int frames = pcm.remaining() / 4 / channels;
                        int a = (int) Math.min(frames, Math.max(0, (long) Math.ceil((fromUs - output.timeUs) * (double) rate / 1e6)));
                        int b = (int) Math.max(a, Math.min(frames, (long) Math.min(Integer.MAX_VALUE, Math.ceil((toUs - output.timeUs) * (double) rate / 1e6))));
                        pcm.position(pcm.position() + a * 4 * channels);
                        for (int f = a; f < b; f++) { for (int c = 0; c < channels; c++) frame[c] = pcm.getFloat(); meter.frame(frame); }
                        if (output.timeUs + frames * 1e6 / rate >= toUs) done = true;
                    } finally { output.release(); }
                }
            }
            return meter;
        }
    }

    private static DataSpec spec(Uri uri, Map<String, String> headers, long position, long length) {
        return new DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).setHttpRequestHeaders(headers).build();
    }

    /**
     * FLAC through the bundled libFLAC. Excerpts start at the seek table's point or, without one, at a
     * proportional byte offset; libFLAC resynchronises on the next frame either way.
     */
    private static LoudnessMeter flac(Context context, Uri uri, Map<String, String> headers, long startMs, long endMs,
                                      int count, long windowMs, long fullLimitMs, long deadline) throws Exception {
        DataSource source = new DefaultDataSource(context, new OkHttpDataSource.Factory(NativeNetwork.HTTP).createDataSource());
        FlacDecoderJni decoder = null;
        try {
            boolean remote = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
            // Online: metadata only needs the head of the file; never leave an open-ended download running.
            long length = source.open(spec(uri, headers, 0, remote ? 1024 * 1024 : C.LENGTH_UNSET));
            if (remote) {
                String range = "";
                for (Map.Entry<String, List<String>> header : source.getResponseHeaders().entrySet())
                    if ("content-range".equalsIgnoreCase(header.getKey()) && !header.getValue().isEmpty()) range = header.getValue().get(0);
                if (range.contains("/")) length = Long.parseLong(range.substring(range.lastIndexOf('/') + 1).trim());
            }
            decoder = new FlacDecoderJni();
            decoder.setData(new DefaultExtractorInput(source, 0, length));
            FlacStreamMetadata metadata = decoder.decodeStreamMetadata();
            int channels = metadata.channels, rate = metadata.sampleRate, bytes = metadata.bitsPerSample / 8;
            if (bytes < 1 || bytes > 4 || metadata.bitsPerSample % 8 != 0) throw new IOException("不支持的 FLAC 位深");
            double scale = Math.pow(2, metadata.bitsPerSample - 1);
            long firstFrame = decoder.getDecodePosition();
            long totalMs = metadata.totalSamples > 0 ? metadata.totalSamples * 1000 / rate : 0;
            List<long[]> windows = windows(startMs, endMs > 0 ? endMs : totalMs, count, windowMs, fullLimitMs);
            LoudnessMeter meter = new LoudnessMeter(rate, channels); double[] frame = new double[channels];
            ByteBuffer buffer = ByteBuffer.allocateDirect(metadata.getMaxDecodedFrameSize()).order(ByteOrder.LITTLE_ENDIAN);
            boolean sequential = windows.size() == 1 && startMs == 0 && (!remote || length <= 1024 * 1024);
            double bytesPerMs = length != C.LENGTH_UNSET && totalMs > 0 ? (length - firstFrame) / (double) totalMs : 0;
            byte[] scratch = new byte[0];
            for (long[] window : windows) {
                long fromUs = window[0] * 1000, toUs = window[1] * 1000;
                boolean exact = true;
                if (!sequential) {
                    SeekMap.SeekPoints points = decoder.getSeekPoints(fromUs);
                    long position, lead = 0;
                    if (points != null) { position = points.first.position; lead = Math.max(0, fromUs - points.first.timeUs) / 1000; }
                    else if (bytesPerMs > 0) { position = firstFrame + (long) (bytesPerMs * window[0]); exact = false; }
                    else throw new IOException("FLAC 缺少长度信息");
                    source.close();
                    long span = remote && bytesPerMs > 0 ? (long) (bytesPerMs * ((toUs - fromUs) / 1000 + lead) * 1.5) + 128 * 1024 : C.LENGTH_UNSET;
                    if (span != C.LENGTH_UNSET && length != C.LENGTH_UNSET) span = Math.min(span, length - position);
                    source.open(spec(uri, headers, position, span));
                    decoder.reset(position);
                    decoder.setData(new DefaultExtractorInput(source, position, length));
                    meter.gap();
                }
                long wanted = (long) Math.min(Long.MAX_VALUE / 2, (toUs - fromUs) * (double) rate / 1e6), fed = 0;
                while (fed < wanted) {
                    check(deadline);
                    try { decoder.decodeSample(buffer); }
                    catch (FlacDecoderJni.FlacFrameDecodeException damaged) { if (sequential) throw damaged; break; }
                    if (!buffer.hasRemaining()) break;
                    long timeUs = decoder.getLastFrameTimestamp();
                    int frames = buffer.remaining() / bytes / channels;
                    if (frames == 0) continue;
                    // Seek-table and sequential reads honour exact times; proportional offsets just take the next excerpt.
                    int a = exact ? (int) Math.min(frames, Math.max(0, (long) Math.ceil((fromUs - timeUs) * (double) rate / 1e6))) : 0;
                    int b = (int) Math.max(a, Math.min(frames, a + wanted - fed));
                    if (exact) b = (int) Math.max(a, Math.min(b, (long) Math.min(Integer.MAX_VALUE, Math.ceil((toUs - timeUs) * (double) rate / 1e6))));
                    int copy = (b - a) * channels * bytes;
                    if (scratch.length < copy) scratch = new byte[copy];
                    buffer.position(buffer.position() + a * channels * bytes); buffer.get(scratch, 0, copy);
                    int offset = 0, shift = 32 - 8 * bytes;
                    for (int f = a; f < b; f++) {
                        for (int c = 0; c < channels; c++) {
                            int value = 0;
                            for (int i = 0; i < bytes; i++) value |= (scratch[offset++] & 255) << (8 * i);
                            frame[c] = (value << shift >> shift) / scale;
                        }
                        meter.frame(frame);
                    }
                    fed += b - a;
                    if (exact && timeUs + frames * 1e6 / rate >= toUs) break;
                }
            }
            return meter;
        } finally {
            try { if (decoder != null) decoder.release(); } finally { source.close(); }
        }
    }

    /** MP3 / ADTS streams resynchronise on any frame, so each excerpt's byte range is decoded as its own small file. */
    private static LoudnessMeter remoteStream(Context context, Uri url, Map<String, String> headers, long size, long startMs, long endMs, long deadline) throws Exception {
        if (size <= 0 || endMs <= 0) throw new IOException("在线音源缺少时长信息");
        File directory = new File(context.getCacheDir(), "loudness"); directory.mkdirs();
        File excerpt = new File(directory, "excerpt.tmp");
        LoudnessMeter meter = null;
        try {
            for (long[] window : windows(startMs, endMs, REMOTE_WINDOWS, REMOTE_WINDOW_MS, REMOTE_FULL_LIMIT_MS)) {
                check(deadline);
                long from = size * window[0] / endMs, to = Math.min(size, window[1] >= Long.MAX_VALUE / 2000 ? size : size * window[1] / endMs + 8192);
                try (FileOutputStream output = new FileOutputStream(excerpt)) { fetch(url, headers, from, to - from, output, null); }
                if (meter != null) meter.gap();
                meter = platform(extractor -> extractor.setDataSource(excerpt.getPath()), 0, 0, 1, 0, Long.MAX_VALUE, meter, deadline);
            }
            return meter;
        } finally { excerpt.delete(); }
    }

    /** Range GET through OkHttp (the app's TLS stack). Returns the total resource size. */
    private static long fetch(Uri url, Map<String, String> headers, long from, long length, OutputStream output, byte[] head) throws IOException {
        okhttp3.Request.Builder request = new okhttp3.Request.Builder().url(url.toString())
            .header("Range", "bytes=" + from + "-" + (from + length - 1)).header("User-Agent", "M3X-Player/0.2");
        for (Map.Entry<String, String> header : headers.entrySet()) request.header(header.getKey(), header.getValue());
        try (okhttp3.Response response = NativeNetwork.HTTP.newCall(request.build()).execute()) {
            if (response.code() != 206 || response.body() == null) throw new IOException("在线音源不支持分段读取：HTTP " + response.code());
            String range = response.header("Content-Range", "");
            long total = Long.parseLong(range.substring(range.lastIndexOf('/') + 1).trim());
            InputStream input = response.body().byteStream(); byte[] buffer = new byte[65536]; long copied = 0;
            for (int n; copied < length && (n = input.read(buffer, 0, (int) Math.min(buffer.length, length - copied))) > 0; copied += n) {
                if (head != null && copied < head.length) System.arraycopy(buffer, 0, head, (int) copied, (int) Math.min(n, head.length - copied));
                output.write(buffer, 0, n);
            }
            if (head != null && copied < head.length) throw new IOException("在线音源内容过短");
            return total;
        } catch (NumberFormatException | StringIndexOutOfBoundsException error) { throw new IOException("在线音源长度无效"); }
    }

    /** Uncompressed PCM layout; the stream is positioned at the first sample once parsed. */
    static final class Pcm {
        int rate, channels, bytes; long frames; boolean littleEndian, floating, unsigned;
        int frameSize() { return channels * bytes; }

        double sample(byte[] buffer, int offset) {
            long value = 0;
            for (int i = 0; i < bytes; i++) value |= (long) (buffer[offset + (littleEndian ? i : bytes - 1 - i)] & 255) << (8 * i);
            if (floating) return bytes == 8 ? Double.longBitsToDouble(value) : Float.intBitsToFloat((int) value);
            if (unsigned) return (value - 128) / 128d;
            int shift = 64 - 8 * bytes;
            return (value << shift >> shift) / Math.pow(2, 8 * bytes - 1);
        }
    }

    static Pcm aiffLayout(InputStream stream) throws IOException {
        AiffHeader header = AiffHeader.read(stream);
        Pcm pcm = new Pcm();
        pcm.rate = header.rate; pcm.channels = header.channels; pcm.bytes = header.bits / 8; pcm.frames = header.frames; pcm.littleEndian = header.littleEndian;
        return pcm;
    }

    /** RIFF WAVE: PCM integer (8-bit unsigned, 16/24/32-bit signed), IEEE float 32/64, and WAVE_FORMAT_EXTENSIBLE of either. */
    static Pcm wavLayout(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        byte[] head = new byte[12]; in.readFully(head);
        if (head[0] != 'R' || head[1] != 'I' || head[2] != 'F' || head[3] != 'F' || head[8] != 'W' || head[9] != 'A' || head[10] != 'V' || head[11] != 'E')
            throw new IOException("不是 RIFF WAVE 文件");
        Pcm pcm = new Pcm(); pcm.littleEndian = true; boolean format = false;
        for (int chunks = 0; chunks < 64; chunks++) {
            byte[] id = new byte[4]; in.readFully(id);
            long size = Integer.toUnsignedLong(Integer.reverseBytes(in.readInt()));
            String name = new String(id, java.nio.charset.StandardCharsets.US_ASCII);
            if (name.equals("fmt ")) {
                if (size < 16) throw new IOException("WAV fmt 块过短");
                byte[] fmt = new byte[(int) size]; in.readFully(fmt);
                ByteBuffer b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN);
                int tag = b.getShort(0) & 0xffff; pcm.channels = b.getShort(2) & 0xffff; pcm.rate = b.getInt(4);
                int bits = b.getShort(14) & 0xffff;
                if (tag == 0xfffe && size >= 26) tag = b.getShort(24) & 0xffff;
                if (tag != 1 && tag != 3) throw new IOException("不支持的 WAV 编码 " + tag);
                pcm.floating = tag == 3; pcm.bytes = (bits + 7) / 8; pcm.unsigned = !pcm.floating && pcm.bytes == 1;
                int block = b.getShort(12) & 0xffff;
                if (pcm.channels < 1 || pcm.channels > 32 || pcm.rate < 1 || block != pcm.frameSize()
                        || (pcm.floating ? pcm.bytes != 4 && pcm.bytes != 8 : pcm.bytes < 1 || pcm.bytes > 4))
                    throw new IOException("不支持的 WAV 格式");
                format = true;
            } else if (name.equals("data")) {
                if (!format) throw new IOException("WAV data 位于 fmt 之前");
                pcm.frames = size == 0xffffffffL ? Long.MAX_VALUE : size / pcm.frameSize();
                return pcm;
            } else skip(in, size);
            if ((size & 1) != 0) skip(in, 1);
        }
        throw new IOException("WAV 缺少音频数据");
    }

    private static LoudnessMeter pcm(Context context, Uri uri, boolean aiff, long startMs, long endMs, long deadline) throws Exception {
        try (InputStream stream = new BufferedInputStream(open(context, uri), 65536)) {
            Pcm layout = aiff ? aiffLayout(stream) : wavLayout(stream);
            int channels = layout.channels, frameSize = layout.frameSize();
            long totalMs = layout.frames == Long.MAX_VALUE ? 0 : layout.frames * 1000 / layout.rate;
            LoudnessMeter meter = new LoudnessMeter(layout.rate, channels); double[] frame = new double[channels];
            byte[] buffer = new byte[frameSize * 1024];
            DataInputStream data = new DataInputStream(stream);
            long position = 0;
            for (long[] window : windows(startMs, endMs > 0 ? (totalMs > 0 ? Math.min(endMs, totalMs) : endMs) : totalMs)) {
                long first = window[0] * layout.rate / 1000, last = Math.min(layout.frames, window[1] * layout.rate / 1000);
                if (first < position) continue;
                skip(data, (first - position) * frameSize); position = first;
                meter.gap();
                while (position < last) {
                    check(deadline);
                    int want = (int) Math.min(1024, last - position) * frameSize, read = 0;
                    while (read < want) { int n = data.read(buffer, read, want - read); if (n < 0) break; read += n; }
                    int frames = read / frameSize;
                    for (int f = 0; f < frames; f++) {
                        for (int c = 0; c < channels; c++) frame[c] = layout.sample(buffer, f * frameSize + c * layout.bytes);
                        meter.frame(frame);
                    }
                    position += frames;
                    if (read < want) return meter; // truncated file: measure what exists
                }
            }
            return meter;
        }
    }

    private static InputStream open(Context context, Uri uri) throws IOException {
        if ("file".equals(uri.getScheme())) return new FileInputStream(uri.getPath());
        InputStream stream = context.getContentResolver().openInputStream(uri);
        if (stream == null) throw new FileNotFoundException(uri.toString());
        return stream;
    }

    private static void skip(InputStream stream, long count) throws IOException {
        while (count > 0) {
            long skipped = stream.skip(count);
            if (skipped <= 0) { if (stream.read() < 0) throw new EOFException(); skipped = 1; }
            count -= skipped;
        }
    }
}
