package dev.rtp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class RtpPlugin extends JavaPlugin {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Economy economy;
    private final Map<UUID, Long> lastUse = new HashMap<>();
    private final Set<UUID> pending = new HashSet<>();

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
    }

    private Component msg(String key, Map<String, String> placeholders) {
        String raw = getConfig().getString("messages." + key, key);
        TagResolver[] resolvers = placeholders.entrySet().stream()
                .map(e -> Placeholder.unparsed(e.getKey(), e.getValue()))
                .toArray(TagResolver[]::new);
        return MM.deserialize(raw, resolvers);
    }

    private Component msg(String key) {
        return msg(key, Map.of());
    }

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

        World world = player.getWorld();
        if (getConfig().getStringList("blacklisted-worlds").contains(world.getName())) {
            player.sendMessage(msg("blacklisted-world"));
            return true;
        }

        UUID id = player.getUniqueId();
        if (pending.contains(id)) {
            player.sendMessage(msg("already-pending"));
            return true;
        }

        long cooldownMs = getConfig().getLong("cooldown-seconds", 300) * 1000L;
        if (!player.hasPermission("rtp.bypass.cooldown")) {
            long remaining = lastUse.getOrDefault(id, 0L) + cooldownMs - System.currentTimeMillis();
            if (remaining > 0) {
                player.sendMessage(msg("cooldown", Map.of("time", formatTime(remaining))));
                return true;
            }
        }

        double cost = player.hasPermission("rtp.bypass.cost") ? 0 : getConfig().getDouble("cost", 0);
        if (cost > 0 && !economy.has(player, cost)) {
            player.sendMessage(msg("not-enough-money", Map.of("cost", String.valueOf(cost))));
            return true;
        }

        int min = getConfig().getInt("min-radius", 500);
        int max = Math.max(min + 1, getConfig().getInt("max-radius", 5000));
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double angle = rnd.nextDouble(0, Math.PI * 2);
        double radius = rnd.nextDouble(min, max);
        Location spawn = world.getSpawnLocation();
        int x = (int) Math.floor(spawn.getX() + Math.cos(angle) * radius);
        int z = (int) Math.floor(spawn.getZ() + Math.sin(angle) * radius);

        pending.add(id);
        player.sendMessage(msg("teleporting"));

        world.getChunkAtAsync(x >> 4, z >> 4).thenCompose(chunk -> {
            int y = world.getHighestBlockYAt(x, z) + 1;
            Location target = new Location(world, x + 0.5, y, z + 0.5,
                    player.getLocation().getYaw(), player.getLocation().getPitch());
            return player.teleportAsync(target);
        }).thenAccept(success -> {
            pending.remove(id);
            if (success) {
                if (cost > 0) economy.withdrawPlayer(player, cost);
                lastUse.put(id, System.currentTimeMillis());
                player.sendMessage(msg("success", Map.of("cost", String.valueOf(cost))));
            } else {
                player.sendMessage(msg("failed"));
            }
        }).exceptionally(ex -> {
            pending.remove(id);
            player.sendMessage(msg("failed"));
            getLogger().warning("RTP failed: " + ex.getMessage());
            return null;
        });

        return true;
    }

    private static String formatTime(long ms) {
        long s = (ms + 999) / 1000;
        long m = s / 60;
        return m > 0 ? m + "m " + (s % 60) + "s" : s + "s";
    }
}
