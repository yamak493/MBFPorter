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
import org.bukkit.entity.Wither;
import org.bukkit.entity.Warden;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.ElderGuardian;
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

    private Location getTeleportLocation(int scrollType) {
        switch (scrollType) {
            case 2037: // メインワールド
                return new Location(Bukkit.getWorld("world"), 3905, 77, -3520);
            case 2038: // メインネザー
                return new Location(Bukkit.getWorld("world_nether"), 23, 83, 86);
            case 2039: // メインエンド
                return new Location(Bukkit.getWorld("world_the_end"), 9, 59, -55);
            case 2040: // TTワールド
                return new Location(Bukkit.getWorld("tt_world"), -59, 64, -43);
            case 2041: // TTネザー
                return new Location(Bukkit.getWorld("tt_nether"), 47, 67, -70);
            case 2042: // TTエンド
                return new Location(Bukkit.getWorld("tt_end"), 92, 60, -1);
            default:
                return new Location(Bukkit.getWorld("world"), 4949, 68, -1149);
        }
    }

    private boolean isTeleportItem(ItemStack item) {
        if (item == null || item.getType() != Material.ENCHANTED_BOOK) return false;
        if (!item.hasItemMeta()) return false;

        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer container = meta.getPersistentDataContainer();
        return container.has(key, PersistentDataType.INTEGER);
    }

    private int getScrollType(ItemStack item) {
        if (!isTeleportItem(item)) return 0;
        
        ItemMeta meta = item.getItemMeta();
        return meta.getCustomModelData();
    }

    private String getScrollTypeName(int scrollType) {
        switch (scrollType) {
            case 2037:
                return "メインワールド";
            case 2038:
                return "メインネザー";
            case 2039:
                return "メインエンド";
            case 2040:
                return "TTワールド";
            case 2041:
                return "TTネザー";
            case 2042:
                return "TTエンド";
            default:
                return "メインワールド";
        }
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

    private boolean isForbiddenMob(Entity entity) {
        // ウィザー、ウォーデン、エンダードラゴン、エルダーガーディアンは転送禁止
        return entity instanceof Wither || 
               entity instanceof Warden ||
                entity instanceof ElderGuardian ||
                entity instanceof EnderDragon;
    }

    @EventHandler
    public void onEntityRightClick(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        Entity target = event.getRightClicked();
        ItemStack item = player.getInventory().getItemInMainHand();

        if (!isTeleportItem(item)) return;
        if (!(target instanceof Mob)) return;

        // 転送禁止MOBのチェック
        if (isForbiddenMob(target)) {
            player.sendMessage(ChatColor.RED + "このMOBは転送できません。");
            return;
        }

        int scrollType = getScrollType(item);
        String scrollName = getScrollTypeName(scrollType);

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
                Location dest = getTeleportLocation(scrollType);
                target.teleportAsync(dest).thenAccept(success -> {
                    if (success) {
                        // 最終処理をプレイヤーのリージョンで実行
                        player.getScheduler().run(this, finalTask -> {
                            // エフェクトとサウンド
                            player.getWorld().spawnParticle(Particle.PORTAL, target.getLocation(), 30, 0.5, 1, 0.5, 0.1);
                            player.getWorld().playSound(target.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0f, 1.0f);

                            // 通知とアイテム削除
                            player.sendMessage(ChatColor.GREEN+"Mobを" + scrollName + "に転送しました！");
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

    public ItemStack createTeleportWand(int customModelData, String displayName) {
        ItemStack item = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(displayName);
        meta.setCustomModelData(customModelData);
        meta.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, 1);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createTeleportWand() {
        return createTeleportWand(2037, "転送スクロール（メインワールド）");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("getwand")) {
            if (sender instanceof Player player) {
                // コマンド実行はプレイヤーのリージョンスケジューラで行う
                player.getScheduler().run(this, task -> {
                    // 引数がある場合はそのタイプのスクロールを、ない場合はメインワールド用を付与
                    if (args.length > 0) {
                        String scrollType = args[0].toLowerCase();
                        ItemStack scroll = null;
                        
                        switch (scrollType) {
                            case "main":
                            case "world":
                                scroll = createTeleportWand(2037, "転送スクロール（メインワールド）");
                                break;
                            case "nether":
                                scroll = createTeleportWand(2038, "転送スクロール（メインネザー）");
                                break;
                            case "end":
                                scroll = createTeleportWand(2039, "転送スクロール（メインエンド）");
                                break;
                            case "tt":
                            case "tt_world":
                                scroll = createTeleportWand(2040, "転送スクロール（TTワールド）");
                                break;
                            case "tt_nether":
                                scroll = createTeleportWand(2041, "転送スクロール（TTネザー）");
                                break;
                            case "tt_end":
                                scroll = createTeleportWand(2042, "転送スクロール（TTエンド）");
                                break;
                            case "all":
                                // 全てのスクロールを付与
                                player.getInventory().addItem(createTeleportWand(2037, "転送スクロール（メインワールド）"));
                                player.getInventory().addItem(createTeleportWand(2038, "転送スクロール（メインネザー）"));
                                player.getInventory().addItem(createTeleportWand(2039, "転送スクロール（メインエンド）"));
                                player.getInventory().addItem(createTeleportWand(2040, "転送スクロール（TTワールド）"));
                                player.getInventory().addItem(createTeleportWand(2041, "転送スクロール（TTネザー）"));
                                player.getInventory().addItem(createTeleportWand(2042, "転送スクロール（TTエンド）"));
                                player.sendMessage(ChatColor.GREEN+"全ての転送スクロールを付与しました！");
                                return;
                            default:
                                player.sendMessage(ChatColor.RED+"無効なスクロールタイプです。");
                                player.sendMessage(ChatColor.YELLOW+"使用方法: /getwand [main|nether|end|tt|ttnether|ttend|all]");
                                return;
                        }
                        
                        if (scroll != null) {
                            player.getInventory().addItem(scroll);
                            player.sendMessage(ChatColor.GREEN+"転送スクロールを付与しました！");
                        }
                    } else {
                        // 引数がない場合はメインワールド用を付与
                        player.getInventory().addItem(createTeleportWand());
                        player.sendMessage(ChatColor.GREEN+"転送スクロール（メインワールド）を付与しました！");
                    }
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
