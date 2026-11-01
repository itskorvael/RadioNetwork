package dev.radionetwork;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

/** Decodes an MP3 in plain Java (JLayer), for servers where voice chat's native decoder does not work. */
public final class Mp3Fallback {

    /** Interleaved 16-bit samples with their layout. */
    public record Audio(short[] pcm, int channels, float sampleRate) {
    }

    /** Thrown when the song would be longer than allowed; the message is shown to the player. */
    public static final class TooLongException extends IOException {
        TooLongException(String message) {
            super(message);
        }
    }

    private Mp3Fallback() {
    }

    public static Audio decode(byte[] mp3, int maxSeconds) throws IOException {
        Bitstream bitstream = new Bitstream(new ByteArrayInputStream(mp3));
        Decoder decoder = new Decoder();
        short[] pcm = new short[1 << 16];
        int length = 0;
        int channels = 0;
        int rate = 0;
        try {
            while (true) {
                Header header = bitstream.readFrame();
                if (header == null) {
                    break;
                }
                SampleBuffer samples = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                if (channels == 0) {
                    channels = samples.getChannelCount();
                    rate = samples.getSampleFrequency();
                }
                int count = samples.getBufferLength();
                if (length + count > pcm.length) {
                    pcm = Arrays.copyOf(pcm, Math.max(pcm.length * 2, length + count));
                }
                System.arraycopy(samples.getBuffer(), 0, pcm, length, count);
                length += count;
                bitstream.closeFrame();
                if (rate > 0 && (double) length / channels / rate > maxSeconds) {
                    throw new TooLongException("the song is longer than " + maxSeconds / 60 + " minutes.");
                }
            }
        } catch (BitstreamException | DecoderException e) {
            if (length == 0) {
                throw new IOException("that isn't a readable MP3 file.", e);
            }
            // A damaged or truncated tail: keep what decoded cleanly.
        } finally {
            try {
                bitstream.close();
            } catch (BitstreamException ignored) {
                // nothing to release
            }
        }
        if (length == 0 || channels == 0) {
            throw new IOException("that isn't a readable MP3 file.");
        }
        return new Audio(Arrays.copyOf(pcm, length), channels, rate);
    }
}
