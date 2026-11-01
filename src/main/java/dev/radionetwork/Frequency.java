package dev.radionetwork;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * A claimed channel name. Two independent switches:
 * <ul>
 *   <li>{@link Access}: PUBLIC lets anyone tune in; ENCRYPTED needs the password (or an invite). A Scanner can
 *       listen to an encrypted frequency without joining, but hears only noise until it has decoded it.</li>
 *   <li>{@link RadioMode}: OPEN lets everyone with access speak; CLOSED limits speaking to the
 *       owner and trusted players.</li>
 * </ul>
 * ENCRYPTED + CLOSED is the fully locked-down "isolated" setup. Main-thread only.
 */
public final class Frequency {

    public enum Access {
        PUBLIC,
        ENCRYPTED;

        public Access toggled() {
            return this == PUBLIC ? ENCRYPTED : PUBLIC;
        }

        /** Reads a saved or typed value; the old name PRIVATE still means ENCRYPTED. */
        public static Access parse(String text) {
            String value = text.trim().toUpperCase(Locale.ROOT);
            return value.equals("PRIVATE") ? ENCRYPTED : valueOf(value);
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String name;
    private String owner;
    private Access access = Access.PUBLIC;
    private RadioMode speech = RadioMode.OPEN;
    private String salt = "";
    private String hash = "";
    /** How long the password is (0 when unknown, for frequencies saved before this was kept); sets how hard it is to decode. */
    private int passwordLength;
    private final Set<String> trusted = new LinkedHashSet<>();
    private final Set<String> members = new HashSet<>();
    private long lastSeen = System.currentTimeMillis();
    /** When the last radio left this frequency (0 = it still has radios). */
    private long emptySince;

    public Frequency(String name, String owner) {
        this.name = name;
        this.owner = owner;
    }

    private static String id(String player) {
        return player.toLowerCase(Locale.ROOT);
    }

    public String name() {
        return name;
    }

    public String owner() {
        return owner;
    }

    public void owner(String owner) {
        this.owner = owner;
    }

    public Access access() {
        return access;
    }

    public void access(Access access) {
        this.access = access;
    }

    public RadioMode speech() {
        return speech;
    }

    public void speech(RadioMode speech) {
        this.speech = speech;
    }

    public Set<String> trusted() {
        return trusted;
    }

    public Set<String> members() {
        return members;
    }

    public long lastSeen() {
        return lastSeen;
    }

    public void lastSeen(long lastSeen) {
        this.lastSeen = lastSeen;
    }

    public long emptySince() {
        return emptySince;
    }

    public void emptySince(long emptySince) {
        this.emptySince = emptySince;
    }

    public boolean isOwner(String player) {
        return owner.equalsIgnoreCase(player);
    }

    public boolean isTrusted(String player) {
        return trusted.contains(id(player));
    }

    public void trust(String player) {
        trusted.add(id(player));
    }

    public boolean untrust(String player) {
        return trusted.remove(id(player));
    }

    /** May this player tune in and listen? */
    public boolean hasAccess(String player) {
        return access == Access.PUBLIC || isOwner(player) || isTrusted(player) || members.contains(id(player));
    }

    /** May this player speak? Needs access first, then OPEN, or owner/trusted when CLOSED. */
    public boolean canSpeak(String player) {
        return hasAccess(player) && (speech == RadioMode.OPEN || isOwner(player) || isTrusted(player));
    }

    /** Why this player has access, for feedback: owner, trusted, member, or public. */
    public String accessReason(String player) {
        if (isOwner(player)) {
            return "the owner";
        }
        if (isTrusted(player)) {
            return "a trusted player";
        }
        if (members.contains(id(player))) {
            return "a member (joined with the password)";
        }
        return access == Access.PUBLIC ? "public" : "none";
    }

    public void grantMember(String player) {
        members.add(id(player));
    }

    public boolean hasPassword() {
        return !hash.isEmpty();
    }

    /** Sets a new password and revokes everyone who joined with the old one. */
    public void setPassword(String password) {
        byte[] saltBytes = new byte[16];
        RANDOM.nextBytes(saltBytes);
        this.salt = HexFormat.of().formatHex(saltBytes);
        this.hash = digest(salt, password);
        this.passwordLength = password.length();
        members.clear();
    }

    public boolean checkPassword(String password) {
        if (!hasPassword()) {
            return false;
        }
        return MessageDigest.isEqual(
                digest(salt, password).getBytes(StandardCharsets.UTF_8), hash.getBytes(StandardCharsets.UTF_8));
    }

    String salt() {
        return salt;
    }

    String hash() {
        return hash;
    }

    int passwordLength() {
        return passwordLength;
    }

    void restorePassword(String salt, String hash, int length) {
        this.salt = salt;
        this.hash = hash;
        this.passwordLength = Math.max(0, length);
    }

    private static String digest(String salt, String password) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest((salt + password).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
