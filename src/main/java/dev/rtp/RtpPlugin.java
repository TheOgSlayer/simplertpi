package dev.rtp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class RtpPlugin extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Economy economy;
    private final Map<UUID, Long> lastUse = new HashMap<>();
    private final Set<UUID> pending = new HashSet<>();

    /** Inventory holder so we can recognise our own menu. */
    private static final class Menu implements InventoryHolder {
        private final Inventory inv;
        private final Map<Integer, String> slots = new HashMap<>();

        Menu(Component title) {
            this.inv = Bukkit.createInventory(this, 27, title);
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inv;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().severe("No Vault economy provider found. Install an economy plugin (e.g. EssentialsX). Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        economy = rsp.getProvider();
        getServer().getPluginManager().registerEvents(this, this);
    }

    // ---------- messages ----------

    private Component parse(String raw, Map<String, String> placeholders) {
        TagResolver[] resolvers = placeholders.entrySet().stream()
                .map(e -> Placeholder.parsed(e.getKey(), e.getValue()))
                .toArray(TagResolver[]::new);
        return MM.deserialize(raw, resolvers);
    }

    private Component msg(String key, Map<String, String> placeholders) {
        return parse(getConfig().getString("messages." + key, key), placeholders);
    }

    private Component msg(String key) {
        return msg(key, Map.of());
    }

    // ---------- helpers ----------

    private World findWorld(ConfigurationSection sec) {
        String name = sec.getString("world", "");
        if (name != null && !name.isBlank()) {
            return Bukkit.getWorld(name);
        }
        World.Environment env;
        try {
            env = World.Environment.valueOf(sec.getString("environment", "NORMAL").toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == env) return w;
        }
        return null;
    }

    private int[] radii(ConfigurationSection sec, World world) {
        int min = Math.max(0, sec.getInt("min-radius", 500));
        int max = sec.getInt("max-radius", 5000);
        int borderLimit = (int) Math.floor(world.getWorldBorder().getSize() / 2) - 1;
        max = Math.max(1, Math.min(max, borderLimit));
        min = Math.min(min, max - 1);
        return new int[]{min, max};
    }

    private long cooldownRemaining(Player player) {
        if (player.hasPermission("rtp.bypass.cooldown")) return 0;
        long cooldownMs = getConfig().getLong("cooldown-seconds", 300) * 1000L;
        return lastUse.getOrDefault(player.getUniqueId(), 0L) + cooldownMs - System.currentTimeMillis();
    }

    private double costFor(Player player) {
        return player.hasPermission("rtp.bypass.cost") ? 0 : getConfig().getDouble("cost", 0);
    }

    private String costText(double cost) {
        return cost > 0 ? economy.format(cost) : "Free";
    }

    private static String formatTime(long ms) {
        long s = (ms + 999) / 1000;
        long m = s / 60;
        return m > 0 ? m + "m " + (s % 60) + "s" : s + "s";
    }

    // ---------- command ----------

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("rtp.reload")) {
                sender.sendMessage(msg("no-permission"));
                return true;
            }
            reloadConfig();
            sender.sendMessage(msg("reloaded"));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("player-only"));
            return true;
        }
        if (!player.hasPermission("rtp.use")) {
            player.sendMessage(msg("no-permission"));
            return true;
        }

        if (args.length > 0) {
            String key = args[0].toLowerCase();
            if (getConfig().isConfigurationSection("destinations." + key)) {
                teleport(player, key);
                return true;
            }
        }

        openMenu(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) return List.of();
        List<String> out = new ArrayList<>();
        ConfigurationSection dests = getConfig().getConfigurationSection("destinations");
        if (dests != null) out.addAll(dests.getKeys(false));
        if (sender.hasPermission("rtp.reload")) out.add("reload");
        String typed = args[0].toLowerCase();
        out.removeIf(s -> !s.startsWith(typed));
        return out;
    }

    // ---------- GUI ----------

    private void openMenu(Player player) {
        Menu menu = new Menu(parse(getConfig().getString("gui.title", "<dark_gray>Random Teleport"), Map.of()));
        Inventory inv = menu.getInventory();

        Material fillerMat = Material.matchMaterial(getConfig().getString("gui.filler", "GRAY_STAINED_GLASS_PANE"));
        if (fillerMat != null && !fillerMat.isAir()) {
            ItemStack filler = new ItemStack(fillerMat);
            ItemMeta fm = filler.getItemMeta();
            fm.displayName(Component.text(" "));
            filler.setItemMeta(fm);
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        }

        long remaining = cooldownRemaining(player);
        String cooldownText = remaining > 0 ? formatTime(remaining) : "Ready";
        String cost = costText(costFor(player));

        ConfigurationSection dests = getConfig().getConfigurationSection("destinations");
        if (dests != null) {
            for (String key : dests.getKeys(false)) {
                ConfigurationSection sec = dests.getConfigurationSection(key);
                if (sec == null || !sec.getBoolean("enabled", true)) continue;
                World world = findWorld(sec);
                if (world == null) continue;
                int slot = sec.getInt("slot", -1);
                if (slot < 0 || slot >= inv.getSize()) continue;

                int[] r = radii(sec, world);
                Map<String, String> ph = Map.of(
                        "min", String.valueOf(r[0]),
                        "max", String.valueOf(r[1]),
                        "cost", cost,
                        "cooldown", cooldownText);

                List<Component> lore = new ArrayList<>();
                for (String line : getConfig().getStringList("gui.lore")) lore.add(parse(line, ph));

                Material mat = Material.matchMaterial(sec.getString("icon", "STONE"));
                if (mat == null || mat.isAir()) mat = Material.STONE;
                ItemStack item = new ItemStack(mat);
                ItemMeta meta = item.getItemMeta();
                meta.displayName(parse("<!italic>" + sec.getString("display-name", key), Map.of()));
                meta.lore(lore);
                item.setItemMeta(meta);

                inv.setItem(slot, item);
                menu.slots.put(slot, key);
            }
        }

        player.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getInventory()) return;
        String key = menu.slots.get(e.getRawSlot());
        if (key == null) return;
        Player player = (Player) e.getWhoClicked();
        player.closeInventory();
        teleport(player, key);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    // ---------- teleport (no safe-location search) ----------

    private void teleport(Player player, String key) {
        if (!player.hasPermission("rtp.use")) {
            player.sendMessage(msg("no-permission"));
            return;
        }
        if (!player.hasPermission("rtp.world." + key)) {
            player.sendMessage(msg("world-no-permission"));
            return;
        }

        ConfigurationSection sec = getConfig().getConfigurationSection("destinations." + key);
        if (sec == null || !sec.getBoolean("enabled", true)) {
            player.sendMessage(msg("unavailable"));
            return;
        }
        World world = findWorld(sec);
        if (world == null) {
            player.sendMessage(msg("unavailable"));
            return;
        }

        UUID id = player.getUniqueId();
        if (pending.contains(id)) {
            player.sendMessage(msg("already-pending"));
            return;
        }

        long remaining = cooldownRemaining(player);
        if (remaining > 0) {
            player.sendMessage(msg("cooldown", Map.of("time", formatTime(remaining))));
            return;
        }

        double cost = costFor(player);
        if (cost > 0 && !economy.has(player, cost)) {
            player.sendMessage(msg("not-enough-money", Map.of("cost", costText(cost))));
            return;
        }

        int[] r = radii(sec, world);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double angle = rnd.nextDouble(0, Math.PI * 2);
        double radius = rnd.nextDouble(r[0], r[1]);
        Location center = world.getWorldBorder().getCenter();
        int x = (int) Math.floor(center.getX() + Math.cos(angle) * radius);
        int z = (int) Math.floor(center.getZ() + Math.sin(angle) * radius);
        float yaw = player.getLocation().getYaw();
        float pitch = player.getLocation().getPitch();
        String worldName = sec.getString("display-name", key);

        pending.add(id);
        player.sendMessage(msg("teleporting"));

        world.getChunkAtAsync(x >> 4, z >> 4).thenCompose(chunk -> {
            int y = world.getHighestBlockYAt(x, z) + 1;
            return player.teleportAsync(new Location(world, x + 0.5, y, z + 0.5, yaw, pitch));
        }).thenAccept(success -> {
            pending.remove(id);
            if (success) {
                if (cost > 0) economy.withdrawPlayer(player, cost);
                lastUse.put(id, System.currentTimeMillis());
                player.sendMessage(msg("success", Map.of("world", worldName, "cost", costText(cost))));
            } else {
                player.sendMessage(msg("failed"));
            }
        }).exceptionally(ex -> {
            pending.remove(id);
            player.sendMessage(msg("failed"));
            getLogger().warning("RTP failed: " + ex.getMessage());
            return null;
        });
    }
}
