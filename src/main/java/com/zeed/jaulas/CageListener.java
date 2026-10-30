package com.zeed.jaulas;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class CageListener implements Listener {

    private final ZeedJaulas plugin;
    private final CageManager manager;

    public CageListener(ZeedJaulas plugin, CageManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    // Qualquer jogador (com ou sem OP) pode usar: clique direito em outro jogador
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUse(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!(e.getRightClicked() instanceof Player target)) return;

        Player user = e.getPlayer();
        ItemStack item = user.getInventory().getItemInMainHand();
        CageType type = manager.getType(item);
        if (type == null) return;

        e.setCancelled(true);
        if (!manager.apply(user, target, type)) return;

        boolean consume = plugin.getConfig().getBoolean("consumir-item", true);
        if (consume && user.getGameMode() != GameMode.CREATIVE) {
            if (item.getAmount() <= 1) {
                user.getInventory().setItemInMainHand(null);
            } else {
                ItemStack rest = item.clone();
                rest.setAmount(item.getAmount() - 1);
                user.getInventory().setItemInMainHand(rest);
            }
        }
    }

    // O item da jaula nao pode ser colocado como bloco
    @EventHandler(ignoreCancelled = true)
    public void onPlaceItem(BlockPlaceEvent e) {
        if (manager.getType(e.getItemInHand()) != null) {
            e.setCancelled(true);
        }
    }

    // ... nem usado em receitas de craft
    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (manager.getType(it) != null) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    // ------------------------------------------------ protecao dos blocos da jaula

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (manager.isProtected(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(manager::isProtected);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(manager::isProtected);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (manager.isProtected(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (manager.isProtected(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (manager.isProtected(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    // ------------------------------------------------ jogador preso

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!plugin.getConfig().getBoolean("bloquear-teleporte", true)) return;
        Player p = e.getPlayer();
        if (!manager.isCaged(p)) return;

        Location to = e.getTo();
        if (to != null && to.getWorld() == e.getFrom().getWorld()
                && to.distanceSquared(e.getFrom()) < 1.0) {
            return;
        }
        e.setCancelled(true);
        plugin.send(p, "teleporte-bloqueado");
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (plugin.getConfig().getBoolean("remover-ao-morrer", true)) {
            manager.end(e.getEntity().getUniqueId(), false);
        }
    }
}
