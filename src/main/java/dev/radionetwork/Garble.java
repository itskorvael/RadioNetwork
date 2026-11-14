package dev.radionetwork;

import java.util.Random;

/**
 * What a bad signal does to radio text. {@link #dropouts} is a weak signal (words drop out and break off);
 * {@link #apply} is encryption noise, which a scanner has to decode (letters become static symbols, spaces and
 * punctuation survive so the shape of the sentence is still there). Pure.
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
     * A weak signal as a listener hears it: whole words drop out ("…") and some are cut off mid-word ("hel-"), the
     * way speech breaks up, instead of turning into symbols. Runs of lost words become a single gap. Nothing is
     * lost at {@code share} 0. Pure.
     */
    public static String dropouts(String text, double share, Random random) {
        if (share <= 0 || text.isEmpty()) {
            return text;
        }
        double lost = Math.min(1.0, share) * 0.8;
        double cut = Math.min(1.0, share) * 0.4;
        StringBuilder out = new StringBuilder(text.length());
        StringBuilder word = new StringBuilder();
        boolean gap = false;
        boolean skipSpace = false;
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : ' ';
            if (i < text.length() && !Character.isWhitespace(c)) {
                word.append(c);
                continue;
            }
            if (!word.isEmpty()) {
                String w = word.toString();
                word.setLength(0);
                if (random.nextDouble() < lost) {
                    if (!gap) {
                        out.append('…');
                        gap = true;
                    } else {
                        skipSpace = true; // joins the gap before it, so its space goes too
                    }
                } else {
                    gap = false;
                    if (w.length() >= 4 && random.nextDouble() < cut) {
                        out.append(w, 0, Math.max(2, w.length() / 2)).append('-');
                    } else {
                        out.append(w);
                    }
                }
            }
            if (i < text.length()) {
                if (skipSpace) {
                    skipSpace = false;
                } else {
                    out.append(c);
                }
            }
        }
        if (!Character.isWhitespace(text.charAt(text.length() - 1))) {
            while (!out.isEmpty() && Character.isWhitespace(out.charAt(out.length() - 1))) {
                out.setLength(out.length() - 1);
            }
        }
        return out.toString();
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
