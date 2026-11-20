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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

public final class SeedServerPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private static final String WORLD_PREFIX = "seed-roll-";
    private static final String NEXT_PERMISSION = "seedserver.next";

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> seedByWorld = new LinkedHashMap<>();
    private final Deque<PreparedWorld> upcomingWorlds = new ArrayDeque<>();

    private File stateFile;
    private List<String> savedUpcomingWorldNames = List.of();
    private String savedActiveWorldName;
    private volatile World activeWorld;
    private int nextWorldId = 1;
    // Read and written only from the Folia global-region scheduler.
    private boolean switchingWorld;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        stateFile = new File(getDataFolder(), "state.yml");

        try {
            readState();
            if (getCommand("next") == null) {
                throw new IllegalStateException("Команда /next не объявлена в plugin.yml");
            }
            getCommand("next").setExecutor(this);
            getServer().getPluginManager().registerEvents(this, this);

            // World creation, queue changes, and persistence are serialized on Folia's global region.
            Bukkit.getGlobalRegionScheduler().run(this, task -> initializeWorlds());
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Не удалось запустить SeedServer", exception);
            getServer().getPluginManager().disablePlugin(this);
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

        // Player commands run on their entity's region; move shared state work to the global region.
        Bukkit.getGlobalRegionScheduler().run(this, task -> handleNext(sender));
        return true;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getGlobalRegionScheduler().run(this, task -> updatePlayerForActiveWorld(player, true, 20));
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (event.getNewGameMode() != GameMode.CREATIVE) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                    Component.text("На этом сервере доступен только креативный режим.", NamedTextColor.RED));
        }
    }

    private void initializeWorlds() {
        try {
            activeWorld = loadActiveWorld();
            seedByWorld.put(activeWorld.getName(), activeWorld.getSeed());

            loadSavedUpcomingWorlds();
            maintainWarmQueue();
            saveState();

            getLogger().info("Активный мир: " + activeWorld.getName() + ", seed=" + activeWorld.getSeed());
            for (Player player : Bukkit.getOnlinePlayers()) {
                updatePlayerForActiveWorld(player, true, 0);
            }
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Не удалось инициализировать миры SeedServer", exception);
            getServer().getPluginManager().disablePlugin(this);
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
            savedActiveWorldName = savedActiveWorld;
        }
        nextWorldId = Math.max(1, state.getInt("next-world-id", 1));
        savedUpcomingWorldNames = new ArrayList<>(state.getStringList("upcoming-worlds"));
    }

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
        prepared.chunks = buildChunkOrder(prepared.world.getSpawnLocation(), radius);

        // Global scheduler decides which chunk to prepare; the actual chunk access runs
        // through RegionScheduler so Folia executes it on the owning region.
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            RuntimeException failure = prepared.generationFailure.get();
            if (failure != null) {
                prepared.failed = true;
                task.cancel();
                getLogger().log(Level.SEVERE,
                        "Ошибка подготовки мира " + prepared.name + ", seed=" + prepared.seed,
                        failure);
                return;
            }

            for (int scheduled = 0;
                 scheduled < chunksPerTick && prepared.nextChunkIndex < prepared.chunks.size();
                 scheduled++) {
                ChunkPosition position = prepared.chunks.get(prepared.nextChunkIndex++);
                try {
                    Bukkit.getRegionScheduler().run(this, prepared.world, position.x, position.z, regionTask -> {
                        try {
                            prepared.world.getChunkAt(position.x, position.z);
                        } catch (RuntimeException exception) {
                            prepared.generationFailure.compareAndSet(null, exception);
                        } finally {
                            prepared.completedChunks.incrementAndGet();
                        }
                    });
                } catch (RuntimeException exception) {
                    prepared.generationFailure.compareAndSet(null, exception);
                    prepared.completedChunks.incrementAndGet();
                }
            }

            if (prepared.nextChunkIndex >= prepared.chunks.size()
                    && prepared.completedChunks.get() >= prepared.chunks.size()) {
                RuntimeException finalFailure = prepared.generationFailure.get();
                if (finalFailure != null) {
                    prepared.failed = true;
                    getLogger().log(Level.SEVERE,
                            "Ошибка подготовки мира " + prepared.name + ", seed=" + prepared.seed,
                            finalFailure);
                } else {
                    prepared.ready = true;
                    getLogger().info("Мир готов: " + prepared.name + ", seed=" + prepared.seed);
                }
                task.cancel();
            }
        }, 1L, 1L);
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

    private void handleNext(CommandSender sender) {
        if (switchingWorld) {
            reply(sender, "Смена мира уже выполняется.");
            return;
        }

        PreparedWorld next = upcomingWorlds.peekFirst();
        if (next == null) {
            reply(sender, "Следующий мир ещё не создан. Попробуйте немного позже.");
            return;
        }
        if (next.failed) {
            reply(sender, "Подготовка следующего мира завершилась ошибкой. Проверьте журнал сервера.");
            return;
        }
        if (!next.ready) {
            reply(sender, "Следующий мир ещё готовится. Повторите /next seed через несколько секунд.");
            return;
        }

        switchTo(next, sender);
    }

    private void switchTo(PreparedWorld next, CommandSender sender) {
        switchingWorld = true;
        try {
            upcomingWorlds.removeFirst();
            activeWorld = next.world;
            seedByWorld.put(activeWorld.getName(), activeWorld.getSeed());
            saveStateSafely();

            Location spawn = activeWorld.getSpawnLocation();
            Component message = seedMessage("Мир изменён. Сид: ", activeWorld.getSeed());
            for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
                teleportPlayer(player, activeWorld, spawn, message);
            }

            getLogger().info("Мир изменён на " + activeWorld.getName() + ", seed=" + activeWorld.getSeed());
            if (!(sender instanceof Player)) {
                sender.sendMessage("Мир изменён на " + activeWorld.getName() + ", seed=" + activeWorld.getSeed());
            }
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Не удалось переключить мир", exception);
            reply(sender, "Не удалось сменить мир. Проверьте журнал сервера.");
            return;
        } finally {
            switchingWorld = false;
        }

        try {
            maintainWarmQueue();
            saveStateSafely();
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Мир сменён, но не удалось подготовить очередь миров", exception);
        }
    }

    private void updatePlayerForActiveWorld(Player player, boolean announce, int retries) {
        World target = activeWorld;
        if (target == null) {
            if (retries > 0) {
                Bukkit.getGlobalRegionScheduler().runDelayed(
                        this,
                        task -> updatePlayerForActiveWorld(player, announce, retries - 1),
                        1L);
            }
            return;
        }

        Location spawn = target.getSpawnLocation();
        Component message = announce ? seedMessage("Текущий сид: ", target.getSeed()) : null;
        teleportPlayer(player, target, spawn, message);
    }

    private void teleportPlayer(Player player, World target, Location spawn, Component message) {
        player.getScheduler().run(this, task -> {
            if (!player.isOnline()) {
                return;
            }

            player.setGameMode(GameMode.CREATIVE);
            if (player.getWorld() != target) {
                player.teleportAsync(spawn.clone()).whenComplete((success, exception) -> {
                    if (exception != null) {
                        getLogger().log(Level.WARNING, "Не удалось переместить игрока " + player.getName(), exception);
                    } else if (!Boolean.TRUE.equals(success)) {
                        getLogger().warning("Телепорт игрока " + player.getName() + " был отклонён");
                    }
                });
            }
            if (message != null) {
                player.sendMessage(message);
            }
        }, () -> {
            // The player disconnected before the queued entity task could run.
        });
    }

    private void reply(CommandSender sender, String message) {
        if (sender instanceof Player player) {
            player.getScheduler().run(this, task -> player.sendMessage(message), () -> {
                // The sender disconnected before the response could be delivered.
            });
        } else {
            sender.sendMessage(message);
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

    private void saveStateSafely() {
        try {
            saveState();
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Не удалось сохранить состояние SeedServer", exception);
        }
    }

    private void saveState() {
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
        private final AtomicInteger completedChunks = new AtomicInteger();
        private final AtomicReference<RuntimeException> generationFailure = new AtomicReference<>();
        private List<ChunkPosition> chunks = List.of();
        private int nextChunkIndex;
        private volatile boolean ready;
        private volatile boolean failed;

        private PreparedWorld(String name, long seed, World world) {
            this.name = name;
            this.seed = seed;
            this.world = world;
        }
    }
}
