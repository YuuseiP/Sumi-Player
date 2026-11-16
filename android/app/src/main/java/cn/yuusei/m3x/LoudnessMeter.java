package cn.yuusei.m3x;

import org.json.JSONObject;
import java.util.Arrays;

/**
 * ITU-R BS.1770-4 integrated loudness: K-weighting, 400 ms blocks with 75 % overlap,
 * absolute (-70 LUFS) and relative (-10 LU) gating. Pure Java so it can be unit tested.
 */
final class LoudnessMeter {
    /** ReplayGain 2.0 tags are referenced to -18 LUFS. */
    static final double REPLAYGAIN_REFERENCE = -18;
    static final double DEFAULT_TARGET = -16;

    private final int channels, subblock;
    private final double[] weights;
    private final double[][] state; // per channel: x1, x2, y1, y2 (shelf) then x1, x2, y1, y2 (high-pass)
    private final double sb0, sb1, sb2, sa1, sa2, hb0, hb1, hb2, ha1, ha2;
    private final double[] recent = new double[4];
    private int recentCount, recentIndex, subblockFrames;
    private double subblockEnergy, peak;
    private double[] blocks = new double[256];
    private int blockCount;

    LoudnessMeter(int rate, int channels) {
        if (rate < 8000 || rate > 768000 || channels < 1 || channels > 32) throw new IllegalArgumentException("不支持的响度分析格式");
        this.channels = channels; subblock = Math.max(1, (int) Math.round(rate * .1));
        weights = new double[channels];
        for (int c = 0; c < channels; c++) weights[c] = channels >= 6 ? (c == 3 ? 0 : c >= 4 ? 1.41 : 1) : 1;
        state = new double[channels][8];
        // High-shelf stage (head effects), coefficients derived for any sample rate.
        double f0 = 1681.974450955533, gain = 3.999843853973347, q = .7071752369554196;
        double k = Math.tan(Math.PI * f0 / rate), vh = Math.pow(10, gain / 20), vb = Math.pow(vh, .4996667741545416);
        double a0 = 1 + k / q + k * k;
        sb0 = (vh + vb * k / q + k * k) / a0; sb1 = 2 * (k * k - vh) / a0; sb2 = (vh - vb * k / q + k * k) / a0;
        sa1 = 2 * (k * k - 1) / a0; sa2 = (1 - k / q + k * k) / a0;
        // RLB high-pass stage.
        f0 = 38.13547087602444; q = .5003270373238773; k = Math.tan(Math.PI * f0 / rate);
        a0 = 1 + k / q + k * k;
        hb0 = 1; hb1 = -2; hb2 = 1; ha1 = 2 * (k * k - 1) / a0; ha2 = (1 - k / q + k * k) / a0;
    }

    /** One interleaved frame of samples in [-1, 1]. */
    void frame(double[] samples) {
        double energy = 0;
        for (int c = 0; c < channels; c++) {
            double x = samples[c];
            double absolute = Math.abs(x); if (absolute > peak) peak = absolute;
            if (weights[c] == 0) continue;
            double[] s = state[c];
            double y = sb0 * x + sb1 * s[0] + sb2 * s[1] - sa1 * s[2] - sa2 * s[3];
            s[1] = s[0]; s[0] = x; s[3] = s[2]; s[2] = y;
            double z = hb0 * y + hb1 * s[4] + hb2 * s[5] - ha1 * s[6] - ha2 * s[7];
            s[5] = s[4]; s[4] = y; s[7] = s[6]; s[6] = z;
            energy += weights[c] * z * z;
        }
        subblockEnergy += energy;
        if (++subblockFrames < subblock) return;
        recent[recentIndex] = subblockEnergy; recentIndex = (recentIndex + 1) % 4;
        subblockEnergy = 0; subblockFrames = 0;
        if (recentCount < 4) recentCount++;
        if (recentCount == 4) {
            if (blockCount == blocks.length) blocks = Arrays.copyOf(blocks, blockCount * 2);
            blocks[blockCount++] = (recent[0] + recent[1] + recent[2] + recent[3]) / (4d * subblock);
        }
    }

    /** Starts a non-contiguous excerpt: blocks never straddle two excerpts. */
    void gap() {
        recentCount = 0; recentIndex = 0; subblockEnergy = 0; subblockFrames = 0;
        for (double[] s : state) Arrays.fill(s, 0);
    }

    /** Integrated loudness in LUFS, or NaN when nothing exceeds the absolute gate. */
    double integrated() {
        double absolute = energy(-70), sum = 0; int count = 0;
        for (int i = 0; i < blockCount; i++) if (blocks[i] > absolute) { sum += blocks[i]; count++; }
        if (count == 0) return Double.NaN;
        double relative = energy(lufs(sum / count) - 10); sum = 0; count = 0;
        for (int i = 0; i < blockCount; i++) if (blocks[i] > absolute && blocks[i] > relative) { sum += blocks[i]; count++; }
        return count == 0 ? Double.NaN : lufs(sum / count);
    }

    double peak() { return peak; }
    int blocks() { return blockCount; }

    private static double lufs(double energy) { return -0.691 + 10 * Math.log10(energy); }
    private static double energy(double lufs) { return Math.pow(10, (lufs + 0.691) / 10); }

    /** Linear attenuation (never a boost) that brings {@code lufs} down to {@code target}. */
    static double attenuation(double lufs, double target) {
        if (!Double.isFinite(lufs) || !Double.isFinite(target)) return 1;
        return Math.max(Math.pow(10, -40 / 20d), Math.min(1, Math.pow(10, (target - lufs) / 20)));
    }

    /** ReplayGain tag gain in dB for the requested mode ("track" or "album", album falls back to track); NaN when absent. */
    static double tagGain(JSONObject tags, String mode) {
        if (tags == null) return Double.NaN;
        String prefix = "replaygain_" + ("album".equals(mode) ? "album" : "track");
        if (!tags.has(prefix + "_gain")) prefix = "replaygain_track";
        if (!tags.has(prefix + "_gain")) return Double.NaN;
        try {
            double db = Double.parseDouble(tags.optString(prefix + "_gain").trim().replaceAll("(?i)\\s*dB$", "").replace("+", ""));
            return db >= -60 && db <= 30 ? db : Double.NaN;
        } catch (NumberFormatException ignored) { return Double.NaN; }
    }
}
