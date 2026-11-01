package dev.radionetwork;

/** OPEN: anyone nearby may speak. CLOSED: anyone may listen, only the creator may speak. */
public enum RadioMode {
    OPEN,
    CLOSED;

    public RadioMode toggled() {
        return this == OPEN ? CLOSED : OPEN;
    }
}
