package dev.radionetwork;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jetbrains.annotations.Nullable;

/** One registered Lodestone radio terminal. Main-thread only. */
public final class Radio {

    private final String world;
    private final int x;
    private final int y;
    private final int z;
    private final String creatorName;

    private String channel;
    private int customRadius = RadioManager.DEFAULT_RADIUS;
    private long fuelLevel;
    private FuelType activeFuel = FuelType.NONE;
    private boolean active;
    /** Runtime only: the fraction of a second of relay fuel not yet burned (the extra drain can be fractional). */
    double relayDrainRemainder;
    /** Runtime only: when a lightning strike's outage ends, in milliseconds (0 = not struck). */
    private long offlineUntil;

    public Radio(String world, int x, int y, int z, String creatorName, String channel) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.creatorName = creatorName;
        this.channel = channel;
    }

    public String key() {
        return RadioManager.key(world, x, y, z);
    }

    public String world() {
        return world;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public String creatorName() {
        return creatorName;
    }

    public String channel() {
        return channel;
    }

    public void channel(String channel) {
        this.channel = channel;
    }

    public boolean isTuned() {
        return !channel.isEmpty();
    }

    public int customRadius() {
        return customRadius;
    }

    public void customRadius(int customRadius) {
        this.customRadius = customRadius;
    }

    public long fuelLevel() {
        return fuelLevel;
    }

    /** Sets the fuel reservoir; an empty reservoir deactivates the radio and clears the fuel type. */
    public void fuelLevel(long seconds) {
        this.fuelLevel = Math.max(0, seconds);
        this.active = this.fuelLevel > 0;
        if (!active) {
            this.activeFuel = FuelType.NONE;
        }
    }

    public FuelType activeFuel() {
        return activeFuel;
    }

    public void activeFuel(FuelType activeFuel) {
        this.activeFuel = activeFuel;
    }

    public boolean isActive() {
        return active;
    }

    /** Has fuel in the tank (a lightning outage does not change that). */
    public boolean hasFuel() {
        return active && fuelLevel > 0;
    }

    /** Working: fuel in the tank and not knocked offline by lightning. */
    public boolean isPowered() {
        return hasFuel() && !isOffline();
    }

    /** True while a lightning strike has the terminal knocked offline. */
    public boolean isOffline() {
        return offlineUntil > 0 && System.currentTimeMillis() < offlineUntil;
    }

    public long offlineUntil() {
        return offlineUntil;
    }

    public void offlineUntil(long millis) {
        this.offlineUntil = millis;
    }

    public @Nullable World bukkitWorld() {
        return Bukkit.getWorld(world);
    }

    public @Nullable Location center() {
        World w = bukkitWorld();
        return w == null ? null : new Location(w, x + 0.5, y + 0.5, z + 0.5);
    }

    public @Nullable Block block() {
        World w = bukkitWorld();
        return w == null ? null : w.getBlockAt(x, y, z);
    }
}
