package com.zeed.jaulas;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class CageManager {

    private record BlockKey(UUID world, int x, int y, int z) {}

    private record SavedBlock(int x, int y, int z, BlockData original) {}

    private static class ActiveCage {
        World world;
        Material material;
        List<SavedBlock> blocks = new ArrayList<>();
        BukkitTask task;
        File file;
    }

    private final ZeedJaulas plugin;
    private final NamespacedKey itemKey;
    private final File cagesDir;
    private final Map<UUID, ActiveCage> active = new HashMap<>();
    private final Set<BlockKey> protectedBlocks = new HashSet<>();
    private boolean particleWarned = false;

    public CageManager(ZeedJaulas plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "tipo_jaula");
        this.cagesDir = new File(plugin.getDataFolder(), "cages");
        if (!cagesDir.exists()) cagesDir.mkdirs();
    }

    // ------------------------------------------------------------ itens

    public Material blockMaterial(CageType type) {
        Material def = type == CageType.QUADRADA ? Material.RED_STAINED_GLASS : Material.GLASS;
        String name = plugin.getConfig().getString("jaulas." + type.key + ".bloco", def.name());
        Material m = Material.matchMaterial(name);
        if (m == null || !m.isBlock()) {
            plugin.getLogger().warning("Bloco invalido '" + name + "' para a jaula " + type.key + ". Usando " + def.name());
            return def;
        }
        return m;
    }

    public ItemStack createItem(CageType type) {
        ItemStack item = new ItemStack(blockMaterial(type));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ZeedJaulas.color(
                    plugin.getConfig().getString("jaulas." + type.key + ".nome-item", "&fJaula " + type.key)));
            List<String> lore = new ArrayList<>();
            for (String l : plugin.getConfig().getStringList("jaulas." + type.key + ".lore")) {
                lore.add(ZeedJaulas.color(l));
            }
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, type.key);
            item.setItemMeta(meta);
        }
        return item;
    }

    public CageType getType(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String v = meta.getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        return v == null ? null : CageType.fromKey(v);
    }

    // ------------------------------------------------------------ estado

    public boolean isCaged(Player p) {
        return active.containsKey(p.getUniqueId());
    }

    public boolean isProtected(Block b) {
        return isProtectedAt(b.getWorld(), b.getX(), b.getY(), b.getZ());
    }

    private boolean isProtectedAt(World w, int x, int y, int z) {
        if (protectedBlocks.isEmpty()) return false;
        return protectedBlocks.contains(new BlockKey(w.getUID(), x, y, z));
    }

    // ------------------------------------------------------------ formato

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static boolean inDisc(int dx, int dz, int r) {
        double lim = (r + 0.5) * (r + 0.5);
        return dx * dx + dz * dz <= lim;
    }

    private static boolean isEdge(int dx, int dz, int r) {
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                if (ox == 0 && oz == 0) continue;
                if (!inDisc(dx + ox, dz + oz, r)) return true;
            }
        }
        return false;
    }

    /** Offsets (dx, dy, dz) relativos ao bloco dos pes do jogador. */
    private List<int[]> shape(CageType type) {
        FileConfiguration cfg = plugin.getConfig();
        int defRadius = type == CageType.QUADRADA ? 2 : 3;
        int r = clamp(cfg.getInt("jaulas." + type.key + ".raio", defRadius), 1, 8);
        int h = clamp(cfg.getInt("jaulas." + type.key + ".altura", 3), 2, 10);

        List<int[]> list = new ArrayList<>();
        if (type == CageType.QUADRADA) {
            int e = r + 1;
            for (int dy = -1; dy <= h; dy++) {
                for (int dx = -e; dx <= e; dx++) {
                    for (int dz = -e; dz <= e; dz++) {
                        boolean cap = dy == -1 || dy == h;
                        if (cap || Math.abs(dx) == e || Math.abs(dz) == e) {
                            list.add(new int[]{dx, dy, dz});
                        }
                    }
                }
            }
        } else {
            int e = r + 1;
            for (int dy = -1; dy <= h; dy++) {
                boolean cap = dy == -1 || dy == h;
                for (int dx = -e; dx <= e; dx++) {
                    for (int dz = -e; dz <= e; dz++) {
                        if (!inDisc(dx, dz, r)) continue;
                        if (cap || isEdge(dx, dz, r)) {
                            list.add(new int[]{dx, dy, dz});
                        }
                    }
                }
            }
        }
        return list;
    }

    private boolean replaceable(Block b) {
        Material t = b.getType();
        if (t == Material.NETHER_PORTAL || t == Material.END_PORTAL || t == Material.END_GATEWAY) return false;
        if (t.isAir() || b.isLiquid()) return true;
        // Blocos sem colisao (grama, flores, tochas...) sao substituidos e depois restaurados.
        // Blocos com dados proprios (placas, banners...) nunca sao mexidos.
        return b.isPassable() && !(b.getState() instanceof TileState);
    }

    // ------------------------------------------------------------ aplicar / remover

    public boolean apply(Player user, Player target, CageType type) {
        FileConfiguration cfg = plugin.getConfig();
        UUID id = target.getUniqueId();

        if (active.containsKey(id)) {
            plugin.send(user, "alvo-ja-preso", "jogador", target.getName());
            return false;
        }

        World w = target.getWorld();
        Location loc = target.getLocation();
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();
        List<int[]> shape = shape(type);

        for (int[] o : shape) {
            if (isProtectedAt(w, bx + o[0], by + o[1], bz + o[2])) {
                plugin.send(user, "area-ocupada");
                return false;
            }
        }

        // Centraliza o alvo no bloco (antes de ficar marcado como preso)
        if (target.isInsideVehicle()) target.leaveVehicle();
        target.eject();
        target.teleport(new Location(w, bx + 0.5, loc.getY(), bz + 0.5, loc.getYaw(), loc.getPitch()));

        Material mat = blockMaterial(type);
        BlockData cageData = mat.createBlockData();
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight();

        ActiveCage ac = new ActiveCage();
        ac.world = w;
        ac.material = mat;

        for (int[] o : shape) {
            int x = bx + o[0];
            int y = by + o[1];
            int z = bz + o[2];
            if (y < minY || y >= maxY) continue;
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            Block b = w.getBlockAt(x, y, z);
            if (!replaceable(b)) continue;
            ac.blocks.add(new SavedBlock(x, y, z, b.getBlockData()));
            b.setBlockData(cageData, false);
            protectedBlocks.add(new BlockKey(w.getUID(), x, y, z));
        }

        ac.file = new File(cagesDir, id + ".yml");
        saveToDisk(ac);
        active.put(id, ac);

        particles(ac, "ao-aparecer", "END_ROD");

        int seconds = Math.max(1, cfg.getInt("duracao-segundos", 15));
        ac.task = Bukkit.getScheduler().runTaskLater(plugin, () -> end(id, true), seconds * 20L);

        plugin.send(user, "jaula-aplicada", "jogador", target.getName(), "segundos", String.valueOf(seconds));
        if (!user.getUniqueId().equals(id)) {
            plugin.send(target, "voce-foi-preso", "jogador", user.getName(), "segundos", String.valueOf(seconds));
        }
        return true;
    }

    public void end(UUID id, boolean notify) {
        ActiveCage ac = active.remove(id);
        if (ac == null) return;
        if (ac.task != null) ac.task.cancel();
        restore(ac);
        if (ac.file != null && ac.file.exists()) ac.file.delete();
        if (notify) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) plugin.send(p, "jaula-removida");
        }
    }

    public void removeAll() {
        for (UUID id : new ArrayList<>(active.keySet())) {
            end(id, false);
        }
    }

    private void restore(ActiveCage ac) {
        for (SavedBlock s : ac.blocks) {
            protectedBlocks.remove(new BlockKey(ac.world.getUID(), s.x(), s.y(), s.z()));
            Block b = ac.world.getBlockAt(s.x(), s.y(), s.z());
            // So restaura se o bloco ainda for o da jaula (nunca sobrescreve outra coisa)
            if (b.getType() == ac.material) {
                b.setBlockData(s.original(), false);
            }
        }
        particles(ac, "ao-desaparecer", "CLOUD");
    }

    // ------------------------------------------------------------ persistencia

    private void saveToDisk(ActiveCage ac) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("world", ac.world.getName());
        y.set("material", ac.material.name());
        List<String> lines = new ArrayList<>();
        for (SavedBlock s : ac.blocks) {
            lines.add(s.x() + "|" + s.y() + "|" + s.z() + "|" + s.original().getAsString());
        }
        y.set("blocos", lines);
        try {
            y.save(ac.file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Nao foi possivel salvar a jaula em disco: " + ex.getMessage());
        }
    }

    /** Restaura jaulas que ficaram no mapa se o servidor caiu ou foi fechado a forca. */
    public void recoverLeftovers() {
        File[] files = cagesDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return;
        int total = 0;
        for (File f : files) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            World w = Bukkit.getWorld(y.getString("world", ""));
            Material mat = Material.matchMaterial(y.getString("material", ""));
            if (w == null || mat == null) continue;
            for (String line : y.getStringList("blocos")) {
                String[] p = line.split("\\|", 4);
                if (p.length < 4) continue;
                try {
                    Block b = w.getBlockAt(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                    if (b.getType() == mat) {
                        b.setBlockData(Bukkit.createBlockData(p[3]), false);
                        total++;
                    }
                } catch (Exception ignored) {
                    // linha invalida, ignora
                }
            }
            f.delete();
        }
        if (total > 0) plugin.getLogger().info("Restaurados " + total + " blocos de jaulas antigas.");
    }

    // ------------------------------------------------------------ particulas

    private void particles(ActiveCage ac, String path, String def) {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("particulas.ativado", true)) return;
        try {
            Particle p;
            switch (cfg.getString("particulas." + path, def).toUpperCase()) {
                case "END_ROD" -> p = Particle.END_ROD;
                case "FLAME" -> p = Particle.FLAME;
                case "CRIT" -> p = Particle.CRIT;
                case "PORTAL" -> p = Particle.PORTAL;
                case "HEART" -> p = Particle.HEART;
                case "NOTE" -> p = Particle.NOTE;
                case "POOF" -> p = Particle.POOF;
                default -> p = Particle.CLOUD;
            }
            int count = clamp(cfg.getInt("particulas.quantidade", 2), 1, 10);
            for (SavedBlock s : ac.blocks) {
                ac.world.spawnParticle(p, s.x() + 0.5, s.y() + 0.5, s.z() + 0.5, count, 0.3, 0.3, 0.3, 0.0);
            }
        } catch (Throwable t) {
            if (!particleWarned) {
                particleWarned = true;
                plugin.getLogger().warning("Particulas indisponiveis nesta versao: " + t);
            }
        }
    }
}
