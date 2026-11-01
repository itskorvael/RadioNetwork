package dev.radionetwork;

import java.util.Random;

/**
 * Turns radio text into static, the way a weak signal does to voice: the weaker the signal, the more letters
 * are replaced, and at the bottom some whole words drop out. Spaces and punctuation survive, so the shape of
 * the sentence is still there. Pure.
 */
public final class Garble {

    private static final String STATIC = "▒░▓#%&*?~";

    private Garble() {
    }

    /**
     * Share of the text that is lost at a given signal: nothing at 4 bars, rising evenly to {@code max} at 1 bar.
     * Signals above four count as four, below one as one.
     */
    public static double share(int bars, double max) {
        if (max <= 0) {
            return 0;
        }
        int clamped = Math.max(1, Math.min(4, bars));
        return Math.min(1.0, max) * (4 - clamped) / 3.0;
    }

    /**
     * Each letter or digit is replaced by a static symbol with probability {@code share}; on top of that each
     * word drops out completely with probability {@code share} squared, so only a very weak signal loses words.
     */
    public static String apply(String text, double share, Random random) {
        if (share <= 0 || text.isEmpty()) {
            return text;
        }
        double dropout = share * share;
        StringBuilder out = new StringBuilder(text.length());
        StringBuilder word = new StringBuilder();
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : ' ';
            if (i < text.length() && !Character.isWhitespace(c)) {
                word.append(c);
                continue;
            }
            if (!word.isEmpty()) {
                out.append(garbleWord(word.toString(), share, dropout, random));
                word.setLength(0);
            }
            if (i < text.length()) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String garbleWord(String word, double share, double dropout, Random random) {
        boolean gone = random.nextDouble() < dropout;
        StringBuilder out = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isLetterOrDigit(c) && (gone || random.nextDouble() < share)) {
                out.append(STATIC.charAt(random.nextInt(STATIC.length())));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
