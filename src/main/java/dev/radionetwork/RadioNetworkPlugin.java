package dev.radionetwork;

import dev.radionetwork.voice.RadioVoicePlugin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;

public final class RadioNetworkPlugin extends JavaPlugin {

    private RadioManager manager;
    private RadioItem radioItem;
    private PortableReceivers portables;
    private MusicBoxes music;
    private Repeaters repeaters;
    private Jammers jammers;
    private Scanners scanners;
    private Weather weather;
    private SongLibrary songs;
    private SongUploads songUploads;
    private AnnouncementSigns announcementSigns;
    private RetiredBlocks retired;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        radioItem = new RadioItem(this);
        radioItem.registerRecipes();

        manager = new RadioManager(this);
        manager.load();
        manager.all().forEach(manager::updateLight);

        portables = new PortableReceivers(this, manager, radioItem);
        manager.portables(portables);
        getServer().getPluginManager().registerEvents(new RadioListener(this, manager, radioItem), this);
        retired = new RetiredBlocks(this, radioItem);
        getServer().getPluginManager().registerEvents(retired, this);
        getServer().getPluginManager().registerEvents(new ReceiverListener(this, manager, radioItem, portables), this);
        getServer().getScheduler().runTaskTimer(this, portables::tick, 20L, 20L);

        announcementSigns = new AnnouncementSigns(new java.io.File(getDataFolder(), "announcement-signs.yml"), getLogger());
        announcementSigns.load();
        manager.signs(announcementSigns);
        songs = new SongLibrary(this);
        songs.load();
        music = new MusicBoxes(this, manager, radioItem, songs);
        music.load();
        manager.music(music);
        repeaters = new Repeaters(this, manager, music, radioItem);
        repeaters.load();
        manager.repeaters(repeaters);
        getServer().getPluginManager().registerEvents(new RepeaterListener(this, manager, radioItem, repeaters), this);
        getServer().getScheduler().runTaskTimer(this, repeaters::tick, 20L, 20L);
        jammers = new Jammers(this, manager, music, radioItem, portables);
        jammers.load();
        manager.jammers(jammers);
        getServer().getPluginManager().registerEvents(new JammerListener(this, manager, radioItem, jammers), this);
        getServer().getScheduler().runTaskTimer(this, () -> jammers.tick(), 20L, 20L);
        scanners = new Scanners(this, manager, radioItem, portables);
        scanners.load();
        manager.scanners(scanners);
        getServer().getPluginManager().registerEvents(new ScannerListener(this, manager, radioItem, scanners), this);
        getServer().getScheduler().runTaskTimer(this, scanners::tick, 20L, 20L);
        weather = new Weather(this, manager, portables);
        manager.weather(weather);
        getServer().getPluginManager().registerEvents(weather, this);
        getServer().getScheduler().runTaskTimer(this, weather::tick, 20L, 20L);
        songUploads = new SongUploads(this, songs, radioItem);
        getServer().getPluginManager().registerEvents(new MusicListener(this, manager, radioItem, music, songUploads), this);
        getServer().getScheduler().runTaskTimer(this, music::tick, 20L, 20L);
        // Note-block songs are played note by note, so they are driven every game tick.
        getServer().getScheduler().runTaskTimer(this, () -> music.songTick(System.currentTimeMillis()), 1L, 1L);

        var broadcast = getCommand("broadcast");
        if (broadcast != null) {
            broadcast.setExecutor(new BroadcastCommand(manager));
        }

        FrequencyCommand frequencyHandler = new FrequencyCommand(manager);
        var radio = getCommand("radio");
        if (radio != null) {
            RadioCommand radioHandler = new RadioCommand(manager, frequencyHandler, radioItem, songs, songUploads);
            radio.setExecutor(radioHandler);
            radio.setTabCompleter(radioHandler);
        }

        new HopperFeeder(manager).runTaskTimer(this, HopperFeeder.PERIOD_TICKS, HopperFeeder.PERIOD_TICKS);
        // Autosave plus cleanup of registrations whose Lodestone was removed behind our back.
        getServer().getScheduler().runTaskTimer(this, () -> {
            manager.pruneMissingBlocks();
            manager.saveIfDirty();
            announcementSigns.saveIfDirty();
        }, 600L, 600L);
        // Fuel burns with time: once a second, working radios lose fuel.
        getServer().getScheduler().runTaskTimer(this, manager::drainFuel, 20L, 20L);
        // Abandoned-frequency sweep (every 5 minutes).
        getServer().getScheduler().runTaskTimer(this, this::sweepFrequencies, 6000L, 6000L);

        if (getServer().getPluginManager().getPlugin("voicechat") != null) {
            RadioVoicePlugin.register(this, manager, songs);
        } else {
            getLogger().info("Simple Voice Chat not found - running in text-only mode.");
        }
        getLogger().info("Loaded " + manager.all().size() + " radio terminal(s).");
    }

    private void sweepFrequencies() {
        long emptyHours = getConfig().getLong("frequencies.abandon-empty-hours", 24);
        long inactiveDays = getConfig().getLong("frequencies.abandon-inactive-days", 30);
        var freed = manager.frequencies().sweep(manager.all(), getServer().getOnlinePlayers(),
                System.currentTimeMillis(), emptyHours * 3_600_000L, inactiveDays * 86_400_000L);
        for (Frequency frequency : freed) {
            getLogger().info("Frequency '" + frequency.name() + "' (owner " + frequency.owner()
                    + ") was abandoned and has been released.");
        }
        manager.saveIfDirty();
    }

    @Override
    public void onDisable() {
        // Closing terminals first lets pending fuel in the drop-zone be consumed before saving.
        for (Player player : getServer().getOnlinePlayers()) {
            InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
            if (holder instanceof RadioGui) {
                player.closeInventory();
            }
        }
        if (getServer().getPluginManager().getPlugin("voicechat") != null) {
            RadioVoicePlugin.shutdown();
        }
        if (portables != null) {
            portables.clear();
        }
        if (music != null) {
            music.shutdown();
        }
        if (repeaters != null) {
            repeaters.save();
        }
        if (jammers != null) {
            jammers.save();
        }
        if (scanners != null) {
            scanners.save();
        }
        if (announcementSigns != null) {
            announcementSigns.saveIfDirty();
        }
        if (weather != null) {
            weather.clear();
        }
        if (radioItem != null) {
            radioItem.unregisterRecipes();
        }
        if (manager != null) {
            manager.save();
        }
    }
}
