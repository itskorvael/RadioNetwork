package probe;

import dev.radionetwork.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Lightable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Runs inside a real Paper server (see tests/run-probes.sh). It drives RadioNetwork's own classes directly
 * (no players needed), prints one PASS or FAIL line per check, a RESULT line, and shuts the server down.
 */
public final class RadioProbe extends JavaPlugin {

    private int pass;
    private int fail;
    private Plugin radio;
    private RadioManager mgr;
    private RadioItem items;
    private Jammers jammers;
    private Repeaters repeaters;
    private World world;

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, this::runAll, 60L);
    }

    // ------------------------------------------------------------------ helpers

    private void ok(String name, boolean condition) {
        if (condition) {
            pass++;
        } else {
            fail++;
        }
        getLogger().info((condition ? "PASS " : "FAIL ") + name);
    }

    private void eq(String name, Object actual, Object expected) {
        boolean same = actual == null ? expected == null : actual.equals(expected);
        ok(name + (same ? "" : " (got " + actual + ", expected " + expected + ")"), same);
    }

    private void near(String name, double actual, double expected, double tolerance) {
        ok(name + (Math.abs(actual - expected) <= tolerance ? "" : " (got " + actual + ", expected " + expected + " +/- " + tolerance + ")"),
                Math.abs(actual - expected) <= tolerance);
    }

    private interface Section {
        void run() throws Throwable;
    }

    private void section(String title, Section body) {
        getLogger().info("== " + title);
        try {
            body.run();
        } catch (Throwable t) {
            fail++;
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            getLogger().severe("FAIL section '" + title + "' threw " + cause);
            for (StackTraceElement e : cause.getStackTrace()) {
                getLogger().severe("    at " + e);
                if (e.getClassName().startsWith("probe.")) {
                    break;
                }
            }
        }
    }

    private Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private Block blockAt(int x, int z) {
        var chunk = world.getChunkAt(x >> 4, z >> 4);
        chunk.load(true);
        chunk.setForceLoaded(true);
        int y = world.getHighestBlockYAt(x, z) + 1;
        return world.getBlockAt(x, y, z);
    }

    private Radio terminal(int x, int z, String owner, String channel, long fuel) {
        Block block = blockAt(x, z);
        block.setType(Material.LODESTONE, false);
        mgr.frequencies().claim(channel, owner);
        Radio r = mgr.register(block, owner);
        r.channel(channel);
        mgr.addFuel(r, FuelType.REDSTONE, fuel);
        return r;
    }

    private int words(String s) {
        return s.isBlank() ? 0 : s.trim().split("\\s+").length;
    }

    // ---------------------------------------------------------------------- run

    private void runAll() {
        try {
            radio = Bukkit.getPluginManager().getPlugin("RadioNetwork");
            mgr = (RadioManager) field(radio, "manager");
            items = (RadioItem) field(radio, "radioItem");
            jammers = mgr.jammers();
            repeaters = mgr.repeaters();
            world = Bukkit.getWorlds().get(0);
        } catch (Throwable t) {
            getLogger().severe("FAIL setup: " + t);
            fail++;
            finish();
            return;
        }
        section("Garble: signal strength", this::garbleShare);
        section("Garble: dropped words", this::garbleDropouts);
        section("Garble: encryption noise", this::garbleNoise);
        section("Alerts and chat tags", this::alerts);
        section("Documented config defaults", this::configDefaults);
        section("Items", this::itemChecks);
        section("Decommissioned items and blocks", this::decommissioned);
        section("Menu text", this::menuText);
        section("Local chat", this::localChat);
        section("Frequencies and access", this::frequencyChecks);
        section("Terminals, fuel and announcements", this::terminalChecks);
        section("Relays", this::relayChecks);
        section("Jammer: block, light and burn-out", this::jammerBlock);
        section("Jammer: full life cycle", this::jammerLife);
        section("Scanner numbers", this::scannerNumbers);
        finish();
    }

    private void finish() {
        getLogger().info("RESULT pass=" + pass + " fail=" + fail);
        Bukkit.shutdown();
    }

    // ------------------------------------------------------------------- garble

    private void garbleShare() {
        eq("4 bars is clean", Garble.share(4, 0.35), 0.0);
        near("1 bar loses the maximum", Garble.share(1, 0.35), 0.35, 1e-9);
        near("2 bars loses two thirds of it", Garble.share(2, 0.35), 0.35 * 2 / 3, 1e-9);
        near("3 bars loses one third of it", Garble.share(3, 0.35), 0.35 / 3, 1e-9);
        eq("more than 4 bars counts as 4", Garble.share(9, 0.35), 0.0);
        near("fewer than 1 bar counts as 1", Garble.share(0, 0.35), 0.35, 1e-9);
        eq("a maximum of 0 turns garble off", Garble.share(1, 0.0), 0.0);
        ok("loss never decreases as bars drop", Garble.share(1, 0.5) > Garble.share(2, 0.5) && Garble.share(2, 0.5) > Garble.share(3, 0.5));
    }

    private void garbleDropouts() {
        Random random = new Random(11);
        String sentence = "Train four to Northgate departs from platform two in five minutes";
        eq("share 0 changes nothing", Garble.dropouts(sentence, 0.0, random), sentence);
        eq("empty text stays empty", Garble.dropouts("", 0.35, random), "");

        int trials = 20000;
        int lost = 0;
        int cut = 0;
        boolean longer = false;
        boolean doubleGap = false;
        for (int i = 0; i < trials; i++) {
            String out = Garble.dropouts("station", 0.35, random);
            if (out.equals("…")) {
                lost++;
            } else if (out.endsWith("-")) {
                cut++;
            }
            if (out.length() > "station".length()) {
                longer = true;
            }
            if (Garble.dropouts("alpha beta gamma delta epsilon zeta", 0.35, random).contains("… …")) {
                doubleGap = true;
            }
        }
        near("1 bar loses about 28% of words", lost / (double) trials, 0.28, 0.02);
        near("1 bar cuts off about 10% of words mid-word", cut / (double) trials, 0.10, 0.02);
        ok("garbling never makes text longer", !longer);
        ok("a run of lost words is one gap, not several", !doubleGap);
        int kept = 0;
        for (int i = 0; i < 2000; i++) {
            kept += words(Garble.dropouts(sentence, 0.35 / 3, random)) > 0 ? 1 : 0;
        }
        ok("3 bars still leaves something to hear", kept == 2000);
        boolean spaced = true;
        for (int i = 0; i < 3000; i++) {
            String out = Garble.dropouts(sentence, 0.8, random);
            if (out.contains("  ") || out.startsWith(" ") || out.endsWith(" ")) {
                spaced = false;
            }
        }
        ok("spacing stays clean however many words are lost", spaced);
    }

    private void garbleNoise() {
        Random random = new Random(5);
        eq("noise at share 0 changes nothing", Garble.apply("hello world", 0.0, random), "hello world");
        String noisy = Garble.apply("hello world", 1.0, random);
        eq("full noise keeps the length", noisy.length(), "hello world".length());
        eq("full noise keeps the space", noisy.charAt(5), ' ');
        boolean onlyStatic = true;
        for (char c : noisy.toCharArray()) {
            if (c != ' ' && "▒░▓#%&*?~".indexOf(c) < 0) {
                onlyStatic = false;
            }
        }
        ok("encryption noise is still symbols", onlyStatic);
    }

    // ------------------------------------------------------------ alerts / chat

    private void alerts() {
        String yellow = Alerts.line(Alerts.Level.YELLOW, "Signal", "Weak signal on " + Alerts.name("WX") + ".");
        String red = Alerts.line(Alerts.Level.RED, "Signal", "No signal on " + Alerts.name("WX") + ".");
        String green = Alerts.line(Alerts.Level.CLEAR, "Power", "Back online.");
        eq("yellow alert", yellow, "§8[§eSignal§8] §7Weak signal on §fWX§7.");
        eq("red alert is red all the way through", red, "§8[§cSignal§8] §cNo signal on §fWX§c.");
        eq("green alert", green, "§8[§aPower§8] §7Back online.");
        ok("no alert is bold", !yellow.contains("§l") && !red.contains("§l") && !green.contains("§l"));
        ok("no alert has an icon", !(yellow + red + green).matches(".*[✖⚠✔].*"));
        eq("channel tag", Chat.tag("§6", "Radio", "WX"), "§8[§6Radio§8 - §fWX§8] ");
        eq("tag without a channel", Chat.tag("§5", "Scanner"), "§8[§5Scanner§8] ");
        eq("player text loses the section sign and control characters", Chat.clean("  §cred\u0007  "), "cred");
        eq("durations are minutes and seconds", Chat.duration(125), "2:05");
    }

    // ---------------------------------------------------------- config defaults

    private void configDefaults() {
        var c = radio.getConfig();
        eq("weather notices are off", c.getBoolean("weather.notices"), false);
        eq("garble maximum", c.getDouble("garble.max-share"), 0.35);
        eq("relays cost no bars", c.getInt("relay.bars-per-hop"), 0);
        eq("relay chain length", c.getInt("relay.max-hops"), 3);
        eq("jammer channel alerts are on", c.getBoolean("jammer.channel-alerts"), true);
        eq("jammer blast power", c.getDouble("jammer.explosion-power"), 2.0);
        eq("jammer blast breaks no blocks", c.getBoolean("jammer.explosion-breaks-blocks"), false);
        eq("jammer countdown", c.getInt("jammer.countdown-seconds"), 10);
        eq("jammer warm-up", c.getInt("jammer.warmup-seconds"), 60);
        eq("announcement search radius", c.getDouble("announcements.search-radius"), 48.0);
        eq("decoder stages", c.getInt("scanner.puzzle.stages"), 3);
        eq("decoder guesses per code", c.getInt("scanner.puzzle.max-guesses"), 10);
        eq("scanner decay minutes per stage", c.getDouble("scanner.decay-minutes-per-stage"), 7.0);
        eq("red alert delay minimum", c.getDouble("scanner.red-alert-delay-min-seconds"), 60.0);
        eq("red alert delay maximum", c.getDouble("scanner.red-alert-delay-max-seconds"), 180.0);
        eq("yellow alert delay minimum", c.getDouble("scanner.yellow-alert-delay-min-seconds"), 10.0);
        eq("yellow alert delay maximum", c.getDouble("scanner.yellow-alert-delay-max-seconds"), 40.0);
    }

    // --------------------------------------------------------------- local chat

    private void localChat() {
        var c = radio.getConfig();
        eq("local chat is on", c.getBoolean("local-chat.enabled"), true);
        eq("local chat radius", c.getDouble("local-chat.radius"), 32.0);
        eq("local chat vertical reach", c.getDouble("local-chat.vertical"), 32.0);
        eq("local chat tells a lonely speaker", c.getBoolean("local-chat.lonely-notice"), true);

        org.bukkit.Location me = new org.bukkit.Location(world, 0.5, 64, 0.5);
        ok("a player beside you hears you", dev.radionetwork.LocalChat.hears(me, new org.bukkit.Location(world, 3.5, 64, 0.5), 50, 64));
        ok("a player exactly at the radius hears you", dev.radionetwork.LocalChat.hears(me, new org.bukkit.Location(world, 50.5, 64, 0.5), 50, 64));
        ok("a player just past the radius does not", !dev.radionetwork.LocalChat.hears(me, new org.bukkit.Location(world, 51.5, 64, 0.5), 50, 64));
        ok("distance is measured across, not along an axis", !dev.radionetwork.LocalChat.hears(me, new org.bukkit.Location(world, 40.5, 64, 40.5), 50, 64));
        ok("a diagonal inside the radius hears you", dev.radionetwork.LocalChat.hears(me, new org.bukkit.Location(world, 30.5, 64, 30.5), 50, 64));
        ok("height up to the vertical reach still hears you", dev.radionetwork.LocalChat.hears(me, new org.bukkit.Location(world, 0.5, 128, 0.5), 50, 64));
        ok("height past the vertical reach does not", !dev.radionetwork.LocalChat.hears(me, new org.bukkit.Location(world, 0.5, 129, 0.5), 50, 64));
        ok("a cave deep under a base still hears it", dev.radionetwork.LocalChat.hears(me, new org.bukkit.Location(world, 10.5, 20, 10.5), 50, 64));
        ok("hearing works both ways", dev.radionetwork.LocalChat.hears(new org.bukkit.Location(world, 3.5, 64, 0.5), me, 50, 64));
        for (org.bukkit.World other : Bukkit.getWorlds()) {
            if (!other.equals(world)) {
                ok("another world never hears you", !dev.radionetwork.LocalChat.hears(me, new org.bukkit.Location(other, 0.5, 64, 0.5), 50, 64));
                break;
            }
        }

        ok("the global permission exists, for ops", Bukkit.getPluginManager().getPermission(dev.radionetwork.LocalChat.GLOBAL) != null
                && Bukkit.getPluginManager().getPermission(dev.radionetwork.LocalChat.GLOBAL).getDefault() == org.bukkit.permissions.PermissionDefault.OP);
        ok("the spy permission exists, for nobody", Bukkit.getPluginManager().getPermission(dev.radionetwork.LocalChat.SPY) != null
                && Bukkit.getPluginManager().getPermission(dev.radionetwork.LocalChat.SPY).getDefault() == org.bukkit.permissions.PermissionDefault.FALSE);
        // Whisper and shout tiers
        eq("whisper reaches 6 blocks", c.getDouble("local-chat.whisper.radius"), 6.0);
        eq("whisper is seen just outside", c.getBoolean("local-chat.whisper.notice"), true);
        eq("shout reaches 64 blocks", c.getDouble("local-chat.shout.radius"), 64.0);
        eq("shout is clear to 40 blocks", c.getDouble("local-chat.shout.clear-radius"), 40.0);
        eq("shout cooldown", c.getDouble("local-chat.shout.cooldown-seconds"), 5.0);
        eq("far shouts are anonymous", c.getBoolean("local-chat.shout.anonymous"), true);
        var r = new VoiceCommand.Ranges(32, 32, 6, 64, 40);
        var W = VoiceCommand.Tier.WHISPER;
        var S = VoiceCommand.Tier.SHOUT;
        eq("whisper: beside you is heard", VoiceCommand.reach(W, 3, 0, r, true), VoiceCommand.Reach.FULL);
        eq("whisper: at 6 blocks is heard", VoiceCommand.reach(W, 6, 0, r, true), VoiceCommand.Reach.FULL);
        eq("whisper: 7 blocks away only sees it", VoiceCommand.reach(W, 7, 0, r, true), VoiceCommand.Reach.CUE);
        eq("whisper: 32 blocks away still sees it", VoiceCommand.reach(W, 32, 0, r, true), VoiceCommand.Reach.CUE);
        eq("whisper: past talking range sees nothing", VoiceCommand.reach(W, 33, 0, r, true), VoiceCommand.Reach.NONE);
        eq("whisper: with the cue off, outside is nothing", VoiceCommand.reach(W, 7, 0, r, false), VoiceCommand.Reach.NONE);
        eq("whisper: another floor up is not heard", VoiceCommand.reach(W, 2, 33, r, true), VoiceCommand.Reach.NONE);
        eq("shout: at 64 blocks is heard", VoiceCommand.reach(S, 64, 0, r, true), VoiceCommand.Reach.FULL);
        eq("shout: at 65 blocks is not", VoiceCommand.reach(S, 65, 0, r, true), VoiceCommand.Reach.NONE);
        eq("shout: too far up is not heard", VoiceCommand.reach(S, 10, 33, r, true), VoiceCommand.Reach.NONE);
        eq("shout: clear inside 40 blocks", VoiceCommand.bars(40, r), 4);
        eq("shout: still near the clear edge", VoiceCommand.bars(41, r), 4);
        eq("shout: a third of the way out", VoiceCommand.bars(49, r), 3);
        eq("shout: two thirds out", VoiceCommand.bars(57, r), 2);
        eq("shout: at the edge it is thin", VoiceCommand.bars(64, r), 1);
        eq("shout: a shout never carries less than normal talk", new VoiceCommand(radio instanceof dev.radionetwork.RadioNetworkPlugin p ? p : null, mgr).ranges().shout() >= 32, true);
        var cmd = Bukkit.getPluginCommand("whisper");
        ok("/whisper is registered to this plugin", cmd != null && cmd.getPlugin() == radio && cmd.getExecutor() instanceof VoiceCommand);
        ok("/shout is registered to this plugin", Bukkit.getPluginCommand("shout") != null && Bukkit.getPluginCommand("shout").getExecutor() instanceof VoiceCommand);
        var w = Bukkit.getCommandMap().getCommand("w");
        ok("/w is the same command as /whisper (not vanilla's /w)", w != null && w == cmd);
        ok("the chat listener is registered", io.papermc.paper.event.player.AsyncChatEvent.getHandlerList().getRegisteredListeners(radio)
                .stream().anyMatch(l -> l.getListener() instanceof dev.radionetwork.LocalChat));
    }

    // -------------------------------------------------------------------- items

    private void decommissioned() throws Exception {
        var kinds = RadioItem.Retired.values();
        ItemStack[] live = {items.create(), items.createJukebox(), items.createRepeater(), items.createJammer()};
        for (int i = 0; i < kinds.length; i++) {
            RadioItem.Retired kind = kinds[i];
            ItemStack dead = items.createDecommissioned(kind);
            String id = kind.id();
            eq(id + ": the item is the same block", dead.getType(), live[i].getType());
            eq(id + ": it is recognised as decommissioned", items.decommissionedKind(dead), kind);
            ok(id + ": it is called Decommissioned", dead.getItemMeta().displayName() != null
                    && net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(dead.getItemMeta().displayName()).startsWith("Decommissioned "));
            eq(id + ": one lore line", dead.getItemMeta().lore().size(), 1);
            ok(id + ": no glint, unlike the working item", !dead.getItemMeta().hasEnchantmentGlintOverride()
                    && live[i].getItemMeta().hasEnchantmentGlintOverride());
            ok(id + ": it is not a working item", !items.isRadio(dead) && !items.isJukebox(dead) && !items.isRepeater(dead)
                    && !items.isJammer(dead) && items.liveKind(dead) == null);
            eq(id + ": the working item is its live kind", items.liveKind(live[i]), kind);
            ok(id + ": the working item is not decommissioned", !items.isDecommissioned(live[i]));
            ok(id + ": a plain block is neither", items.liveKind(new ItemStack(kind.block())) == null
                    && !items.isDecommissioned(new ItemStack(kind.block())));
            var recipe = Bukkit.getRecipe(items.decommissionRecipeKey(kind));
            ok(id + ": the crafting recipe exists", recipe != null);
            ok(id + ": the recipe makes the decommissioned item", recipe instanceof org.bukkit.inventory.ShapelessRecipe shapeless
                    && items.decommissionedKind(shapeless.getResult()) == kind && shapeless.getChoiceList().size() == 1);
            eq(id + ": the recipe key maps back to its kind", items.decommissionRecipeKind(items.decommissionRecipeKey(kind)), kind);
            ok(id + ": the recipe key is listed", items.recipeKeys().contains(items.decommissionRecipeKey(kind)));
        }
        eq("an unrelated key is no decommission recipe", items.decommissionRecipeKind(items.recipeKey()), null);
        ok("an old Respawn Anchor jammer item is not decommissionable", items.liveKind(items.createJammer().withType(Material.RESPAWN_ANCHOR)) == null);

        // Placed blocks: marked in the chunk's own data, never part of the network.
        RetiredBlocks retired = new RetiredBlocks((RadioNetworkPlugin) radio, items);
        Block terminalBlock = blockAt(120, 120);
        terminalBlock.setType(Material.LODESTONE, false);
        Block jukeboxBlock = terminalBlock.getRelative(2, 0, 0);
        jukeboxBlock.setType(Material.JUKEBOX, false);
        Block repeaterBlock = terminalBlock.getRelative(4, 0, 0);
        repeaterBlock.setType(Material.LIGHTNING_ROD, false);
        Block jammerBlock = terminalBlock.getRelative(6, 0, 0);
        jammerBlock.setType(Jammers.BLOCK, false);
        List<Block> all = List.of(terminalBlock, jukeboxBlock, repeaterBlock, jammerBlock);
        ok("blocks start out as ordinary blocks", all.stream().noneMatch(retired::isRetired));
        all.forEach(retired::mark);
        ok("marked blocks are decommissioned", all.stream().allMatch(retired::isRetired));
        all.forEach(retired::mark);
        retired.unmark(terminalBlock);
        ok("unmarking one leaves the others", !retired.isRetired(terminalBlock) && retired.isRetired(jukeboxBlock)
                && retired.isRetired(repeaterBlock) && retired.isRetired(jammerBlock));
        ok("marking twice stores it once", !retired.isRetired(terminalBlock));
        jukeboxBlock.setType(Material.STONE, false);
        ok("a mark does not count once the block is something else", !retired.isRetired(jukeboxBlock));
        jukeboxBlock.setType(Material.JUKEBOX, false);
        retired.mark(terminalBlock);
        ok("the mark is stored in the chunk's own data", terminalBlock.getChunk().getPersistentDataContainer().getKeys().stream()
                .anyMatch(k -> k.getKey().equals("decommissioned_blocks")));
        all.forEach(retired::unmark);
        ok("all marks can be removed", all.stream().noneMatch(retired::isRetired));
        ok("and the chunk data is tidy again", terminalBlock.getChunk().getPersistentDataContainer().getKeys().stream()
                .noneMatch(k -> k.getKey().equals("decommissioned_blocks")));

        retired.mark(terminalBlock);
        retired.mark(jammerBlock);
        retired.mark(repeaterBlock);
        retired.mark(jukeboxBlock);
        ok("a decommissioned terminal is not registered", mgr.get(terminalBlock) == null);
        ok("a decommissioned jammer is not registered", jammers.get(jammerBlock) == null);
        ok("a decommissioned repeater is not registered", repeaters.get(repeaterBlock) == null);
        ok("a decommissioned jukebox is not registered", mgr.music().get(jukeboxBlock) == null);
        Lightable bulb = (Lightable) jammerBlock.getBlockData();
        bulb.setLit(true);
        jammerBlock.setBlockData(bulb, false);
        jammers.tick();
        jammers.tick();
        ok("the jammer tick leaves a decommissioned bulb alone", jammerBlock.getType() == Jammers.BLOCK
                && ((Lightable) jammerBlock.getBlockData()).isLit());
        all.forEach(retired::unmark);
        all.forEach(b -> b.setType(Material.AIR, false));
    }

    private void menuText() throws Exception {
        eq("a scan result 20 blocks away is close", new PortableReceivers.ScanResult("X", 1, 20, "N", 4).range(), "close");
        eq("32 blocks is still close", new PortableReceivers.ScanResult("X", 1, 32, "N", 4).range(), "close");
        eq("60 blocks is some way off", new PortableReceivers.ScanResult("X", 1, 60, "N", 4).range(), "some way off");
        eq("200 blocks is far", new PortableReceivers.ScanResult("X", 1, 200, "N", 4).range(), "far");
        ok("a scan result never gives the exact distance", !new PortableReceivers.ScanResult("X", 1, 37, "NE", 3).range().matches(".*\\d.*"));

        java.lang.reflect.Method lore = Class.forName("dev.radionetwork.RadioGui").getDeclaredMethod("channelLore", Radio.class, Frequency.class);
        lore.setAccessible(true);
        Radio mine = terminal(140, 140, "Alex", "PROBE-MENU", 600);
        Frequency own = mgr.frequencies().get("PROBE-MENU");
        String[] same = (String[]) lore.invoke(null, mine, own);
        eq("one owner line when the operator owns the frequency", same.length, 2);
        ok("and it says Owner", same[1].contains("Owner: ") && same[1].contains("Alex"));
        Radio theirs = terminal(144, 140, "Sam", "PROBE-MENU", 600);
        String[] split = (String[]) lore.invoke(null, theirs, own);
        eq("two lines when the operator and the owner differ", split.length, 3);
        ok("operator first, then the frequency owner", split[1].contains("Operator: ") && split[1].contains("Sam")
                && split[2].contains("Frequency owner: ") && split[2].contains("Alex"));
        String[] unclaimed = (String[]) lore.invoke(null, mine, null);
        ok("an unclaimed channel shows just the operator", unclaimed.length == 2 && unclaimed[1].contains("Operator: "));
        mgr.unregister(mine);
        mgr.unregister(theirs);
        mgr.frequencies().release(own);
    }

    private void itemChecks() {
        ItemStack terminal = items.create();
        ItemStack core = items.createCore();
        ItemStack receiver = items.createReceiver();
        ItemStack antenna = items.createAntenna();
        ItemStack earpiece = items.createEarpiece();
        ItemStack jukebox = items.createJukebox();
        ItemStack repeater = items.createRepeater();
        ItemStack jammer = items.createJammer();
        ItemStack scanner = items.createScanner();
        ItemStack cable = items.createCable(3);
        ItemStack blank = items.createBlankDisc(1);

        ok("terminal is recognised", items.isRadio(terminal));
        ok("core is recognised", items.isCore(core));
        ok("receiver is recognised", items.isReceiver(receiver));
        ok("antenna is recognised", items.isAntenna(antenna));
        ok("earpiece is recognised", items.isEarpiece(earpiece));
        ok("jukebox is recognised", items.isJukebox(jukebox));
        ok("repeater is recognised", items.isRepeater(repeater));
        ok("jammer is recognised", items.isJammer(jammer));
        ok("scanner is recognised", items.isScanner(scanner));
        ok("cable is recognised", items.isCable(cable));
        ok("blank disc is recognised", items.isBlankDisc(blank));
        eq("a stack of cables keeps its size", cable.getAmount(), 3);

        ok("a scanner is not a jammer", !items.isJammer(scanner));
        ok("a jammer is not a repeater", !items.isRepeater(jammer));
        ok("a repeater is not a scanner", !items.isScanner(repeater));
        ok("a terminal is not a receiver", !items.isReceiver(terminal));
        ok("plain items are nothing", !items.isRadio(new ItemStack(Material.LODESTONE)) && !items.isJammer(new ItemStack(Material.COPPER_BULB)));

        eq("jammer item is a waxed copper bulb", jammer.getType(), Material.WAXED_COPPER_BULB);
        ok("old Respawn Anchor jammer items still count", items.isJammer(jammer.withType(Material.RESPAWN_ANCHOR)));
        ok("jammer name is not bold", jammer.getItemMeta().displayName()
                .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD) != net.kyori.adventure.text.format.TextDecoration.State.TRUE);

        ItemStack tuned = items.withScannerChannel(scanner, "WX");
        eq("scanner remembers its channel", items.scannerChannel(tuned), "WX");
        ItemStack withAntenna = items.withAntenna(receiver, true);
        ok("receiver takes an antenna", items.hasAntenna(withAntenna) && !items.hasEarpiece(withAntenna));
        ok("receiver can have both parts", items.hasEarpiece(items.withEarpiece(withAntenna, true)));
        ok("a part can be removed again", !items.hasAntenna(items.withAntenna(withAntenna, false)));
        eq("receiver remembers its channel", items.receiverChannel(items.withChannel(receiver, "WX")), "WX");

        for (ItemStack stack : List.of(terminal, core, receiver, antenna, earpiece, jukebox, repeater, jammer, scanner, cable, blank)) {
            var meta = stack.getItemMeta();
            ok("has a name: " + stack.getType(), meta.displayName() != null);
        }
    }

    // -------------------------------------------------------------- frequencies

    private void frequencyChecks() {
        var reg = mgr.frequencies();
        Frequency f = reg.claim("PROBE-A", "Owner");
        ok("a claimed frequency can be found", reg.get("PROBE-A") == f);
        ok("owner has access", f.hasAccess("Owner"));
        ok("anyone has access to a public frequency", f.hasAccess("Stranger"));

        f.access(Frequency.Access.ENCRYPTED);
        f.setPassword("secret");
        ok("a stranger is locked out of an encrypted frequency", !f.hasAccess("Stranger"));
        ok("the owner still has access", f.hasAccess("Owner"));
        ok("the right password checks out", f.checkPassword("secret"));
        ok("a wrong password does not", !f.checkPassword("Secret"));
        f.grantMember("Stranger");
        ok("a member who joined has access", f.hasAccess("Stranger"));
        eq("access reason for a member", f.accessReason("Stranger"), "a member (joined with the password)");
        f.setPassword("new-secret");
        ok("a password change keeps trusted players but the member list is separate", f.isOwner("Owner"));

        Frequency g = reg.claim("PROBE-B", "Owner");
        g.speech(RadioMode.CLOSED);
        ok("closed: a stranger may listen but not speak", g.hasAccess("Stranger") && !g.canSpeak("Stranger"));
        g.trust("Friend");
        ok("closed: a trusted player may speak", g.canSpeak("Friend"));
        ok("closed: the owner may speak", g.canSpeak("Owner"));
        ok("untrust removes it again", g.untrust("Friend") && !g.canSpeak("Friend"));
        g.speech(RadioMode.OPEN);
        ok("open: anyone with access may speak", g.canSpeak("Stranger"));

        PasswordGuard guard = mgr.passwordGuard();
        UUID id = UUID.randomUUID();
        eq("no wait before any failure", guard.waitMillis(id), 0L);
        for (int i = 0; i < 12; i++) {
            guard.failure(id);
        }
        ok("repeated wrong passwords earn a wait", guard.waitMillis(id) > 0);
        guard.success(id);
        eq("a right password clears the wait", guard.waitMillis(id), 0L);

        reg.release(f);
        reg.release(g);
        ok("released frequencies are gone", reg.get("PROBE-A") == null && reg.get("PROBE-B") == null);
    }

    // ---------------------------------------------------- terminals / announce

    private void terminalChecks() {
        Radio a = terminal(40, 40, "Owner", "PROBE-T", 600);
        ok("a fuelled, tuned terminal is powered", a.isPowered());
        ok("and live", mgr.isLive(a));
        eq("redstone is the active fuel", a.activeFuel(), FuelType.REDSTONE);
        eq("fuel is stored as seconds", a.fuelLevel(), 600L);
        mgr.consume(a, 100);
        eq("burning fuel reduces it", a.fuelLevel(), 500L);

        Frequency freq = mgr.frequencies().get("PROBE-T");
        eq("a public channel does not announce who tunes in", mgr.announcesPresence(a), false);
        freq.access(Frequency.Access.ENCRYPTED);
        eq("an encrypted channel does", mgr.announcesPresence(a), true);
        freq.access(Frequency.Access.PUBLIC);

        Location at = a.center();
        int before = mgr.signs().size();
        ok("an announcement on the right channel is carried", mgr.announce(world, at.getX(), at.getY(), at.getZ(), "PROBE-T", "Hello"));
        ok("none on another channel", !mgr.announce(world, at.getX(), at.getY(), at.getZ(), "PROBE-X", "Hello"));
        ok("the owner may announce as themselves", mgr.announce(world, at.getX(), at.getY(), at.getZ(), "PROBE-T", "Hello", "Owner"));
        ok("an unknown channel refuses a named speaker", !mgr.announce(world, at.getX(), at.getY(), at.getZ(), "PROBE-NOPE", "Hello", "Owner"));
        freq.speech(RadioMode.CLOSED);
        ok("a closed channel refuses a stranger", !mgr.announce(world, at.getX(), at.getY(), at.getZ(), "PROBE-T", "Hello", "Stranger"));
        freq.trust("Friend");
        ok("but a trusted player may", mgr.announce(world, at.getX(), at.getY(), at.getZ(), "PROBE-T", "Hello", "Friend"));
        freq.speech(RadioMode.OPEN);
        ok("announcements are written to the sign log", mgr.signs().size() > before);

        radio.getConfig().set("announcements.search-radius", 24.0);
        ok("a terminal 20 blocks away is within a radius of 24", mgr.announce(world, at.getX() + 20, at.getY(), at.getZ(), "PROBE-T", "Hello"));
        ok("one 30 blocks away is not", !mgr.announce(world, at.getX() + 30, at.getY(), at.getZ(), "PROBE-T", "Hello"));
        radio.getConfig().set("announcements.search-radius", 128.0);
        ok("128 blocks reaches further", mgr.announce(world, at.getX() + 100, at.getY(), at.getZ(), "PROBE-T", "Hello"));
        ok("but not 200", !mgr.announce(world, at.getX() + 200, at.getY(), at.getZ(), "PROBE-T", "Hello"));

        mgr.consume(a, a.fuelLevel());
        ok("a terminal with no fuel is not powered", !a.isPowered());
        ok("and carries no announcements", !mgr.announce(world, at.getX(), at.getY(), at.getZ(), "PROBE-T", "Hello"));

        mgr.addFuel(a, FuelType.ENDER_PEARL, 300);
        eq("the better fuel becomes the active one", a.activeFuel(), FuelType.ENDER_PEARL);
        eq("ender pearls lift the radius cap", FuelType.ENDER_PEARL.maxRadius(), Integer.MAX_VALUE);
        ok("and allow other dimensions", FuelType.ENDER_PEARL.crossDimension() && !FuelType.GLOWSTONE.crossDimension());
        eq("redstone cap", FuelType.REDSTONE.maxRadius(), 250);
        eq("glowstone cap", FuelType.GLOWSTONE.maxRadius(), 500);

        mgr.unregister(a);
        a.block().setType(Material.AIR, false);
        mgr.frequencies().release(freq);
    }

    // ------------------------------------------------------------------- relays

    private void relayChecks() {
        Radio r1 = terminal(0, 100, "RelayOwner", "PROBE-R", 6000);
        double range = mgr.effectiveRadius(r1);
        ok("the terminal has a usable radius (" + range + ")", range > 50);
        int d2 = (int) (range * 0.55);
        int d3 = (int) (range * 1.5);
        Radio r2 = terminal(d2, 100, "RelayOwner", "PROBE-R", 6000);
        Radio r3 = terminal(d3, 100, "RelayOwner", "PROBE-R", 6000);

        ok("the far terminal is out of the first one's range", !mgr.reachOf(r1).towers().contains(r3));
        ok("the middle one is in range", mgr.reachOf(r1).towers().contains(r2));

        Repeaters.Repeater rep = repeaters.register(blockAt(d2 + 2, 100), "RelayOwner");
        repeaters.link(rep, r2);
        ok("a terminal with a linked repeater relays", mgr.isRelaying(r2));
        var reach = mgr.reachOf(r1);
        ok("so the far terminal now hears the first", reach.towers().contains(r3));
        eq("through one relay", reach.relays().get(r3), 1);
        eq("the middle one is reached directly", reach.relays().get(r2), 0);

        eq("a relay costs no bars by default", mgr.relayLoss(1), 0);
        radio.getConfig().set("relay.bars-per-hop", 2);
        eq("unless you charge for hops", mgr.relayLoss(3), 6);
        radio.getConfig().set("relay.bars-per-hop", 0);

        radio.getConfig().set("relay.max-hops", 0);
        ok("with hops set to 0 nothing is relayed", !mgr.reachOf(r1).towers().contains(r3));
        radio.getConfig().set("relay.max-hops", 3);
        ok("and it works again afterwards", mgr.reachOf(r1).towers().contains(r3));

        mgr.consume(r2, r2.fuelLevel());
        ok("a relay with no fuel breaks the chain", !mgr.reachOf(r1).towers().contains(r3));
        mgr.addFuel(r2, FuelType.REDSTONE, 6000);
        ok("refuelling restores it", mgr.reachOf(r1).towers().contains(r3));

        repeaters.sever(rep);
        ok("without the repeater there is no relay", !mgr.reachOf(r1).towers().contains(r3));

        repeaters.remove(rep);
        for (Radio r : List.of(r1, r2, r3)) {
            mgr.unregister(r);
            r.block().setType(Material.AIR, false);
        }
        mgr.frequencies().release(mgr.frequencies().get("PROBE-R"));
    }

    // ------------------------------------------------------------ jammer: block

    private void jammerBlock() throws Exception {
        Block b = blockAt(60, 60);
        b.setType(Material.RESPAWN_ANCHOR, false);
        Jammers.Jammer j = jammers.register(b, "Tester");
        jammers.tick();
        eq("an old Respawn Anchor jammer becomes the waxed bulb", b.getType(), Material.WAXED_COPPER_BULB);
        ok("and stays registered", jammers.get(b) != null);
        b.setType(Material.EXPOSED_COPPER_BULB, false);
        jammers.tick();
        eq("a scraped bulb is waxed again", b.getType(), Material.WAXED_COPPER_BULB);

        Object[][] cases = {
                {Jammers.State.IDLE, 0, false}, {Jammers.State.ACTIVE, 7, true}, {Jammers.State.COOLDOWN, 4, false},
                {Jammers.State.WARMUP, 2, true}, {Jammers.State.WARMUP, 3, false},
                {Jammers.State.COUNTDOWN, 4, true}, {Jammers.State.COUNTDOWN, 5, false}, {Jammers.State.BLOCKED, 0, false},
        };
        var light = Jammers.class.getDeclaredMethod("light", Jammers.Jammer.class, Block.class);
        light.setAccessible(true);
        for (Object[] c : cases) {
            setField(j, "state", c[0]);
            setField(j, "seconds", c[1]);
            light.invoke(jammers, j, b);
            ok("light in " + c[0] + " at " + c[1] + " is " + c[2], ((Lightable) b.getBlockData()).isLit() == (Boolean) c[2]);
        }
        jammers.remove(j);
        b.setType(Material.AIR, false);
    }

    // ------------------------------------------------------ jammer: life cycle

    private void jammerLife() throws Exception {
        Radio t = terminal(80, 160, "JamOwner", "PROBE-J", 100000);
        Block b = blockAt(84, 160);
        b.setType(Jammers.BLOCK, false);
        Jammers.Jammer j = jammers.register(b, "JamOwner");
        jammers.link(j, t);
        Location at = t.center();

        eq("no jam before it runs", jammers.lossAt(at), 0);
        jammers.tick();
        eq("it starts warming up", field(j, "state"), Jammers.State.WARMUP);
        for (int i = 0; i < 70; i++) {
            jammers.tick();
        }
        eq("after the warm-up it jams", field(j, "state"), Jammers.State.ACTIVE);
        int held = jammers.lossAt(at);
        ok("and costs the listener bars (" + held + ")", held > 0);
        ok("the status light is on", ((Lightable) b.getBlockData()).isLit());

        mgr.consume(t, t.fuelLevel());
        jammers.tick();
        eq("when the terminal runs dry it starts to fail", field(j, "state"), Jammers.State.COUNTDOWN);
        for (int i = 0; i < 6; i++) {
            jammers.tick();
        }
        eq("the jam holds through the countdown", jammers.lossAt(at), held);
        ok("the jammer is still there", jammers.get(b) != null && b.getType() == Jammers.BLOCK);
        for (int i = 0; i < 6; i++) {
            jammers.tick();
        }
        eq("it burns out and the block is gone", b.getType(), Material.AIR);
        ok("the jammer is unregistered", jammers.get(b) == null);
        eq("only then does the signal come back", jammers.lossAt(at), 0);

        // A lightning strike overheats a running jammer.
        mgr.addFuel(t, FuelType.REDSTONE, 100000);
        Block b2 = blockAt(84, 168);
        b2.setType(Jammers.BLOCK, false);
        Jammers.Jammer j2 = jammers.register(b2, "JamOwner");
        jammers.link(j2, t);
        for (int i = 0; i < 70; i++) {
            jammers.tick();
        }
        eq("a second jammer reaches full strength", field(j2, "state"), Jammers.State.ACTIVE);
        ok("lightning overheats it", jammers.overheat(t));
        eq("and it cools down", field(j2, "state"), Jammers.State.COOLDOWN);
        eq("with the jam lifted", jammers.lossAt(at), 0);

        jammers.remove(j2);
        b2.setType(Material.AIR, false);
        mgr.unregister(t);
        t.block().setType(Material.AIR, false);
        mgr.frequencies().release(mgr.frequencies().get("PROBE-J"));
    }

    // ------------------------------------------------------------ scanner numbers

    private void scannerNumbers() {
        Scanners s = mgr.scanners();
        eq("three codes to break", s.stages(), 3);
        eq("ten guesses per code", s.maxGuesses(), 10);
        near("guess cooldown at 4 bars", s.guessCooldown(4), 5.0, 1e-9);
        near("guess cooldown at 2 bars", s.guessCooldown(2), 10.0, 1e-9);
        near("guess cooldown at 1 bar", s.guessCooldown(1), 20.0, 1e-9);

        Frequency f = mgr.frequencies().claim("PROBE-S", "Owner");
        f.access(Frequency.Access.ENCRYPTED);
        f.setPassword("abcd");
        eq("a 4 character password makes a 4 colour code", s.slotsFor(f), 4);
        f.setPassword("abcdef");
        eq("6 characters make 5 colours", s.slotsFor(f), 5);
        f.setPassword("abcdefghijkl");
        eq("12 characters make 6 colours", s.slotsFor(f), 6);
        eq("nobody has progress on a fresh frequency", s.progressOn(f), 0);
        mgr.frequencies().release(f);
    }
}
