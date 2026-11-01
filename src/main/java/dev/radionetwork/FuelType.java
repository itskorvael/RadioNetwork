package dev.radionetwork;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

/**
 * Fuel attribute matrix. Text and voice share this table, so range and dimension rules stay identical.
 */
public enum FuelType {
    NONE(null, 0, 250, false),
    REDSTONE(Material.REDSTONE, 60, 250, false),
    GLOWSTONE(Material.GLOWSTONE_DUST, 120, 500, false),
    ENDER_PEARL(Material.ENDER_PEARL, 300, Integer.MAX_VALUE, true);

    private final @Nullable Material material;
    private final long burnSeconds;
    private final int maxRadius;
    private final boolean crossDimension;

    FuelType(@Nullable Material material, long burnSeconds, int maxRadius, boolean crossDimension) {
        this.material = material;
        this.burnSeconds = burnSeconds;
        this.maxRadius = maxRadius;
        this.crossDimension = crossDimension;
    }

    public long burnSeconds() {
        return burnSeconds;
    }

    /** Hard cap for CustomRadius while this fuel is active. */
    public int maxRadius() {
        return maxRadius;
    }

    public boolean crossDimension() {
        return crossDimension;
    }

    public static FuelType of(Material material) {
        for (FuelType type : values()) {
            if (type.material == material) {
                return type;
            }
        }
        return NONE;
    }

    /** Higher tier wins when several fuels are inserted at once. */
    public FuelType best(FuelType other) {
        return other.ordinal() > ordinal() ? other : this;
    }
}
