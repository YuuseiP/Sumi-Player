package cn.yuusei.m3x;

import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class LoudnessMeterTest {
    private static void tone(LoudnessMeter meter, int rate, double amplitude, double seconds, double frequency, boolean bothChannels) {
        double[] frame = new double[2];
        for (int i = 0; i < rate * seconds; i++) {
            double value = amplitude * Math.sin(2 * Math.PI * frequency * i / rate);
            frame[0] = value; frame[1] = bothChannels ? value : 0; meter.frame(frame);
        }
    }

    @Test public void stereoSineMatchesBs1770Reference() {
        for (int rate : new int[]{44100, 48000, 96000, 192000}) {
            LoudnessMeter meter = new LoudnessMeter(rate, 2);
            tone(meter, rate, .1, 5, 1000, true);
            assertEquals("rate " + rate, -20, meter.integrated(), .1);
            assertEquals(.1, meter.peak(), 1e-3);
        }
        LoudnessMeter mono = new LoudnessMeter(48000, 2);
        tone(mono, 48000, 1, 5, 1000, false);
        assertEquals("0 dBFS 1 kHz in one channel is -3.01 LUFS", -3.01, mono.integrated(), .1);
    }

    @Test public void gatingIgnoresSilenceAndQuietPassages() {
        LoudnessMeter meter = new LoudnessMeter(44100, 2);
        tone(meter, 44100, .1, 5, 1000, true);
        tone(meter, 44100, 0, 20, 1000, true);
        tone(meter, 44100, .001, 20, 1000, true); // -60 LUFS: above the absolute gate, below the relative gate
        assertEquals("Only the three partial fade-out blocks remain", -20, meter.integrated(), .2);
        LoudnessMeter silent = new LoudnessMeter(44100, 2);
        tone(silent, 44100, 0, 2, 1000, true);
        assertTrue(Double.isNaN(silent.integrated()));
    }

    @Test public void excerptsDoNotFormBlocksAcrossGaps() {
        LoudnessMeter meter = new LoudnessMeter(48000, 2);
        tone(meter, 48000, .1, .35, 1000, true);
        meter.gap();
        tone(meter, 48000, .1, .35, 1000, true);
        assertEquals("Two 350 ms excerpts never complete a 400 ms block", 0, meter.blocks());
        tone(meter, 48000, .1, 1, 1000, true);
        assertEquals(-20, meter.integrated(), .15);
    }

    @Test public void attenuationOnlyLowersLoudSongs() {
        assertEquals(Math.pow(10, -8 / 20d), LoudnessMeter.attenuation(-8, -16), 1e-9);
        assertEquals(1, LoudnessMeter.attenuation(-22, -16), 0);
        assertEquals(1, LoudnessMeter.attenuation(Double.NaN, -16), 0);
        assertEquals(.01, LoudnessMeter.attenuation(40, -16), 1e-9);
    }

    @Test public void replayGainTagsUseRequestedModeWithTrackFallback() throws Exception {
        JSONObject tags = new JSONObject().put("replaygain_track_gain", "-7.25 dB").put("replaygain_album_gain", "+1.5 dB");
        assertEquals(-7.25, LoudnessMeter.tagGain(tags, "track"), 1e-9);
        assertEquals(1.5, LoudnessMeter.tagGain(tags, "album"), 1e-9);
        assertEquals(-7.25, LoudnessMeter.tagGain(tags, "off"), 1e-9);
        assertEquals(-3, LoudnessMeter.tagGain(new JSONObject().put("replaygain_track_gain", "-3"), "album"), 1e-9);
        assertTrue(Double.isNaN(LoudnessMeter.tagGain(new JSONObject(), "track")));
        assertTrue(Double.isNaN(LoudnessMeter.tagGain(new JSONObject().put("replaygain_track_gain", "loud"), "track")));
        assertTrue(Double.isNaN(LoudnessMeter.tagGain(null, "track")));
    }

    @Test public void alacCookieIsReadFromMoovEvenAfterMediaData() throws Exception {
        java.nio.ByteBuffer cookie = java.nio.ByteBuffer.allocate(24).putInt(4096).put((byte) 0).put((byte) 24).put((byte) 40).put((byte) 10).put((byte) 14)
            .put((byte) 2).putShort((short) 255).putInt(0).putInt(0).putInt(96000);
        java.io.ByteArrayOutputStream file = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(file);
        out.writeInt(16); out.writeBytes("ftyp"); out.writeBytes("M4A "); out.writeInt(0);
        out.writeInt(8 + 1000); out.writeBytes("mdat"); out.write(new byte[1000]);
        // moov > ... > stsd entry 'alac' (decoy without a valid config) > child 'alac' atom with the cookie
        out.writeInt(8 + 20 + 36); out.writeBytes("moov");
        out.writeInt(20); out.writeBytes("alac"); out.write(new byte[12]);
        out.writeInt(36); out.writeBytes("alac"); out.writeInt(0); out.write(cookie.array());
        byte[] found = LoudnessAnalyzer.alacCookie(new java.io.ByteArrayInputStream(file.toByteArray()));
        assertArrayEquals(cookie.array(), found);
        assertTrue(LoudnessAnalyzer.alacConfig(found, 0));
        assertFalse(LoudnessAnalyzer.alacConfig(new byte[24], 0));
        assertNull(LoudnessAnalyzer.alacCookie(new java.io.ByteArrayInputStream(new byte[]{0, 0, 0, 8, 'f', 'r', 'e', 'e'})));
    }

    private static byte[] wav(int tag, int channels, int rate, int bits, boolean extensible, byte[] samples) throws Exception {
        java.io.ByteArrayOutputStream file = new java.io.ByteArrayOutputStream();
        java.nio.ByteBuffer fmt = java.nio.ByteBuffer.allocate(extensible ? 40 : 16).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int block = channels * bits / 8;
        fmt.putShort((short) (extensible ? 0xfffe : tag)).putShort((short) channels).putInt(rate).putInt(rate * block).putShort((short) block).putShort((short) bits);
        if (extensible) fmt.putShort((short) 22).putShort((short) bits).putInt(3).putShort((short) tag);
        java.nio.ByteBuffer chunk = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        file.write("RIFF".getBytes("US-ASCII")); file.write(new byte[4]); file.write("WAVE".getBytes("US-ASCII"));
        file.write("LIST".getBytes("US-ASCII")); file.write(chunk.putInt(0, 3).array()); file.write(new byte[]{1, 2, 3, 0}); // odd chunk + pad
        file.write("fmt ".getBytes("US-ASCII")); file.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(fmt.capacity()).array()); file.write(fmt.array());
        file.write("data".getBytes("US-ASCII")); file.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(samples.length).array()); file.write(samples);
        return file.toByteArray();
    }

    @Test public void wavLayoutsDecodeIntegerAndFloatSamples() throws Exception {
        // 24-bit PCM: -0.5 and +0.25 full scale
        byte[] pcm24 = {0, 0, (byte) 0xc0, 0, 0, 0x20};
        java.io.InputStream in = new java.io.ByteArrayInputStream(wav(1, 2, 96000, 24, false, pcm24));
        LoudnessAnalyzer.Pcm layout = LoudnessAnalyzer.wavLayout(in);
        assertEquals(96000, layout.rate); assertEquals(2, layout.channels); assertEquals(3, layout.bytes); assertEquals(1, layout.frames);
        byte[] frame = new byte[6]; new java.io.DataInputStream(in).readFully(frame);
        assertEquals(-.5, layout.sample(frame, 0), 1e-9); assertEquals(.25, layout.sample(frame, 3), 1e-9);
        // 32-bit float inside WAVE_FORMAT_EXTENSIBLE
        byte[] float32 = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(.75f).putFloat(-1f).array();
        in = new java.io.ByteArrayInputStream(wav(3, 2, 48000, 32, true, float32));
        layout = LoudnessAnalyzer.wavLayout(in);
        assertTrue(layout.floating); new java.io.DataInputStream(in).readFully(frame = new byte[8]);
        assertEquals(.75, layout.sample(frame, 0), 1e-9); assertEquals(-1, layout.sample(frame, 4), 1e-9);
        // 8-bit is unsigned
        in = new java.io.ByteArrayInputStream(wav(1, 1, 8000, 8, false, new byte[]{(byte) 0, (byte) 128}));
        layout = LoudnessAnalyzer.wavLayout(in);
        assertEquals(-1, layout.sample(new byte[]{0}, 0), 1e-9); assertEquals(0, layout.sample(new byte[]{(byte) 128}, 0), 1e-9);
        try { LoudnessAnalyzer.wavLayout(new java.io.ByteArrayInputStream(wav(2, 2, 44100, 4, false, new byte[0]))); fail("ADPCM is not raw PCM"); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("编码")); }
    }

    @Test public void longSongsAreSampledAcrossTheirWholeLength() {
        List<long[]> short_ = LoudnessAnalyzer.windows(1000, 41000);
        assertEquals(1, short_.size()); assertEquals(1000, short_.get(0)[0]); assertEquals(41000, short_.get(0)[1]);
        List<long[]> windows = LoudnessAnalyzer.windows(0, 240000);
        assertEquals(8, windows.size());
        assertTrue(windows.get(0)[0] > 0 && windows.get(7)[1] < 240000);
        for (int i = 1; i < windows.size(); i++) assertTrue(windows.get(i)[0] >= windows.get(i - 1)[1]);
        assertEquals("Unknown duration decodes everything", 1, LoudnessAnalyzer.windows(0, 0).size());
    }
}
