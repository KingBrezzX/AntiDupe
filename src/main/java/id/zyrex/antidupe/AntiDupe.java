package id.zyrex.antidupe;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AntiDupe extends JavaPlugin implements Listener {

    private final Map<UUID, TransactionData> transactions =
            new ConcurrentHashMap<>();

    private long notificationCooldown;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        notificationCooldown = getConfig().getLong(
                "settings.notification-cooldown-ms",
                3000L
        );

        Bukkit.getPluginManager().registerEvents(this, this);

        getLogger().info("AntiDupe enabled.");
        getLogger().info("Paper 26.2 / Java 25");
    }

    @Override
    public void onDisable() {
        transactions.clear();
        getLogger().info("AntiDupe disabled.");
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onInventoryClick(InventoryClickEvent event) {
        if (!isEnabled()) {
            return;
        }

        if (!getConfig().getBoolean(
                "protection.inventory-click", true)) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        if (hasImpossibleAmount(current)
                || hasImpossibleAmount(cursor)) {

            block(player, event, "Impossible item stack");
            return;
        }

        if (isTransactionSpamming(player)) {
            block(player, event, "Inventory transaction spam");
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!isEnabled()) {
            return;
        }

        if (!getConfig().getBoolean(
                "protection.inventory-drag", true)) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        ItemStack oldCursor = event.getOldCursor();

        if (hasImpossibleAmount(oldCursor)
                || isTransactionSpamming(player)) {

            block(player, event, "Suspicious inventory drag");
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onHopperMove(InventoryMoveItemEvent event) {
        if (!isEnabled()) {
            return;
        }

        if (!getConfig().getBoolean(
                "protection.hopper", true)) {
            return;
        }

        ItemStack item = event.getItem();

        if (hasImpossibleAmount(item)) {
            event.setCancelled(true);

            getLogger().warning(
                    "Blocked impossible item stack moved by inventory."
            );
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onHopperPickup(InventoryPickupItemEvent event) {
        if (!isEnabled()) {
            return;
        }

        if (!getConfig().getBoolean(
                "protection.hopper", true)) {
            return;
        }

        ItemStack item = event.getItem().getItemStack();

        if (hasImpossibleAmount(item)) {
            event.setCancelled(true);

            getLogger().warning(
                    "Blocked impossible item stack picked up by inventory."
            );
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onPlayerPickup(PlayerAttemptPickupItemEvent event) {
        if (!isEnabled()) {
            return;
        }

        if (!getConfig().getBoolean(
                "protection.item-pickup", true)) {
            return;
        }

        ItemStack item = event.getItem().getItemStack();

        if (hasImpossibleAmount(item)) {
            event.setCancelled(true);

            handleDetection(
                    event.getPlayer(),
                    "Impossible pickup stack"
            );
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onPlayerDrop(PlayerDropItemEvent event) {
        if (!isEnabled()) {
            return;
        }

        if (!getConfig().getBoolean(
                "protection.item-drop", true)) {
            return;
        }

        ItemStack item = event.getItemDrop().getItemStack();

        if (hasImpossibleAmount(item)) {
            event.setCancelled(true);

            handleDetection(
                    event.getPlayer(),
                    "Impossible dropped stack"
            );
        }
    }

    private boolean hasImpossibleAmount(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }

        if (!getConfig().getBoolean(
                "detection.impossible-stack-size", true)) {
            return false;
        }

        int amount = item.getAmount();
        int maximum = item.getMaxStackSize();

        return amount <= 0 || amount > maximum;
    }

    private boolean isTransactionSpamming(Player player) {
        if (!getConfig().getBoolean(
                "detection.transaction-spam", true)) {
            return false;
        }

        long now = System.currentTimeMillis();

        TransactionData data = transactions.computeIfAbsent(
                player.getUniqueId(),
                uuid -> new TransactionData(now)
        );

        long window = getConfig().getLong(
                "detection.transaction-window-ms",
                1000L
        );

        if (now - data.windowStart > window) {
            data.windowStart = now;
            data.transactions = 0;
        }

        data.transactions++;

        return data.transactions >
                getConfig().getInt(
                        "detection.max-transactions",
                        80
                );
    }

    private void block(
            Player player,
            org.bukkit.event.Cancellable event,
            String reason
    ) {
        if (getConfig().getBoolean(
                "settings.cancel-suspicious-actions",
                true)) {

            event.setCancelled(true);
        }

        handleDetection(player, reason);
    }

    private void handleDetection(
            Player player,
            String reason
    ) {
        if (player == null) {
            return;
        }

        long now = System.currentTimeMillis();

        TransactionData data = transactions.computeIfAbsent(
                player.getUniqueId(),
                uuid -> new TransactionData(now)
        );

        if (now - data.lastNotification <
                notificationCooldown) {
            return;
        }

        data.lastNotification = now;

        String name = player.getName();

        if (getConfig().getBoolean(
                "settings.console-log",
                true)) {

            getLogger().warning(
                    "Possible duplication activity detected: "
                            + name
                            + " | "
                            + reason
            );
        }

        if (getConfig().getBoolean(
                "settings.staff-notification",
                true)) {

            String message = color(
                    getConfig().getString(
                            "messages.staff-alert",
                            "&c%player% &7triggered AntiDupe protection."
                    )
            ).replace("%player%", name);

            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("antidupe.admin")) {
                    staff.sendMessage(message);
                }
            }
        }

        if (getConfig().getBoolean(
                "actions.message-player",
                true)) {

            player.sendMessage(
                    color(
                            getConfig().getString(
                                    "messages.player-detected",
                                    "&cSuspicious inventory activity detected."
                            )
                    )
            );
        }

        if (getConfig().getBoolean(
                "actions.close-inventory",
                true)) {

            Bukkit.getScheduler().runTask(
                    this,
                    player::closeInventory
            );
        }

        if (getConfig().getBoolean(
                "actions.kick",
                false)) {

            Bukkit.getScheduler().runTask(
                    this,
                    () -> player.kick(
                            color(
                                    getConfig().getString(
                                            "messages.player-detected",
                                            "&cSuspicious inventory activity detected."
                                    )
                            )
                    )
            );
        }

        for (String command :
                getConfig().getStringList(
                        "actions.commands")) {

            String parsed = command.replace(
                    "%player%",
                    name
            );

            Bukkit.getScheduler().runTask(
                    this,
                    () -> Bukkit.dispatchCommand(
                            Bukkit.getConsoleSender(),
                            parsed
                    )
            );
        }
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!sender.hasPermission("antidupe.admin")) {
            sender.sendMessage(
                    color(
                            getConfig().getString(
                                    "messages.no-permission",
                                    "&cYou don't have permission."
                            )
                    )
            );

            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(
                    color("&b/antidupe reload &7- Reload configuration")
            );

            sender.sendMessage(
                    color("&b/antidupe status &7- Show status")
            );

            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                reloadConfig();

                notificationCooldown =
                        getConfig().getLong(
                                "settings.notification-cooldown-ms",
                                3000L
                        );

                sender.sendMessage(
                        color(
                                getConfig().getString(
                                        "messages.reloaded",
                                        "&aAntiDupe configuration reloaded."
                                )
                        )
                );
            }

            case "status" -> {
                boolean enabled =
                        getConfig().getBoolean(
                                "settings.enabled",
                                true
                        );

                sender.sendMessage(
                        color(
                                enabled
                                        ? "&aAntiDupe is enabled."
                                        : "&cAntiDupe is disabled."
                        )
                );
            }

            default ->
                    sender.sendMessage(
                            color("&cUsage: /antidupe <reload|status>")
                    );
        }

        return true;
    }

    private boolean isEnabled() {
        return getConfig().getBoolean(
                "settings.enabled",
                true
        );
    }

    private String color(String text) {
        if (text == null) {
            return "";
        }

        return ChatColor.translateAlternateColorCodes(
                '&',
                text
        );
    }

    private static final class TransactionData {

        private long windowStart;
        private long lastNotification;
        private int transactions;

        private TransactionData(long now) {
            this.windowStart = now;
            this.lastNotification = 0L;
            this.transactions = 0;
        }
    }
      }
