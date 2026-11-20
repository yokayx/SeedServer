package ru.seedserver;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public final class SeedServerPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private static final String WORLD_PREFIX = "seed-roll-";
    private static final String NEXT_PERMISSION = "seedserver.next";

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> seedByWorld = new LinkedHashMap<>();
    private final Deque<PreparedWorld> upcomingWorlds = new ArrayDeque<>();

    private File stateFile;
    private List<String> savedUpcomingWorldNames = List.of();
    private World activeWorld;
    private int nextWorldId = 1;
    private boolean switchingWorld;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        stateFile = new File(getDataFolder(), "state.yml");

        try {
            readState();
            activeWorld = loadActiveWorld();
            seedByWorld.put(activeWorld.getName(), activeWorld.getSeed());

            if (getCommand("next") == null) {
                throw new IllegalStateException("Команда /next не объявлена в plugin.yml");
            }
            getCommand("next").setExecutor(this);
            getServer().getPluginManager().registerEvents(this, this);

            loadSavedUpcomingWorlds();
            maintainWarmQueue();
            saveState();

            getLogger().info("Активный мир: " + activeWorld.getName() + ", seed=" + activeWorld.getSeed());
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Не удалось запустить SeedServer", exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (stateFile != null && activeWorld != null) {
            try {
                saveState();
            } catch (RuntimeException exception) {
                getLogger().log(Level.SEVERE, "Не удалось сохранить состояние SeedServer", exception);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("next")) {
            return false;
        }

        if (args.length != 1 || !args[0].equalsIgnoreCase("seed")) {
            sender.sendMessage("Использование: /next seed");
            return true;
        }

        if (!sender.hasPermission(NEXT_PERMISSION)) {
            sender.sendMessage("У вас нет прав для смены мира.");
            return true;
        }

        if (switchingWorld) {
            sender.sendMessage("Смена мира уже выполняется.");
            return true;
        }

        PreparedWorld next = upcomingWorlds.peekFirst();
        if (next == null) {
            sender.sendMessage("Следующий мир ещё не создан. Попробуйте немного позже.");
            return true;
        }
        if (next.failed) {
            sender.sendMessage("Подготовка следующего мира завершилась ошибкой. Проверьте журнал сервера.");
            return true;
        }
        if (!next.ready) {
            sender.sendMessage("Следующий мир ещё готовится. Повторите /next seed через несколько секунд.");
            return true;
        }

        switchTo(next, sender);
        return true;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline() || activeWorld == null) {
                return;
            }

            player.setGameMode(GameMode.CREATIVE);
            if (player.getWorld() != activeWorld) {
                player.teleport(activeWorld.getSpawnLocation());
            }
            player.sendMessage(seedMessage("Текущий сид: ", activeWorld.getSeed()));
        });
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (event.getNewGameMode() != GameMode.CREATIVE) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                    Component.text("На этом сервере доступен только креативный режим.", NamedTextColor.RED));
        }
    }

    private void readState() {
        FileConfiguration state = YamlConfiguration.loadConfiguration(stateFile);
        ConfigurationSection worlds = state.getConfigurationSection("world-seeds");
        if (worlds != null) {
            for (String worldName : worlds.getKeys(false)) {
                seedByWorld.put(worldName, worlds.getLong(worldName));
            }
        }

        String savedActiveWorld = state.getString("active-world");
        if (savedActiveWorld != null && !savedActiveWorld.isBlank()) {
            this.savedActiveWorldName = savedActiveWorld;
        }
        nextWorldId = Math.max(1, state.getInt("next-world-id", 1));
        savedUpcomingWorldNames = new ArrayList<>(state.getStringList("upcoming-worlds"));
    }

    private String savedActiveWorldName;

    private World loadActiveWorld() {
        if (savedActiveWorldName != null) {
            Long knownSeed = seedByWorld.get(savedActiveWorldName);
            World savedWorld = Bukkit.getWorld(savedActiveWorldName);
            if (savedWorld == null) {
                long seed = knownSeed == null ? createUniqueSeed() : knownSeed;
                savedWorld = Bukkit.createWorld(new WorldCreator(savedActiveWorldName)
                        .environment(World.Environment.NORMAL)
                        .seed(seed));
            }
            if (savedWorld != null) {
                return savedWorld;
            }
            getLogger().warning("Сохранённый активный мир не загрузился; используется основной мир сервера.");
        }

        return Bukkit.getWorlds().stream()
                .filter(world -> world.getEnvironment() == World.Environment.NORMAL)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Не найден основной мир (Overworld)"));
    }

    private void loadSavedUpcomingWorlds() {
        for (String worldName : savedUpcomingWorldNames) {
            if (worldName == null || worldName.isBlank() || worldName.equals(activeWorld.getName())) {
                continue;
            }
            if (upcomingWorlds.stream().anyMatch(world -> world.name.equals(worldName))) {
                continue;
            }

            Long knownSeed = seedByWorld.get(worldName);
            long seed = knownSeed == null ? createUniqueSeed() : knownSeed;
            PreparedWorld prepared = createPreparedWorld(worldName, seed);
            upcomingWorlds.addLast(prepared);
            startPreGeneration(prepared);
        }
    }

    private void maintainWarmQueue() {
        int targetCount = Math.max(1, Math.min(5, getConfig().getInt("warm-worlds", 2)));
        while (upcomingWorlds.size() < targetCount) {
            String worldName = nextWorldName();
            PreparedWorld prepared = createPreparedWorld(worldName, createUniqueSeed());
            upcomingWorlds.addLast(prepared);
            startPreGeneration(prepared);
            saveState();
        }
    }

    private String nextWorldName() {
        while (true) {
            String candidate = WORLD_PREFIX + String.format(Locale.ROOT, "%06d", nextWorldId++);
            if (!seedByWorld.containsKey(candidate) && Bukkit.getWorld(candidate) == null) {
                return candidate;
            }
        }
    }

    private PreparedWorld createPreparedWorld(String worldName, long preferredSeed) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            world = Bukkit.createWorld(new WorldCreator(worldName)
                    .environment(World.Environment.NORMAL)
                    .seed(preferredSeed));
        }
        if (world == null) {
            throw new IllegalStateException("Не удалось создать мир " + worldName);
        }

        long actualSeed = world.getSeed();
        seedByWorld.put(worldName, actualSeed);
        return new PreparedWorld(worldName, actualSeed, world);
    }

    private void startPreGeneration(PreparedWorld prepared) {
        int radius = Math.max(0, Math.min(32, getConfig().getInt("pregeneration-radius-chunks", 4)));
        int chunksPerTick = Math.max(1, Math.min(16, getConfig().getInt("chunks-per-tick", 1)));
        List<ChunkPosition> chunks = buildChunkOrder(prepared.world.getSpawnLocation(), radius);

        new BukkitRunnable() {
            private int cursor;

            @Override
            public void run() {
                try {
                    for (int generated = 0; generated < chunksPerTick && cursor < chunks.size(); generated++) {
                        ChunkPosition position = chunks.get(cursor++);
                        prepared.world.getChunkAt(position.x, position.z);
                        prepared.world.unloadChunk(position.x, position.z, true);
                    }
                } catch (RuntimeException exception) {
                    prepared.failed = true;
                    cancel();
                    getLogger().log(Level.SEVERE,
                            "Ошибка подготовки мира " + prepared.name + ", seed=" + prepared.seed,
                            exception);
                    return;
                }

                if (cursor >= chunks.size()) {
                    prepared.ready = true;
                    cancel();
                    getLogger().info("Мир готов: " + prepared.name + ", seed=" + prepared.seed);
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    private List<ChunkPosition> buildChunkOrder(Location spawn, int radius) {
        int centerX = spawn.getBlockX() >> 4;
        int centerZ = spawn.getBlockZ() >> 4;
        List<ChunkPosition> result = new ArrayList<>();
        result.add(new ChunkPosition(centerX, centerZ));

        for (int ring = 1; ring <= radius; ring++) {
            for (int offset = -ring; offset <= ring; offset++) {
                result.add(new ChunkPosition(centerX + offset, centerZ - ring));
                result.add(new ChunkPosition(centerX + offset, centerZ + ring));
            }
            for (int offset = -ring + 1; offset < ring; offset++) {
                result.add(new ChunkPosition(centerX - ring, centerZ + offset));
                result.add(new ChunkPosition(centerX + ring, centerZ + offset));
            }
        }
        return result;
    }

    private void switchTo(PreparedWorld next, CommandSender sender) {
        switchingWorld = true;
        try {
            upcomingWorlds.removeFirst();
            activeWorld = next.world;
            seedByWorld.put(activeWorld.getName(), activeWorld.getSeed());
            saveState();

            Location spawn = activeWorld.getSpawnLocation();
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.setGameMode(GameMode.CREATIVE);
                if (!player.teleport(spawn)) {
                    getLogger().warning("Не удалось переместить игрока " + player.getName() + " в " + activeWorld.getName());
                }
            }
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Не удалось переключить мир", exception);
            sender.sendMessage("Не удалось сменить мир. Проверьте журнал сервера.");
            return;
        } finally {
            switchingWorld = false;
        }

        Component message = seedMessage("Мир изменён. Сид: ", activeWorld.getSeed());
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(message);
        }
        getLogger().info("Мир изменён на " + activeWorld.getName() + ", seed=" + activeWorld.getSeed());
        if (!(sender instanceof Player)) {
            sender.sendMessage("Мир изменён на " + activeWorld.getName() + ", seed=" + activeWorld.getSeed());
        }

        try {
            maintainWarmQueue();
            saveState();
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Мир сменён, но не удалось подготовить очередь миров", exception);
        }
    }

    private Component seedMessage(String prefix, long seed) {
        String seedText = Long.toString(seed);
        Component clickableSeed = Component.text(seedText, NamedTextColor.AQUA)
                .clickEvent(ClickEvent.copyToClipboard(seedText))
                .hoverEvent(HoverEvent.showText(
                        Component.text("Нажмите, чтобы скопировать сид", NamedTextColor.YELLOW)));
        return Component.text(prefix, NamedTextColor.GRAY).append(clickableSeed);
    }

    private long createUniqueSeed() {
        while (true) {
            long candidate = random.nextLong();
            if (!seedByWorld.containsValue(candidate)) {
                return candidate;
            }
        }
    }

    private void saveState() {
        if (stateFile == null) {
            return;
        }

        YamlConfiguration state = new YamlConfiguration();
        if (activeWorld != null) {
            state.set("active-world", activeWorld.getName());
        }
        state.set("next-world-id", nextWorldId);
        state.set("upcoming-worlds", upcomingWorlds.stream().map(world -> world.name).toList());

        ConfigurationSection worlds = state.createSection("world-seeds");
        for (Map.Entry<String, Long> entry : seedByWorld.entrySet()) {
            worlds.set(entry.getKey(), entry.getValue());
        }

        try {
            File parent = stateFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Не удалось создать каталог " + parent);
            }
            state.save(stateFile);
        } catch (IOException exception) {
            throw new IllegalStateException("Не удалось записать " + stateFile, exception);
        }
    }

    private record ChunkPosition(int x, int z) {
    }

    private static final class PreparedWorld {
        private final String name;
        private final long seed;
        private final World world;
        private boolean ready;
        private boolean failed;

        private PreparedWorld(String name, long seed, World world) {
            this.name = name;
            this.seed = seed;
            this.world = world;
        }
    }
}
