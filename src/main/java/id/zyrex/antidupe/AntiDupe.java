package id.zyrex.antidupe;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.Cancellable;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AntiDupe extends JavaPlugin implements Listener {

    private final Map<UUID, TransactionData> transactionData =
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

        getLogger().info("----------------------------------------");
        getLogger().info("AntiDupe v1.1 enabled");
        getLogger().info("Paper 26.2 / Java 25");
        getLogger().info("Event-driven protection enabled");
        getLogger().info("----------------------------------------");
    }

    @Override
    public void onDisable() {
        transactionData.clear();
        getLogger().info("AntiDupe disabled.");
    }

    // =========================================================
    // INVENTORY CLICK
    // =========================================================

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onInventoryClick(InventoryClickEvent event) {

        if (!enabled("protection.inventory.enabled")) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        if (enabled("protection.inventory.click")
                && isInvalidStack(current)) {

            detect(
                    player,
                    event,
                    "Invalid item stack in inventory click"
            );

            return;
        }

        if (isInvalidStack(cursor)) {

            detect(
                    player,
                    event,
                    "Invalid cursor stack"
            );

            return;
        }

        if (enabled("transaction.enabled")
                && isTransactionAnomaly(player)) {

            monitorTransaction(
                    player,
                    "Excessive inventory transactions"
            );
        }
    }

    // =========================================================
    // INVENTORY DRAG
    // =========================================================

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onInventoryDrag(InventoryDragEvent event) {

        if (!enabled("protection.inventory.drag")) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        ItemStack oldCursor = event.getOldCursor();

        if (isInvalidStack(oldCursor)) {

            detect(
                    player,
                    event,
                    "Invalid stack during inventory drag"
            );
        }
    }

    // =========================================================
    // HOPPER / INVENTORY MOVE
    // =========================================================

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onInventoryMove(InventoryMoveItemEvent event) {

        if (!enabled("hopper.enabled")) {
            return;
        }

        if (!enabled("hopper.monitor-transfers")) {
            return;
        }

        ItemStack item = event.getItem();

        if (isInvalidStack(item)) {

            event.setCancelled(true);

            logSystemDetection(
                    "Invalid stack detected during inventory transfer"
            );
        }

        if (enabled("item-consistency.container-transfer")) {

            if (containsInvalidStack(event.getSource())
                    || containsInvalidStack(event.getDestination())) {

                event.setCancelled(true);

                logSystemDetection(
                        "Invalid stack detected in container transfer"
                );
            }
        }
    }

    // =========================================================
    // HOPPER PICKUP
    // =========================================================

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onInventoryPickup(
            InventoryPickupItemEvent event
    ) {

        if (!enabled("hopper.enabled")) {
            return;
        }

        ItemStack item = event.getItem().getItemStack();

        if (isInvalidStack(item)) {

            event.setCancelled(true);

            logSystemDetection(
                    "Invalid stack detected during hopper pickup"
            );
        }
    }

    // =========================================================
    // PLAYER PICKUP
    // =========================================================

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onPlayerPickup(
            PlayerAttemptPickupItemEvent event
    ) {

        if (!enabled("protection.items.pickup")) {
            return;
        }

        Player player = event.getPlayer();

        ItemStack item =
                event.getItem().getItemStack();

        if (isInvalidStack(item)) {

            event.setCancelled(true);

            detect(
                    player,
                    null,
                    "Invalid dropped item stack"
            );
        }
    }

    // =========================================================
    // PLAYER DROP
    // =========================================================

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onPlayerDrop(PlayerDropItemEvent event) {

        if (!enabled("protection.items.drop")) {
            return;
        }

        ItemStack item =
                event.getItemDrop().getItemStack();

        if (isInvalidStack(item)) {

            event.setCancelled(true);

            detect(
                    event.getPlayer(),
                    event,
                    "Invalid dropped item stack"
            );
        }

        if (enabled("item-consistency.drop-pickup")) {

            if (containsInvalidContainer(item)) {

                event.setCancelled(true);

                detect(
                        event.getPlayer(),
                        event,
                        "Invalid container item"
                );
            }
        }
    }

    // =========================================================
    // ITEM VALIDATION
    // =========================================================

    private boolean isInvalidStack(ItemStack item) {

        if (item == null) {
            return false;
        }

        if (item.getType() == Material.AIR) {
            return false;
        }

        if (!enabled("item-consistency.enabled")) {
            return false;
        }

        if (!enabled(
                "item-consistency.invalid-stack-size"
        )) {
            return false;
        }

        int amount = item.getAmount();

        int max =
                item.getMaxStackSize();

        return amount <= 0 || amount > max;
    }

    // =========================================================
    // CONTAINER VALIDATION
    // =========================================================

    private boolean containsInvalidContainer(
            ItemStack item
    ) {

        if (item == null) {
            return false;
        }

        if (!(item.getItemMeta() instanceof BlockStateMeta meta)) {
            return false;
        }

        if (meta.getBlockState() instanceof org.bukkit.block.Container container) {

            Inventory inventory =
                    container.getInventory();

            return containsInvalidStack(inventory);
        }

        return false;
    }

    private boolean containsInvalidStack(
            Inventory inventory
    ) {

        if (inventory == null) {
            return false;
        }

        for (ItemStack item : inventory.getContents()) {

            if (isInvalidStack(item)) {
                return true;
            }
        }

        return false;
    }

    // =========================================================
    // TRANSACTION ANALYSIS
    // =========================================================

    private boolean isTransactionAnomaly(Player player) {

        long now =
                System.currentTimeMillis();

        TransactionData data =
                transactionData.computeIfAbsent(
                        player.getUniqueId(),
                        id -> new TransactionData(now)
                );

        long window =
                getConfig().getLong(
                        "transaction.window-ms",
                        1000L
                );

        if (now - data.windowStart > window) {

            if (data.transactions >
                    getConfig().getInt(
                            "transaction.max-per-window",
                            80
                    )) {

                data.suspiciousWindows++;
            } else {

                data.suspiciousWindows = 0;
            }

            data.windowStart = now;
            data.transactions = 0;
        }

        data.transactions++;

        return data.suspiciousWindows >=
                getConfig().getInt(
                        "transaction.suspicious-windows",
                        3
                );
    }

    private void monitorTransaction(
            Player player,
            String reason
    ) {

        /*
         * Transaction monitoring is intentionally monitor-only.
         *
         * Fast clicking alone must NOT result in item deletion,
         * kicking or inventory cancellation.
         */
        logDetection(
                player,
                reason
        );
    }

    // =========================================================
    // DETECTION
    // =========================================================

    private void detect(
            Player player,
            Cancellable event,
            String reason
    ) {

        if (player == null) {
            return;
        }

        if (event != null
                && getConfig().getBoolean(
                "actions.cancel",
                true)) {

            event.setCancelled(true);
        }

        logDetection(
                player,
                reason
        );

        notifyStaff(
                player,
                reason
        );

        notifyPlayer(
                player
        );

        if (getConfig().getBoolean(
                "actions.close-inventory",
                true)) {

            Bukkit.getScheduler().runTask(
                    this,
                    (Runnable) player::closeInventory
            );
        }

        if (getConfig().getBoolean(
                "actions.kick",
                false
        )) {

            String message =
                    stripColor(
                            color(
                                    getConfig().getString(
                                            "messages.player-detected",
                                            "&cSuspicious item duplication activity detected."
                                    )
                            )
                    );

            Bukkit.getScheduler().runTask(
                    this,
                    (Runnable) () ->
                            player.kick(
                                    Component.text(message)
                            )
            );
        }

        executeCommands(player);
    }

    // =========================================================
    // LOGGING
    // =========================================================

    private void logDetection(
            Player player,
            String reason
    ) {

        if (!getConfig().getBoolean(
                "logging.enabled",
                true
        )) {
            return;
        }

        TransactionData data =
                transactionData.computeIfAbsent(
                        player.getUniqueId(),
                        id -> new TransactionData(
                                System.currentTimeMillis()
                        )
                );

        long now =
                System.currentTimeMillis();

        if (now - data.lastNotification <
                notificationCooldown) {

            return;
        }

        data.lastNotification = now;

        if (!getConfig().getBoolean(
                "logging.console",
                true
        )) {
            return;
        }

        String location =
                player.getWorld().getName()
                        + " "
                        + player.getLocation().getBlockX()
                        + " "
                        + player.getLocation().getBlockY()
                        + " "
                        + player.getLocation().getBlockZ();

        getLogger().warning(
                "Detection: "
                        + player.getName()
                        + " | Reason: "
                        + reason
                        + " | Location: "
                        + location
        );
    }

    private void logSystemDetection(
            String reason
    ) {

        if (!getConfig().getBoolean(
                "logging.console",
                true
        )) {
            return;
        }

        getLogger().warning(
                "System detection: "
                        + reason
        );
    }

    // =========================================================
    // STAFF ALERT
    // =========================================================

    private void notifyStaff(
            Player player,
            String reason
    ) {

        if (!getConfig().getBoolean(
                "staff-alert.enabled",
                true
        )) {
            return;
        }

        String permission =
                getConfig().getString(
                        "staff-alert.permission",
                        "antidupe.admin"
                );

        StringBuilder message =
                new StringBuilder();

        message.append(
                getConfig().getString(
                        "messages.staff-alert",
                        "&c[AntiDupe] &f%player% &7triggered protection."
                ).replace(
                        "%player%",
                        player.getName()
                )
        );

        if (getConfig().getBoolean(
                "staff-alert.show-reason",
                true
        )) {

            message.append(
                    color(
                            " &8| &7Reason: &f"
                    )
            );

            message.append(reason);
        }

        if (getConfig().getBoolean(
                "staff-alert.show-world",
                true
        )) {

            message.append(
                    color(
                            " &8| &7World: &f"
                    )
            );

            message.append(
                    player.getWorld().getName()
            );
        }

        if (getConfig().getBoolean(
                "staff-alert.show-location",
                true
        )) {

            message.append(
                    color(
                            " &8| &7Location: &f"
                    )
            );

            message.append(
                    player.getLocation().getBlockX()
            );

            message.append(" ");

            message.append(
                    player.getLocation().getBlockY()
            );

            message.append(" ");

            message.append(
                    player.getLocation().getBlockZ()
            );
        }

        String finalMessage =
                color(message.toString());

        for (Player staff :
                Bukkit.getOnlinePlayers()) {

            if (staff.hasPermission(permission)) {

                staff.sendMessage(
                        Component.text(
                                stripColor(finalMessage)
                        )
                );
            }
        }
    }

    // =========================================================
    // PLAYER MESSAGE
    // =========================================================

    private void notifyPlayer(
            Player player
    ) {

        if (!getConfig().getBoolean(
                "actions.message-player",
                true
        )) {
            return;
        }

        String message =
                getConfig().getString(
                        "messages.player-detected",
                        "&cSuspicious item duplication activity detected."
                );

        player.sendMessage(
                Component.text(
                        stripColor(
                                color(message)
                        )
                )
        );
    }

    // =========================================================
    // COMMANDS
    // =========================================================

    private void executeCommands(
            Player player
    ) {

        for (String command :
                getConfig().getStringList(
                        "actions.commands"
                )) {

            String parsed =
                    command.replace(
                            "%player%",
                            player.getName()
                    );

            Bukkit.getScheduler().runTask(
                    this,
                    (Runnable) () ->
                            Bukkit.dispatchCommand(
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

        if (!sender.hasPermission(
                "antidupe.admin"
        )) {

            sender.sendMessage(
                    Component.text(
                            stripColor(
                                    color(
                                            getConfig().getString(
                                                    "messages.no-permission",
                                                    "&cYou don't have permission."
                                            )
                                    )
                            )
                    )
            );

            return true;
        }

        if (args.length == 0) {

            sender.sendMessage(
                    Component.text(
                            "/antidupe reload"
                    )
            );

            sender.sendMessage(
                    Component.text(
                            "/antidupe status"
                    )
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
                        Component.text(
                                "AntiDupe configuration reloaded."
                        )
                );
            }

            case "status" -> {

                boolean enabled =
                        pluginEnabled();

                sender.sendMessage(
                        Component.text(
                                enabled
                                        ? "AntiDupe is enabled."
                                        : "AntiDupe is disabled."
                        )
                );
            }

            default -> sender.sendMessage(
                    Component.text(
                            "Usage: /antidupe <reload|status>"
                    )
            );
        }

        return true;
    }

    // =========================================================
    // CONFIG
    // =========================================================

    private boolean enabled(
            String path
    ) {

        if (!pluginEnabled()) {
            return false;
        }

        return getConfig().getBoolean(
                path,
                true
        );
    }

    private boolean pluginEnabled() {

        return getConfig().getBoolean(
                "settings.enabled",
                true
        );
    }

    // =========================================================
    // TEXT
    // =========================================================

    private String color(
            String text
    ) {

        if (text == null) {
            return "";
        }

        return ChatColor.translateAlternateColorCodes(
                '&',
                text
        );
    }

    private String stripColor(
            String text
    ) {

        if (text == null) {
            return "";
        }

        return ChatColor.stripColor(text);
    }

    // =========================================================
    // TRANSACTION DATA
    // =========================================================

    private static final class TransactionData {

        private long windowStart;
        private long lastNotification;
        private int transactions;
        private int suspiciousWindows;

        private TransactionData(
                long now
        ) {

            this.windowStart = now;
            this.lastNotification = 0L;
            this.transactions = 0;
            this.suspiciousWindows = 0;
        }
    }
        }
