package net.enabify.mBFPorter;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class MBFPorter extends JavaPlugin implements Listener {

    private NamespacedKey key;

    @Override
    public void onEnable() {
        key = new NamespacedKey(this, "teleport_wand");
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("MBFPorter has been enabled with Folia support.");
    }

    private Location getTeleportLocation() {
        return new Location(Bukkit.getWorld("world"), 4949, 68, -1149);
    }

    private boolean isTeleportItem(ItemStack item) {
        if (item == null || item.getType() != Material.ENCHANTED_BOOK) return false;
        if (!item.hasItemMeta()) return false;

        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer container = meta.getPersistentDataContainer();
        return container.has(key, PersistentDataType.INTEGER);
    }

    private boolean canPlayerTeleportMobHere(Player player, Location mobLocation) {
        try {
            // WorldGuardチェックは同期的に実行する必要があるため
            // 現在の実装を維持するが、Foliaではこの部分は注意が必要
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionQuery query = container.createQuery();
            return query.testState(BukkitAdapter.adapt(mobLocation), WorldGuardPlugin.inst().wrapPlayer(player), Flags.BUILD);
        } catch (Exception e) {
            getLogger().warning("WorldGuard チェック中に例外が発生: " + e.getMessage());
            return false; // 保守的に拒否
        }
    }

    @EventHandler
    public void onEntityRightClick(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        Entity target = event.getRightClicked();
        ItemStack item = player.getInventory().getItemInMainHand();

        if (!isTeleportItem(item)) return;
        if (!(target instanceof Mob)) return;

        // 非同期で実行する必要があるため、プレイヤーのリージョンスケジューラを使用
        player.getScheduler().run(this, scheduledTask -> {
            // WorldGuard 保護チェック
            if (!canPlayerTeleportMobHere(player, target.getLocation())) {
                player.sendMessage(ChatColor.RED+"土地保護されたエリアにいるMOBには、転送スクロールは使用できません。");
                return;
            }

            // エンティティの処理はそのエンティティのホームリージョンで行う
            target.getScheduler().run(this, entityTask -> {
                // テレポート処理 - teleport()の代わりにteleportAsync()を使用
                Location dest = getTeleportLocation();
                target.teleportAsync(dest).thenAccept(success -> {
                    if (success) {
                        // 最終処理をプレイヤーのリージョンで実行
                        player.getScheduler().run(this, finalTask -> {
                            // エフェクトとサウンド
                            player.getWorld().spawnParticle(Particle.PORTAL, target.getLocation(), 30, 0.5, 1, 0.5, 0.1);
                            player.getWorld().playSound(target.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0f, 1.0f);

                            // 通知とアイテム削除
                            player.sendMessage(ChatColor.GREEN+"Mobに転送スクロールを使用しました！ ");
                            item.setAmount(item.getAmount() - 1);
                        }, () -> {
                            // エラー処理
                            player.sendMessage(ChatColor.RED+"転送処理中にエラーが発生しました。");
                        });
                    } else {
                        player.sendMessage(ChatColor.RED+"MOBのテレポートに失敗しました。");
                    }
                });
            }, () -> {
                // エラー処理
                player.sendMessage(ChatColor.RED+"MOBの転送中にエラーが発生しました。");
            });
        }, () -> {
            // エラー処理
            player.sendMessage(ChatColor.RED+"プレイヤーの処理中にエラーが発生しました。");
        });
    }

    public ItemStack createTeleportWand() {
        ItemStack item = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("転送スクロール");
        meta.setCustomModelData(2037);
        meta.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, 1);
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("getwand")) {
            if (sender instanceof Player player) {
                // コマンド実行はプレイヤーのリージョンスケジューラで行う
                player.getScheduler().run(this, task -> {
                    player.getInventory().addItem(createTeleportWand());
                    player.sendMessage(ChatColor.GREEN+"転送スクロールを付与しました！");
                }, () -> {
                    // エラー処理
                    player.sendMessage(ChatColor.RED+"アイテムの付与に失敗しました。");
                });
                return true;
            } else {
                sender.sendMessage("このコマンドはプレイヤーのみ使用できます。");
                return true;
            }
        }
        return false;
    }
}
