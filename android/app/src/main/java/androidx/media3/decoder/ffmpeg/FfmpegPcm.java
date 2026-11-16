package androidx.media3.decoder.ffmpeg;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Lets M3X's loudness analyser decode codecs the Android 7.1 platform lacks (ALAC and others the bundled
 * FFmpeg build supports) without a playback renderer. {@link FfmpegAudioDecoder} is package-private.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class FfmpegPcm implements AutoCloseable {
    private final FfmpegAudioDecoder decoder;

    public static boolean supports(String mimeType) {
        return FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(mimeType);
    }

    public FfmpegPcm(String mimeType, int channels, int sampleRate, List<byte[]> initializationData, int maxInputSize) throws FfmpegDecoderException {
        Format format = new Format.Builder().setSampleMimeType(mimeType).setChannelCount(channels).setSampleRate(sampleRate)
            .setInitializationData(initializationData).setMaxInputSize(maxInputSize).build();
        decoder = new FfmpegAudioDecoder(format, 8, 8, Math.max(maxInputSize, 64 * 1024), /* outputFloat= */ true);
    }

    /** @return an empty input buffer to fill, or null when all are queued. */
    public DecoderInputBuffer input() throws FfmpegDecoderException { return decoder.dequeueInputBuffer(); }
    public void queue(DecoderInputBuffer buffer) throws FfmpegDecoderException { decoder.queueInputBuffer(buffer); }
    /** @return decoded float PCM, or null when none is ready yet. Callers must {@link SimpleDecoderOutputBuffer#release()} it. */
    public SimpleDecoderOutputBuffer output() throws FfmpegDecoderException { return decoder.dequeueOutputBuffer(); }
    public void flush() { decoder.flush(); }
    public int channels() { return decoder.getChannelCount(); }
    public int sampleRate() { return decoder.getSampleRate(); }
    public static ByteBuffer prepare(DecoderInputBuffer buffer, int size) {
        buffer.ensureSpaceForWrite(size);
        return buffer.data;
    }
    public static void endOfStream(DecoderInputBuffer buffer) { buffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM); }
    @Override public void close() { decoder.release(); }
}
