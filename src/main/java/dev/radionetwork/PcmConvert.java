package dev.radionetwork;

/** Turns decoded audio into the 48 kHz mono 20 ms frames voice chat uses. Pure and allocation-light. */
public final class PcmConvert {

    public static final int RATE = 48_000;
    /** Samples in one 20 ms frame. */
    public static final int FRAME = 960;
    public static final int FRAME_MILLIS = 20;

    private PcmConvert() {
    }

    /** Interleaved 16-bit audio of any channel count and sample rate to mono at 48 kHz (linear resampling). */
    public static short[] toMono48k(short[] interleaved, int channels, float sampleRate) {
        if (channels < 1 || sampleRate <= 0) {
            throw new IllegalArgumentException("bad audio format");
        }
        int frames = interleaved.length / channels;
        short[] mono = new short[frames];
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                sum += interleaved[i * channels + c];
            }
            mono[i] = (short) (sum / channels);
        }
        if (Math.abs(sampleRate - RATE) < 0.5f) {
            return mono;
        }
        int outLength = (int) ((long) frames * RATE / sampleRate);
        short[] out = new short[outLength];
        double step = sampleRate / RATE;
        for (int j = 0; j < outLength; j++) {
            double position = j * step;
            int i0 = (int) position;
            int i1 = Math.min(i0 + 1, frames - 1);
            double fraction = position - i0;
            out[j] = (short) Math.round(mono[i0] * (1 - fraction) + mono[i1] * fraction);
        }
        return out;
    }

    /** Splits mono audio into 960-sample frames; the last one is padded with silence. */
    public static short[][] frames(short[] mono) {
        int count = (mono.length + FRAME - 1) / FRAME;
        short[][] frames = new short[count][FRAME];
        for (int f = 0; f < count; f++) {
            int from = f * FRAME;
            System.arraycopy(mono, from, frames[f], 0, Math.min(FRAME, mono.length - from));
        }
        return frames;
    }

    /** True when the bytes start like an MP3: an ID3 tag, or an MPEG audio frame header. */
    public static boolean looksLikeMp3(byte[] data) {
        if (data.length < 4) {
            return false;
        }
        boolean id3 = data[0] == 'I' && data[1] == 'D' && data[2] == '3';
        boolean frameSync = (data[0] & 0xFF) == 0xFF && (data[1] & 0xE0) == 0xE0;
        return id3 || frameSync;
    }

    /**
     * The MP3 without its metadata: leading ID3v2 tags (often holding megabytes of cover art, which some
     * decoders choke on) and the 128-byte ID3v1 trailer. Anything unexpected returns the bytes untouched.
     */
    public static byte[] stripTags(byte[] data) {
        int start = 0;
        int end = data.length;
        while (end - start >= 10 && data[start] == 'I' && data[start + 1] == 'D' && data[start + 2] == '3') {
            int size = (data[start + 6] & 0x7F) << 21 | (data[start + 7] & 0x7F) << 14
                    | (data[start + 8] & 0x7F) << 7 | (data[start + 9] & 0x7F);
            int footer = (data[start + 5] & 0x10) != 0 ? 10 : 0;
            int next = start + 10 + size + footer;
            if (size < 0 || next > end) {
                return data;
            }
            start = next;
        }
        if (end - start > 128 && data[end - 128] == 'T' && data[end - 127] == 'A' && data[end - 126] == 'G') {
            end -= 128;
        }
        if (start == 0 && end == data.length) {
            return data;
        }
        return java.util.Arrays.copyOfRange(data, start, end);
    }
}
