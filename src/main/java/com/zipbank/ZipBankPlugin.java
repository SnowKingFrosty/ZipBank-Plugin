package com.zipbank;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.File;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class ZipBankPlugin extends JavaPlugin implements Listener {
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
    private final Map<UUID, CashSelection> cashSelections = new HashMap<>();
    private BankStore store;
    private Economy economy;
    private NamespacedKey cardKey;
    private Material cardMaterial;
    private Component cardName;
    private Integer cardModelData;
    private CashItems cashItems;
    private ShopCheckout shops;

    private enum Transfer { DEPOSIT, WITHDRAW }
    private enum PromptType { CHECKOUT_ID_PENDING, CHECKOUT_ID, SHOP_ID_PENDING, SHOP_ID, SHOP_READER, NEW_CARD_PIN, EXISTING_CARD_PIN }
    private record Prompt(PromptType type, String checkoutLocation, UUID card) { }
    private record Payment(String location, UUID card, BankStore.Terminal terminal) { }
    private static final class CashSelection {
        private final String location;
        private final String token;
        private final Map<Long, Integer> counts = new HashMap<>();
        private long total;

        private CashSelection(String location, String token) {
            this.location = location;
            this.token = token;
        }
    }

    private static final class AtmHolder implements InventoryHolder {
        private Inventory inventory;
        private boolean busy;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class WalletHolder implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;
        private boolean busy;

        private WalletHolder(UUID owner) {
            this.owner = owner;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class KeypadHolder implements InventoryHolder {
        private final Transfer transfer;
        private final Payment payment;
        private final boolean cash;
        private final KeypadInput input;
        private Inventory inventory;
        private boolean submitted;

        private KeypadHolder(Transfer transfer, Payment payment, boolean cash) {
            this.transfer = transfer;
            this.payment = payment;
            this.cash = cash;
            this.input = new KeypadInput(payment != null);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class HistoryHolder implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;

        private HistoryHolder(UUID owner) {
            this.owner = owner;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    @Override
    public void onEnable() {
        RegisteredServiceProvider<Economy> provider = getServer().getServicesManager().getRegistration(Economy.class);
        if (provider == null) {
            getLogger().severe("A Vault-compatible economy provider is required.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        economy = provider.getProvider();
        cardKey = new NamespacedKey(this, "card-id");
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().severe("Could not create the plugin data folder.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        String materialName = getConfig().getString("debit-card.material", "PAPER");
        cardMaterial = Material.matchMaterial(materialName);
        if (cardMaterial == null || !cardMaterial.isItem() || cardMaterial.isAir()) {
            getLogger().severe("Invalid debit-card.material: " + materialName);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        cardName = LegacyComponentSerializer.legacyAmpersand().deserialize(
                getConfig().getString("debit-card.name", "ZipBank Debit Card"));
        Object modelData = getConfig().get("debit-card.custom-model-data");
        if (modelData != null && (!(modelData instanceof Integer) || (Integer) modelData < 0)) {
            getLogger().severe("debit-card.custom-model-data must be a non-negative integer or null.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        cardModelData = (Integer) modelData;
        try {
            cashItems = new CashItems(this);
        } catch (IllegalArgumentException exception) {
            getLogger().severe("Invalid cash configuration: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            store = new BankStore("jdbc:sqlite:" + new File(getDataFolder(), "zipbank.db").getAbsolutePath());
            shops = new ShopCheckout(this, store);
        } catch (SQLException exception) {
            getLogger().severe("Could not open bank database: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(shops, this);
    }

    @Override
    public void onDisable() {
        if (store != null) {
            try {
                store.close();
            } catch (SQLException exception) {
                getLogger().severe("Could not close bank database: " + exception.getMessage());
            }
        }
    }

    static String text(Component component) {
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
    }

    static String location(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    void error(Player player, Exception exception) {
        getLogger().severe("Bank operation failed: " + exception.getMessage());
        player.sendMessage(ChatColor.RED + "The bank could not complete that operation. Contact an administrator.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        if (event.getSide() != Side.FRONT) {
            return;
        }
        Player player = event.getPlayer();
        try {
            if (store.terminal(location(event.getBlock())) != null || store.shop(location(event.getBlock())) != null) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "Break the checkout sign to remove it.");
                return;
            }
            String header = text(event.line(0)).trim();
            if (header.equalsIgnoreCase("[ZipATM]")) {
                if (!player.hasPermission("zipbank.atm.create")) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.RED + "You cannot create ATM signs.");
                    return;
                }
                event.line(0, Component.text("[ZipATM]", NamedTextColor.GOLD, TextDecoration.BOLD));
                player.sendMessage(ChatColor.GREEN + "ATM created.");
            } else if (header.equalsIgnoreCase("[ZipShop]")) {
                if (!player.hasPermission("zipbank.pay.create") || store.balance(player.getUniqueId()) == null) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.RED + "You need checkout creation permission and a bank account.");
                } else {
                    Prompt prompt = new Prompt(PromptType.SHOP_ID_PENDING, location(event.getBlock()), null);
                    if (prompts.putIfAbsent(player.getUniqueId(), prompt) != null) {
                        event.setCancelled(true);
                        player.sendMessage(ChatColor.RED + "Finish or cancel your current chat prompt first.");
                        return;
                    }
                    event.line(0, Component.text("[ZipShop]", NamedTextColor.DARK_GREEN, TextDecoration.BOLD));
                    event.line(1, Component.text("Scan items", NamedTextColor.DARK_BLUE));
                    event.line(2, Component.empty());
                    event.line(3, Component.empty());
                    getServer().getScheduler().runTask(this, () -> {
                        if (event.isCancelled() || !(event.getBlock().getState() instanceof Sign sign)
                                || !text(sign.getSide(Side.FRONT).line(0)).equals("[ZipShop]")) {
                            prompts.remove(player.getUniqueId(), prompt);
                            return;
                        }
                        try {
                            if (!store.registerShop(prompt.checkoutLocation(), player.getUniqueId())) {
                                prompts.remove(player.getUniqueId(), prompt);
                                player.sendMessage(ChatColor.RED + "Could not register this shop sign.");
                            } else if (player.isOnline()) {
                                shops.giveDisplay(player, prompt.checkoutLocation());
                                if (prompts.replace(player.getUniqueId(), prompt,
                                        new Prompt(PromptType.SHOP_ID, prompt.checkoutLocation(), null))) {
                                    player.sendMessage(ChatColor.YELLOW + "Enter a shop checkout ID in chat, or type cancel.");
                                }
                            }
                        } catch (SQLException exception) {
                            prompts.remove(player.getUniqueId(), prompt);
                            error(player, exception);
                        }
                    });
                }
            } else if (header.equalsIgnoreCase("[ZipPay]")) {
                if (!player.hasPermission("zipbank.pay.create")) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.RED + "You cannot create checkout signs.");
                } else if (store.balance(player.getUniqueId()) == null) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.RED + "Create a bank account at an ATM first.");
                } else if (prompts.containsKey(player.getUniqueId())) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.RED + "Finish or cancel your current chat prompt first.");
                } else {
                    Prompt checkoutPrompt = new Prompt(PromptType.CHECKOUT_ID_PENDING, location(event.getBlock()), null);
                    if (prompts.putIfAbsent(player.getUniqueId(), checkoutPrompt) != null) {
                        event.setCancelled(true);
                        player.sendMessage(ChatColor.RED + "Finish or cancel your current chat prompt first.");
                        return;
                    }
                    event.line(0, Component.text("[ZipPay]", NamedTextColor.DARK_AQUA, TextDecoration.BOLD));
                    event.line(1, Component.text("Tap Card Here", NamedTextColor.DARK_BLUE));
                    event.line(2, Component.empty());
                    event.line(3, Component.empty());
                    getServer().getScheduler().runTask(this, () -> {
                        if (event.isCancelled() || !(event.getBlock().getState() instanceof Sign sign)
                                || !text(sign.getSide(Side.FRONT).line(0)).equals("[ZipPay]")) {
                            prompts.remove(player.getUniqueId(), checkoutPrompt);
                            return;
                        }
                        try {
                            if (store.registerTerminal(location(event.getBlock()), player.getUniqueId())) {
                                Prompt readyPrompt = new Prompt(PromptType.CHECKOUT_ID, checkoutPrompt.checkoutLocation(), null);
                                if (player.isOnline() && prompts.replace(player.getUniqueId(), checkoutPrompt, readyPrompt)) {
                                    player.sendMessage(ChatColor.YELLOW + "Enter a checkout ID in chat (letters, numbers, _ or -, up to 16 characters), or type cancel.");
                                }
                            } else {
                                prompts.remove(player.getUniqueId(), checkoutPrompt);
                                player.sendMessage(ChatColor.RED + "Could not register this checkout sign.");
                            }
                        } catch (SQLException exception) {
                            prompts.remove(player.getUniqueId(), checkoutPrompt);
                            error(player, exception);
                        }
                    });
                }
            }
        } catch (SQLException exception) {
            event.setCancelled(true);
            error(player, exception);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        try {
            BankStore.Terminal terminal = store.terminal(location(event.getBlock()));
            BankStore.Shop shop = store.shop(location(event.getBlock()));
            UUID owner = terminal != null ? terminal.owner() : shop != null ? shop.owner() : null;
            if (owner != null && !owner.equals(event.getPlayer().getUniqueId())
                    && !event.getPlayer().hasPermission("zipbank.admin")) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(ChatColor.RED + "Only the checkout owner can break this sign.");
            }
        } catch (SQLException exception) {
            event.setCancelled(true);
            error(event.getPlayer(), exception);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBroken(BlockBreakEvent event) {
        removeTerminal(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplosion(EntityExplodeEvent event) {
        event.blockList().forEach(this::removeTerminal);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplosion(BlockExplodeEvent event) {
        event.blockList().forEach(this::removeTerminal);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        if (!(event.getBlock().getState() instanceof Sign)) {
            return;
        }
        Block block = event.getBlock();
        getServer().getScheduler().runTask(this, () -> {
            if (!(block.getState() instanceof Sign)) {
                removeTerminal(block);
            }
        });
    }

    private void removeTerminal(Block block) {
        try {
            store.removeTerminal(location(block));
            store.removeShop(location(block));
            shops.remove(location(block));
        } catch (SQLException exception) {
            getLogger().severe("Could not remove checkout: " + exception.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || !(event.getClickedBlock().getState() instanceof Sign sign)) {
            return;
        }
        Block block = event.getClickedBlock();
        Player player = event.getPlayer();
        try {
            BankStore.Terminal terminal = store.terminal(location(block));
            if (terminal != null && !text(sign.getSide(Side.FRONT).line(0)).equals("[ZipPay]")) {
                store.removeTerminal(location(block));
                terminal = null;
            }
            BankStore.Shop shop = store.shop(location(block));
            if (shop != null) {
                event.setCancelled(true);
                shops.scan(player, block, sign, shop, event.getItem());
            } else if (terminal != null) {
                event.setCancelled(true);
                long cashValue = cashItems.value(event.getItem());
                if (cashValue != 0) {
                    payCash(player, block, sign, terminal, cashValue);
                } else {
                    tap(player, event.getItem(), block, sign, terminal);
                }
            } else if (text(sign.getSide(Side.FRONT).line(0)).equalsIgnoreCase("[ZipATM]")) {
                event.setCancelled(true);
                openAtm(player);
            }
        } catch (SQLException exception) {
            error(player, exception);
        }
    }

    private void openAtm(Player player) throws SQLException {
        Long balance = store.balance(player.getUniqueId());
        AtmHolder holder = new AtmHolder();
        holder.inventory = Bukkit.createInventory(holder, 27, Component.text("ZipATM"));
        if (balance == null) {
            holder.inventory.setItem(13, button(Material.EMERALD, "Create Account", "Open your bank account"));
        } else {
            holder.inventory.setItem(4, button(Material.GOLD_INGOT, "Bank Balance", Money.format(balance)));
            holder.inventory.setItem(10, button(Material.CHEST, "Deposit Wallet", "Move Vault wallet money to the bank"));
            holder.inventory.setItem(12, button(Material.HOPPER, "Withdraw to Wallet", "Move bank money to your Vault wallet"));
            holder.inventory.setItem(14, button(Material.IRON_INGOT, "Deposit Cash", "Deposit all cash in your inventory"));
            holder.inventory.setItem(16, button(Material.IRON_NUGGET, "Withdraw Cash", "Withdraw bank money as cash"));
            holder.inventory.setItem(22, button(Material.PAPER, "Get Debit Card", "Receive a uniquely identified card"));
            holder.inventory.setItem(24, button(Material.BOOK, "Transaction History", "View your recent transactions"));
        }
        player.openInventory(holder.inventory);
    }

    private ItemStack button(Material material, String name, String description) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        meta.lore(java.util.List.of(Component.text(description)));
        item.setItemMeta(meta);
        return item;
    }

    private void openTransactionHistory(Player player) throws SQLException {
        java.util.List<BankStore.Transaction> transactions = store.getTransactionHistory(player.getUniqueId(), 28);
        HistoryHolder holder = new HistoryHolder(player.getUniqueId());
        holder.inventory = Bukkit.createInventory(holder, 36, Component.text("Transaction History"));
        
        for (int i = 0; i < transactions.size() && i < 28; i++) {
            BankStore.Transaction tx = transactions.get(i);
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            
            String sign = tx.amount() > 0 ? "+" : "";
            meta.displayName(Component.text(tx.type()));
            
            java.time.LocalDateTime dt = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(tx.timestamp()),
                java.time.ZoneId.systemDefault()
            );
            String timeStr = dt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            
            java.util.List<Component> lore = java.util.List.of(
                Component.text(timeStr),
                Component.text("Amount: " + sign + Money.format(tx.amount())),
                Component.text("Balance: " + Money.format(tx.balanceAfter()))
            );
            meta.lore(lore);
            item.setItemMeta(meta);
            holder.inventory.setItem(i, item);
        }
        
        for (int slot = 28; slot < 35; slot++) {
            holder.inventory.setItem(slot, button(Material.GRAY_STAINED_GLASS_PANE, " ", " "));
        }
        holder.inventory.setItem(35, button(Material.ARROW, "Back", "Return to ATM"));
        
        player.openInventory(holder.inventory);
    }

    private void openWallet(Player player) throws SQLException {
        WalletHolder holder = new WalletHolder(player.getUniqueId());
        holder.inventory = Bukkit.createInventory(holder, 36, Component.text("ZipPay Wallet"));
        updateWallet(holder);
        player.openInventory(holder.inventory);
    }

    private void updateWallet(WalletHolder holder) throws SQLException {
        Map<Long, Integer> contents = store.walletContents(holder.owner);
        for (int index = 0; index < CashDenominations.VALUES.length; index++) {
            long value = CashDenominations.VALUES[index];
            ItemStack item = cashItems.displayTemplate(value);
            ItemMeta meta = item.getItemMeta();
            meta.lore(java.util.List.of(Component.text("Stored: " + contents.getOrDefault(value, 0)),
                    Component.text("Left-click: take one"), Component.text("Right-click: take up to 64")));
            item.setItemMeta(meta);
            holder.inventory.setItem(9 + index, item);
        }
        for (int slot = 18; slot < 27; slot++) {
            holder.inventory.setItem(slot, button(Material.GRAY_STAINED_GLASS_PANE, " ", " "));
        }
        BankStore.WalletCard stored = store.walletCard(holder.owner);
        if (stored == null) {
            holder.inventory.setItem(31, button(Material.BARRIER, "Debit Card", "Click your debit card in your inventory to store it"));
        } else {
            ItemStack item = walletCardItem(holder.owner, stored);
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().remove(cardKey);
            java.util.List<Component> lore = meta.lore() == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(meta.lore());
            lore.add(Component.text("Click to take your card"));
            meta.lore(lore);
            item.setItemMeta(meta);
            holder.inventory.setItem(31, item);
        }
    }

    private ItemStack walletCardItem(UUID owner, BankStore.WalletCard stored) throws SQLException {
        try {
            ItemStack item = ItemStack.deserializeBytes(stored.item());
            if (item.getAmount() != 1 || !stored.id().equals(cardId(item)) || !store.ownsCard(owner, stored.id())) {
                throw new SQLException("Stored debit card failed validation for " + owner);
            }
            return item;
        } catch (IllegalArgumentException exception) {
            throw new SQLException("Could not read stored debit card for " + owner, exception);
        }
    }

    @EventHandler
    public void onWalletClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof WalletHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId()) || holder.busy) {
            return;
        }
        if (event.getCursor() != null && !event.getCursor().getType().isAir()) {
            player.sendMessage(ChatColor.RED + "Empty your cursor before using the wallet.");
            return;
        }
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT && click != ClickType.SHIFT_LEFT && click != ClickType.SHIFT_RIGHT) {
            return;
        }
        int rawSlot = event.getRawSlot();
        boolean withdraw = rawSlot >= 9 && rawSlot < 9 + CashDenominations.VALUES.length;
        boolean withdrawCard = rawSlot == 31;
        boolean deposit = rawSlot >= holder.inventory.getSize() && event.getSlot() >= 0 && event.getSlot() < 36;
        if (!withdraw && !withdrawCard && !deposit) {
            return;
        }
        int slot = event.getSlot();
        ItemStack clicked = deposit ? event.getCurrentItem() : null;
        boolean depositCard = deposit && cardId(clicked) != null;
        if (deposit && !depositCard && cashItems.value(clicked) == 0) {
            player.sendMessage(ChatColor.RED + "Only ZipBank cash and your debit card can be stored in this wallet.");
            return;
        }
        ItemStack expected = clicked == null ? null : clicked.clone();
        long denomination = withdraw ? CashDenominations.VALUES[rawSlot - 9] : depositCard || withdrawCard ? 0 : cashItems.value(expected);
        boolean single = click == ClickType.LEFT;
        holder.busy = true;
        getServer().getScheduler().runTask(this, () -> {
            try {
                if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != holder) {
                    return;
                }
                if (withdraw) {
                    withdrawWalletCash(player, denomination, single ? 1 : 64);
                } else if (withdrawCard) {
                    withdrawWalletCard(player);
                } else if (depositCard) {
                    depositWalletCard(player, slot, expected);
                } else {
                    depositWalletCash(player, slot, expected, click == ClickType.RIGHT ? 1 : expected.getAmount());
                }
                updateWallet(holder);
            } catch (SQLException exception) {
                error(player, exception);
            } finally {
                holder.busy = false;
            }
        });
    }

    private void depositWalletCard(Player player, int slot, ItemStack expected) throws SQLException {
        ItemStack[] original = player.getInventory().getStorageContents();
        ItemStack card = original[slot];
        if (card == null || !card.equals(expected) || card.getAmount() != 1) {
            player.sendMessage(ChatColor.RED + "Select one unchanged debit card.");
            return;
        }
        UUID id = cardId(card);
        if (id == null || !store.ownsCard(player.getUniqueId(), id)) {
            player.sendMessage(ChatColor.RED + "That card is not registered to you.");
            return;
        }
        byte[] serialized = card.serializeAsBytes();
        ItemStack[] remaining = original.clone();
        remaining[slot] = null;
        player.getInventory().setStorageContents(remaining);
        try {
            if (!store.storeWalletCard(player.getUniqueId(), id, serialized)) {
                player.getInventory().setStorageContents(original);
                player.sendMessage(ChatColor.RED + "Your wallet already contains a debit card.");
                return;
            }
        } catch (SQLException exception) {
            player.getInventory().setStorageContents(original);
            throw exception;
        }
        player.sendMessage(ChatColor.GREEN + "Debit card stored.");
    }

    private void withdrawWalletCard(Player player) throws SQLException {
        BankStore.WalletCard stored = store.walletCard(player.getUniqueId());
        if (stored == null) {
            player.sendMessage(ChatColor.YELLOW + "Your wallet does not contain a debit card.");
            return;
        }
        ItemStack card = walletCardItem(player.getUniqueId(), stored);
        ItemStack[] original = player.getInventory().getStorageContents();
        ItemStack[] withCard = original.clone();
        int emptySlot = -1;
        for (int slot = 0; slot < withCard.length; slot++) {
            if (withCard[slot] == null || withCard[slot].getType().isAir()) {
                emptySlot = slot;
                break;
            }
        }
        if (emptySlot < 0) {
            player.sendMessage(ChatColor.RED + "Make room in your inventory first.");
            return;
        }
        withCard[emptySlot] = card;
        if (!store.removeWalletCard(player.getUniqueId(), stored.id())) {
            player.sendMessage(ChatColor.RED + "Your wallet changed; try again.");
            return;
        }
        try {
            player.getInventory().setStorageContents(withCard);
        } catch (RuntimeException exception) {
            try {
                if (!store.storeWalletCard(player.getUniqueId(), stored.id(), stored.item())) {
                    throw new SQLException("Could not restore wallet card");
                }
            } catch (SQLException failure) {
                getLogger().severe("MANUAL RECONCILIATION REQUIRED: wallet card " + stored.id() + " for " + player.getUniqueId() + " could not be restored: " + failure.getMessage());
                exception.addSuppressed(failure);
            }
            throw exception;
        }
        player.sendMessage(ChatColor.GREEN + "Debit card taken from wallet.");
    }

    private void depositWalletCash(Player player, int slot, ItemStack expected, int amount) throws SQLException {
        ItemStack[] original = player.getInventory().getStorageContents();
        ItemStack current = original[slot];
        if (current == null || !current.equals(expected)) {
            player.sendMessage(ChatColor.RED + "The selected cash changed. Try again.");
            return;
        }
        long denomination = cashItems.value(current);
        ItemStack[] remaining = original.clone();
        if (amount == current.getAmount()) {
            remaining[slot] = null;
        } else {
            remaining[slot] = current.clone();
            remaining[slot].setAmount(current.getAmount() - amount);
        }
        player.getInventory().setStorageContents(remaining);
        try {
            if (!store.changeWallet(player.getUniqueId(), denomination, amount)) {
                player.getInventory().setStorageContents(original);
                player.sendMessage(ChatColor.RED + "Your wallet cannot hold more of that denomination.");
                return;
            }
        } catch (SQLException exception) {
            player.getInventory().setStorageContents(original);
            throw exception;
        }
        cashSelections.remove(player.getUniqueId());
    }

    private void withdrawWalletCash(Player player, long denomination, int requested) throws SQLException {
        int stored = store.walletContents(player.getUniqueId()).getOrDefault(denomination, 0);
        int amount = Math.min(requested, stored);
        if (amount == 0) {
            player.sendMessage(ChatColor.RED + "No cash of that denomination is stored.");
            return;
        }
        ItemStack[] original = player.getInventory().getStorageContents();
        ItemStack[] withCash = cashItems.addDenomination(original, denomination, amount);
        if (withCash == null) {
            player.sendMessage(ChatColor.RED + "Make room in your inventory first.");
            return;
        }
        if (!store.changeWallet(player.getUniqueId(), denomination, -amount)) {
            player.sendMessage(ChatColor.RED + "Wallet balance changed; try again.");
            return;
        }
        try {
            player.getInventory().setStorageContents(withCash);
        } catch (RuntimeException exception) {
            try {
                if (!store.changeWallet(player.getUniqueId(), denomination, amount)) {
                    throw new SQLException("Could not restore wallet cash");
                }
            } catch (SQLException failure) {
                getLogger().severe("MANUAL RECONCILIATION REQUIRED: wallet cash restoration for " + player.getUniqueId()
                        + ", denomination " + denomination + ", quantity " + amount + " failed: " + failure.getMessage());
                exception.addSuppressed(failure);
            }
            throw exception;
        }
        cashSelections.remove(player.getUniqueId());
    }

    private void openKeypad(Player player, Transfer transfer, Payment payment, boolean cash) {
        KeypadHolder holder = new KeypadHolder(transfer, payment, cash);
        String title = payment == null ? (cash ? "Withdraw Cash" : transfer == Transfer.DEPOSIT ? "Deposit Amount" : "Withdraw Amount") : "Enter Card PIN";
        holder.inventory = Bukkit.createInventory(holder, 54, Component.text(title));
        int[] slots = {37, 10, 11, 12, 19, 20, 21, 28, 29, 30};
        for (int digit = 0; digit < slots.length; digit++) {
            holder.inventory.setItem(slots[digit], button(Material.LIGHT_GRAY_STAINED_GLASS_PANE, Integer.toString(digit), ""));
        }
        if (payment == null) {
            holder.inventory.setItem(38, button(Material.PAPER, ".", "Decimal point"));
        }
        holder.inventory.setItem(39, button(Material.ARROW, "Backspace", "Remove the last digit"));
        holder.inventory.setItem(45, button(Material.BARRIER, "Cancel", "Cancel this operation"));
        holder.inventory.setItem(53, button(Material.LIME_CONCRETE, "Enter", "Confirm"));
        updateKeypad(holder);
        player.openInventory(holder.inventory);
    }

    private void updateKeypad(KeypadHolder holder) {
        String input = holder.input.value();
        String display = holder.payment == null ? (input.isEmpty() ? "0" : input) : "*".repeat(input.length());
        String description = holder.payment == null ? "Amount" :
                (holder.payment.terminal().pending() > 0 ? "Charge: " : "Refund: ")
                        + Money.format(Math.abs(holder.payment.terminal().pending()));
        holder.inventory.setItem(4, button(Material.MAP, display.isEmpty() ? "Enter PIN" : display, description));
    }

    private void closeKeypad(Player player, KeypadHolder holder) {
        holder.submitted = true;
        getServer().getScheduler().runTask(this, () -> {
            if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == holder) {
                player.closeInventory();
            }
        });
    }

    private void clickKeypad(Player player, KeypadHolder holder, int slot) throws SQLException {
        if (holder.submitted) {
            return;
        }
        int[] slots = {37, 10, 11, 12, 19, 20, 21, 28, 29, 30};
        for (int digit = 0; digit < slots.length; digit++) {
            if (slot == slots[digit]) {
                holder.input.digit(digit);
                updateKeypad(holder);
                return;
            }
        }
        if (slot == 38) {
            holder.input.decimal();
        } else if (slot == 39) {
            holder.input.backspace();
        } else if (slot == 45) {
            closeKeypad(player, holder);
            player.sendMessage(ChatColor.YELLOW + "Operation cancelled.");
            return;
        } else if (slot == 53) {
            if (holder.payment == null) {
                try {
                    long amount = Money.parse(holder.input.value());
                    closeKeypad(player, holder);
                    if (holder.cash) {
                        withdrawCash(player, amount);
                    } else {
                        transfer(player, holder.transfer, amount);
                    }
                } catch (IllegalArgumentException exception) {
                    player.sendMessage(ChatColor.RED + exception.getMessage());
                }
            } else if (!PinCode.valid(holder.input.value())) {
                player.sendMessage(ChatColor.RED + "Enter exactly four PIN digits.");
            } else {
                confirmPayment(player, holder);
            }
            return;
        }
        updateKeypad(holder);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder inventoryHolder = event.getView().getTopInventory().getHolder();
        if (!(inventoryHolder instanceof AtmHolder) && !(inventoryHolder instanceof KeypadHolder) && !(inventoryHolder instanceof HistoryHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() >= event.getView().getTopInventory().getSize()
                || event.getRawSlot() < 0) {
            return;
        }
        try {
            if (inventoryHolder instanceof HistoryHolder history) {
                if (event.getRawSlot() == 35) {
                    player.closeInventory();
                    getServer().getScheduler().runTask(this, () -> {
                        if (player.isOnline()) {
                            try {
                                openAtm(player);
                            } catch (SQLException exception) {
                                error(player, exception);
                            }
                        }
                    });
                }
                return;
            }
            if (inventoryHolder instanceof KeypadHolder keypad) {
                clickKeypad(player, keypad, event.getRawSlot());
                return;
            }
            AtmHolder atm = (AtmHolder) inventoryHolder;
            if (atm.busy) {
                return;
            }
            Long balance = store.balance(player.getUniqueId());
            if (balance == null && event.getRawSlot() == 13) {
                store.createAccount(player.getUniqueId());
                player.sendMessage(ChatColor.GREEN + "Bank account created.");
                getServer().getScheduler().runTask(this, () -> {
                    if (player.isOnline()) {
                        try {
                            openAtm(player);
                        } catch (SQLException exception) {
                            error(player, exception);
                        }
                    }
                });
            } else if (balance != null && (event.getRawSlot() == 10 || event.getRawSlot() == 12 || event.getRawSlot() == 16)) {
                boolean cash = event.getRawSlot() == 16;
                Transfer transfer = event.getRawSlot() == 10 ? Transfer.DEPOSIT : Transfer.WITHDRAW;
                getServer().getScheduler().runTask(this, () -> {
                    if (player.isOnline()) {
                        openKeypad(player, transfer, null, cash);
                    }
                });
            } else if (balance != null && event.getRawSlot() == 14) {
                atm.busy = true;
                getServer().getScheduler().runTask(this, () -> {
                    if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == atm) {
                        try {
                            depositCash(player);
                            openAtm(player);
                        } catch (SQLException exception) {
                            error(player, exception);
                        }
                    }
                    atm.busy = false;
                });
            } else if (balance != null && event.getRawSlot() == 22) {
                if (player.getInventory().firstEmpty() == -1) {
                    player.sendMessage(ChatColor.RED + "Make room in your inventory first.");
                    return;
                }
                if (prompts.putIfAbsent(player.getUniqueId(), new Prompt(PromptType.NEW_CARD_PIN, null, null)) != null) {
                    player.sendMessage(ChatColor.RED + "Finish or cancel your current chat prompt first.");
                    return;
                }
                getServer().getScheduler().runTask(this, () -> {
                    if (player.isOnline()) {
                        player.closeInventory();
                        player.sendMessage(ChatColor.YELLOW + "Enter a four-digit PIN in chat for your new card, or type cancel.");
                    }
                });
            } else if (balance != null && event.getRawSlot() == 24) {
                getServer().getScheduler().runTask(this, () -> {
                    if (player.isOnline()) {
                        try {
                            openTransactionHistory(player);
                        } catch (SQLException exception) {
                            error(player, exception);
                        }
                    }
                });
            }
        } catch (SQLException exception) {
            error(player, exception);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof AtmHolder
            || event.getView().getTopInventory().getHolder() instanceof KeypadHolder
            || event.getView().getTopInventory().getHolder() instanceof WalletHolder
            || event.getView().getTopInventory().getHolder() instanceof HistoryHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Prompt prompt = prompts.get(event.getPlayer().getUniqueId());
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        if (prompt.type() == PromptType.CHECKOUT_ID_PENDING || prompt.type() == PromptType.SHOP_ID_PENDING) {
            getServer().getScheduler().runTask(this, () -> event.getPlayer().sendMessage(ChatColor.YELLOW + "Wait for the checkout ID prompt."));
            return;
        }
        String input = text(event.message()).trim();
        getServer().getScheduler().runTask(this, () -> {
            Player player = event.getPlayer();
            if (!player.isOnline() || !prompts.remove(player.getUniqueId(), prompt)) {
                return;
            }
            if (input.equalsIgnoreCase("cancel")) {
                shops.pendingIds.remove(player.getUniqueId());
                player.sendMessage(ChatColor.YELLOW + (prompt.type() == PromptType.CHECKOUT_ID
                        ? "Checkout ID assignment cancelled. Look at the sign and use /zippay assign <id> later."
                        : prompt.type() == PromptType.SHOP_ID || prompt.type() == PromptType.SHOP_READER
                        ? "Shop setup cancelled. Look at the sign and use /zipshop setup <id> <reader-id> later."
                        : "Card PIN setup cancelled."));
                return;
            }
            try {
                if (prompt.type() == PromptType.CHECKOUT_ID) {
                    if (!assignCheckout(player, prompt.checkoutLocation(), input)) {
                        prompts.putIfAbsent(player.getUniqueId(), prompt);
                    }
                } else if (prompt.type() == PromptType.SHOP_ID) {
                    if (!shops.validId(input)) {
                        player.sendMessage(ChatColor.RED + "ID must be 1-16 letters, numbers, underscores or hyphens, and cannot be cancel.");
                        prompts.putIfAbsent(player.getUniqueId(), prompt);
                    } else if (store.shopLocation(player.getUniqueId(), input) != null) {
                        player.sendMessage(ChatColor.RED + "That shop ID is already in use.");
                        prompts.putIfAbsent(player.getUniqueId(), prompt);
                    } else {
                        shops.pendingIds.put(player.getUniqueId(), input);
                        prompts.putIfAbsent(player.getUniqueId(), new Prompt(PromptType.SHOP_READER, prompt.checkoutLocation(), null));
                        player.sendMessage(ChatColor.YELLOW + "Enter the ID of your existing ZipPay reader, or type cancel.");
                    }
                } else if (prompt.type() == PromptType.SHOP_READER) {
                    String id = shops.pendingIds.get(player.getUniqueId());
                    if (id != null && !shops.setup(player, prompt.checkoutLocation(), id, input)) {
                        prompts.putIfAbsent(player.getUniqueId(), prompt);
                    } else {
                        shops.pendingIds.remove(player.getUniqueId());
                    }
                } else {
                    if (!setupCardPin(player, prompt, input)) {
                        prompts.putIfAbsent(player.getUniqueId(), prompt);
                    }
                }
            } catch (IllegalArgumentException exception) {
                player.sendMessage(ChatColor.RED + exception.getMessage());
            } catch (SQLException exception) {
                error(player, exception);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        prompts.remove(event.getPlayer().getUniqueId());
        cashSelections.remove(event.getPlayer().getUniqueId());
        shops.quit(event.getPlayer().getUniqueId());
    }

    private boolean setupCardPin(Player player, Prompt prompt, String pin) throws SQLException {
        if (!PinCode.valid(pin)) {
            player.sendMessage(ChatColor.RED + "PIN must be exactly four digits. Try again, or type cancel.");
            return false;
        }
        if (prompt.type() == PromptType.EXISTING_CARD_PIN) {
            if (!prompt.card().equals(cardId(player.getInventory().getItemInMainHand()))
                    || !store.setCardPin(player.getUniqueId(), prompt.card(), pin)) {
                player.sendMessage(ChatColor.RED + "Hold the same unconfigured card in your main hand. Tap it again to retry.");
                return true;
            }
            player.sendMessage(ChatColor.GREEN + "Card PIN set. Tap the checkout again to continue.");
            return true;
        }
        if (player.getInventory().firstEmpty() == -1) {
            player.sendMessage(ChatColor.RED + "Make room in your inventory, then enter your PIN again or type cancel.");
            return false;
        }
        UUID cardId = store.createCard(player.getUniqueId());
        if (!store.setCardPin(player.getUniqueId(), cardId, pin)) {
            throw new SQLException("Could not set PIN on new card");
        }
        ItemStack card = new ItemStack(cardMaterial);
        ItemMeta meta = card.getItemMeta();
        meta.displayName(cardName);
        if (cardModelData != null) {
            meta.setCustomModelData(cardModelData);
        }
        meta.lore(java.util.List.of(Component.text("Owner: " + player.getName()), Component.text("ID: " + cardId)));
        meta.getPersistentDataContainer().set(cardKey, PersistentDataType.STRING, cardId.toString());
        card.setItemMeta(meta);
        player.getInventory().addItem(card);
        player.sendMessage(ChatColor.GREEN + "Debit card issued with your PIN.");
        return true;
    }

    private boolean cashSupported(Player player) {
        int precision = economy.fractionalDigits();
        if (precision >= 0 && precision < 2) {
            player.sendMessage(ChatColor.RED + "Cash requires a Vault economy that supports cents.");
            return false;
        }
        return true;
    }

    private void depositCash(Player player) throws SQLException {
        if (!cashSupported(player)) {
            return;
        }
        ItemStack[] original = player.getInventory().getStorageContents();
        Map<Long, Integer> counts = cashItems.counts(original);
        long amount = counts.entrySet().stream().mapToLong(entry -> entry.getKey() * entry.getValue()).sum();
        if (amount == 0) {
            player.sendMessage(ChatColor.RED + "There is no ZipBank cash in your inventory.");
            return;
        }
        ItemStack[] remaining = cashItems.remove(original, counts);
        player.getInventory().setStorageContents(remaining);
        try {
            if (!store.changeBalance(player.getUniqueId(), amount)) {
                player.getInventory().setStorageContents(original);
                player.sendMessage(ChatColor.RED + "Your bank account cannot accept that cash deposit.");
                return;
            }
            store.logTransaction(player.getUniqueId(), "Cash Deposit", amount);
        } catch (SQLException exception) {
            player.getInventory().setStorageContents(original);
            throw exception;
        }
        player.sendMessage(ChatColor.GREEN + "Cash deposited: " + Money.format(amount) + ".");
    }

    private void withdrawCash(Player player, long amount) throws SQLException {
        if (!cashSupported(player)) {
            return;
        }
        ItemStack[] original = player.getInventory().getStorageContents();
        ItemStack[] withCash = cashItems.add(original, amount);
        if (withCash == null) {
            player.sendMessage(ChatColor.RED + "Not enough inventory space for that cash withdrawal.");
            return;
        }
        if (!store.changeBalance(player.getUniqueId(), -amount)) {
            player.sendMessage(ChatColor.RED + "Insufficient bank balance.");
            return;
        }
        try {
            player.getInventory().setStorageContents(withCash);
            store.logTransaction(player.getUniqueId(), "Cash Withdrawal", -amount);
        } catch (RuntimeException exception) {
            try {
                if (!store.changeBalance(player.getUniqueId(), amount)) {
                    throw new SQLException("Could not restore bank balance");
                }
            } catch (SQLException failure) {
                getLogger().severe("MANUAL RECONCILIATION REQUIRED: cash withdrawal refund of " + Money.format(amount) + " to " + player.getUniqueId() + " failed: " + failure.getMessage());
                exception.addSuppressed(failure);
            }
            throw exception;
        }
        player.sendMessage(ChatColor.GREEN + "Cash withdrawn: " + Money.format(amount) + ".");
    }

    private void payCash(Player player, Block block, Sign sign, BankStore.Terminal terminal, long value) throws SQLException {
        UUID customer = player.getUniqueId();
        if (terminal.customer() != null && !terminal.customer().equals(customer)) {
            player.sendMessage(ChatColor.RED + "This checkout is reserved for another customer.");
            return;
        }
        if (!cashSupported(player)) {
            return;
        }
        if (terminal.pending() <= 0 || terminal.owner().equals(customer)) {
            cashSelections.remove(customer);
            player.sendMessage(ChatColor.RED + (terminal.pending() < 0 ? "Cash cannot be used to collect a refund; use a debit card."
                    : terminal.pending() == 0 ? "There is no pending charge." : "Owners cannot pay themselves."));
            return;
        }
        String checkoutLocation = location(block);
        CashSelection selection = cashSelections.get(customer);
        if (selection == null || !selection.location.equals(checkoutLocation) || !selection.token.equals(terminal.token())) {
            selection = new CashSelection(checkoutLocation, terminal.token());
            cashSelections.put(customer, selection);
            player.sendMessage(ChatColor.YELLOW + "Tap once per bill or coin. Cash stays in your inventory until payment completes.");
        }
        ItemStack[] original = player.getInventory().getStorageContents();
        Map<Long, Integer> selected = new HashMap<>(selection.counts);
        selected.merge(value, 1, Integer::sum);
        ItemStack[] remaining = cashItems.remove(original, selected);
        if (remaining == null) {
            player.sendMessage(ChatColor.RED + "You no longer have all selected cash. Tap again to restart.");
            cashSelections.remove(customer);
            return;
        }
        long total = selection.total + value;
        long due = Math.max(0, terminal.pending() - total);
        if (due > 0) {
            player.sendActionBar(Component.text("Amount left: " + Money.format(due)));
            selection.counts.clear();
            selection.counts.putAll(selected);
            selection.total = total;
            return;
        }
        long change = total - terminal.pending();
        ItemStack[] afterPayment = cashItems.add(remaining, change);
        if (afterPayment == null) {
            player.sendMessage(ChatColor.RED + "Make room for your change, then tap that cash again.");
            return;
        }
        ShopCheckout.Fulfillment fulfillment = shops.preparePacking(player, checkoutLocation, terminal, afterPayment);
        if (fulfillment == null) return;
        player.getInventory().setStorageContents(fulfillment.customer());
        try {
            if (!store.settleCash(checkoutLocation, customer, terminal.token())) {
                player.getInventory().setStorageContents(original);
                updateSign(sign, 0);
                player.sendMessage(ChatColor.RED + "Payment failed: the checkout changed or the shop account is full.");
                cashSelections.remove(customer);
                return;
            }
        } catch (SQLException exception) {
            player.getInventory().setStorageContents(original);
            throw exception;
        }
        fulfillment.applyOwner();
        cashSelections.remove(customer);
        updateSign(sign, 0);
        player.sendActionBar(Component.text("Amount left: 0.00"));
        player.sendMessage(ChatColor.GREEN + "Paid " + Money.format(terminal.pending()) + " in cash. Change: " + Money.format(change) + ".");
        Player owner = Bukkit.getPlayer(terminal.owner());
        if (owner != null) {
            owner.sendMessage(ChatColor.GREEN + player.getName() + " paid " + Money.format(terminal.pending()) + " in cash.");
        }
    }

    private void transfer(Player player, Transfer transfer, long amount) throws SQLException {
        int digits = economy.fractionalDigits();
        if (digits >= 0 && digits < 2 && amount % (digits == 0 ? 100 : 10) != 0) {
            player.sendMessage(ChatColor.RED + "Your economy does not support that decimal precision.");
            return;
        }
        double value = amount / 100.0;
        UUID owner = player.getUniqueId();
        Long balance = store.balance(owner);
        if (balance == null || (transfer == Transfer.DEPOSIT && balance > BankStore.MAX_BALANCE - amount)
                || (transfer == Transfer.WITHDRAW && balance < amount)) {
            player.sendMessage(ChatColor.RED + "The account cannot accept that transfer.");
            return;
        }
        if (transfer == Transfer.DEPOSIT) {
            EconomyResponse response = economy.withdrawPlayer(player, value);
            if (!response.transactionSuccess()) {
                player.sendMessage(ChatColor.RED + "Wallet withdrawal failed: " + response.errorMessage);
                return;
            }
            try {
                if (!store.changeBalance(owner, amount)) {
                    throw new SQLException("Could not credit bank account");
                }
                store.logTransaction(owner, "Vault Deposit", amount);
            } catch (SQLException exception) {
                EconomyResponse reversal = economy.depositPlayer(player, value);
                if (!reversal.transactionSuccess()) {
                    getLogger().severe("MANUAL RECONCILIATION REQUIRED: wallet refund of " + Money.format(amount) + " to " + owner + " failed: " + reversal.errorMessage);
                }
                throw exception;
            }
        } else {
            if (!store.changeBalance(owner, -amount)) {
                player.sendMessage(ChatColor.RED + "Insufficient bank balance.");
                return;
            }
            EconomyResponse response = economy.depositPlayer(player, value);
            if (!response.transactionSuccess()) {
                try {
                    if (!store.changeBalance(owner, amount)) {
                        throw new SQLException("Could not restore bank balance");
                    }
                } catch (SQLException exception) {
                    getLogger().severe("MANUAL RECONCILIATION REQUIRED: bank refund of " + Money.format(amount) + " to " + owner + " failed: " + exception.getMessage());
                    throw exception;
                }
                player.sendMessage(ChatColor.RED + "Wallet deposit failed: " + response.errorMessage);
                return;
            }
            store.logTransaction(owner, "Vault Withdrawal", -amount);
        }
        player.sendMessage(ChatColor.GREEN + "Transfer complete: " + Money.format(amount) + ".");
    }

    private void transferToPlayer(Player player, String amountText, String targetName, boolean government) throws SQLException {
        long amount;
        try {
            amount = Money.parse(amountText);
        } catch (IllegalArgumentException exception) {
            player.sendMessage(ChatColor.RED + exception.getMessage());
            return;
        }
        OfflinePlayer target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            target = Bukkit.getOfflinePlayerIfCached(targetName);
        }
        if (target == null || store.balance(target.getUniqueId()) == null) {
            player.sendMessage(ChatColor.RED + "That player does not have a ZipBank account.");
            return;
        }
        UUID targetId = target.getUniqueId();
        String name = target.getName() == null ? targetName : target.getName();
        if (government) {
            if (!store.transferFromGovernment(targetId, amount)) {
                player.sendMessage(ChatColor.RED + "Transfer failed: the recipient's account cannot accept that amount.");
                return;
            }
            store.logTransaction(targetId, "Government Transfer", amount);
            player.sendMessage(ChatColor.GREEN + "Sent " + Money.format(amount) + " from the government budget to " + name + ".");
        } else {
            if (targetId.equals(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "You cannot transfer money to yourself.");
                return;
            }
            if (!store.transfer(player.getUniqueId(), targetId, amount)) {
                player.sendMessage(ChatColor.RED + "Transfer failed: check your balance and the recipient's account limit.");
                return;
            }
            store.logTransaction(player.getUniqueId(), "Transfer to " + name, -amount);
            store.logTransaction(targetId, "Transfer from " + player.getName(), amount);
            player.sendMessage(ChatColor.GREEN + "Sent " + Money.format(amount) + " to " + name + ".");
        }
        Player online = Bukkit.getPlayer(targetId);
        if (online != null) {
            online.sendMessage(ChatColor.GREEN + "You received " + Money.format(amount) + (government ? " from the government budget." : " from " + player.getName() + "."));
        }
    }

    private void tap(Player player, ItemStack item, Block block, Sign sign, BankStore.Terminal terminal) throws SQLException {
        if (terminal.customer() != null && !terminal.customer().equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "This checkout is reserved for another customer.");
            return;
        }
        UUID card = cardId(item);
        if (card == null) {
            player.sendMessage(ChatColor.RED + "Hold a valid debit card.");
            return;
        }
        if (!store.ownsCard(player.getUniqueId(), card)) {
            player.sendMessage(ChatColor.RED + "That card is not registered to you.");
        } else if (!store.hasCardPin(player.getUniqueId(), card)) {
            if (prompts.putIfAbsent(player.getUniqueId(), new Prompt(PromptType.EXISTING_CARD_PIN, null, card)) == null) {
                player.sendMessage(ChatColor.YELLOW + "Enter a four-digit PIN in chat for this existing card, or type cancel.");
            } else {
                player.sendMessage(ChatColor.RED + "Finish or cancel your current chat prompt first.");
            }
        } else if (terminal.pending() == 0) {
            player.sendMessage(ChatColor.YELLOW + "There is no pending charge or refund.");
        } else if (terminal.owner().equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "Owners cannot pay themselves.");
        } else {
            openKeypad(player, null, new Payment(location(block), card, terminal), false);
        }
    }

    private UUID cardId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String id = item.getItemMeta().getPersistentDataContainer().get(cardKey, PersistentDataType.STRING);
        try {
            return UUID.fromString(id == null ? "" : id);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void confirmPayment(Player player, KeypadHolder holder) throws SQLException {
        Payment payment = holder.payment;
        Block block = checkoutBlock(payment.location());
        if (block == null || !(block.getState() instanceof Sign sign)
                || !text(sign.getSide(Side.FRONT).line(0)).equals("[ZipPay]")
                || !player.getWorld().equals(block.getWorld())
                || player.getLocation().distanceSquared(block.getLocation().add(0.5, 0.5, 0.5)) > 64
                || !payment.card().equals(cardId(player.getInventory().getItemInMainHand()))) {
            closeKeypad(player, holder);
            player.sendMessage(ChatColor.RED + "Hold the same card near the checkout and tap again.");
            return;
        }
        BankStore.Terminal current = store.terminal(payment.location());
        if (current == null || !current.equals(payment.terminal())) {
            closeKeypad(player, holder);
            player.sendMessage(ChatColor.RED + "The checkout amount changed. Tap again to review it.");
            return;
        }
        BankStore.PinResult result = store.verifyCardPin(player.getUniqueId(), payment.card(), holder.input.value(), System.currentTimeMillis());
        holder.input.clear();
        updateKeypad(holder);
        if (result == BankStore.PinResult.INVALID) {
            player.sendMessage(ChatColor.RED + "Incorrect PIN. Try again.");
            return;
        }
        if (result != BankStore.PinResult.OK) {
            closeKeypad(player, holder);
            player.sendMessage(ChatColor.RED + (result == BankStore.PinResult.LOCKED
                    ? "Card locked for five minutes after too many incorrect PIN attempts." : "This card cannot be verified."));
            return;
        }
        ShopCheckout.Fulfillment fulfillment = shops.preparePacking(player, payment.location(), payment.terminal(),
                player.getInventory().getStorageContents());
        if (fulfillment == null) {
            closeKeypad(player, holder);
            return;
        }
        closeKeypad(player, holder);
        if (store.settle(payment.location(), player.getUniqueId(), payment.terminal().token())) {
            fulfillment.applyOwner();
            player.getInventory().setStorageContents(fulfillment.customer());
            updateSign(sign, 0);
            long amount = payment.terminal().pending();
            player.sendMessage(ChatColor.GREEN + (amount > 0 ? "Paid " : "Refund received: ") + Money.format(Math.abs(amount)) + ".");
            Player owner = Bukkit.getPlayer(payment.terminal().owner());
            if (owner != null) {
                owner.sendMessage(ChatColor.GREEN + player.getName() + (amount > 0 ? " paid " : " received a refund of ") + Money.format(Math.abs(amount)) + ".");
            }
        } else {
            updateSign(sign, 0);
            player.sendMessage(ChatColor.RED + "Payment failed: check balances and account limits, then tap again.");
        }
    }

    void updateSign(Sign sign, long amount) {
        sign.getSide(Side.FRONT).line(1, Component.text("Tap Card Here", NamedTextColor.DARK_BLUE));
        sign.getSide(Side.FRONT).line(2, amount == 0 ? Component.empty() : Component.text(Money.format(amount),
                amount < 0 ? NamedTextColor.DARK_RED : NamedTextColor.DARK_GREEN, TextDecoration.BOLD));
        sign.update();
    }

    private boolean assignCheckout(Player player, String checkoutLocation, String id) throws SQLException {
        if (!id.matches("[A-Za-z0-9_-]{1,16}") || id.equalsIgnoreCase("cancel")) {
            player.sendMessage(ChatColor.RED + "ID must be 1-16 letters, numbers, underscores or hyphens, and cannot be cancel.");
            return false;
        }
        Block block = checkoutBlock(checkoutLocation);
        if (block == null || !(block.getState() instanceof Sign sign)
                || !text(sign.getSide(Side.FRONT).line(0)).equals("[ZipPay]")) {
            player.sendMessage(ChatColor.RED + "The checkout sign is no longer available.");
            return true;
        }
        BankStore.Terminal terminal = store.terminal(checkoutLocation);
        if (terminal == null || !terminal.owner().equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You no longer own that checkout.");
            return true;
        }
        if (!store.assignTerminalId(checkoutLocation, player.getUniqueId(), id)) {
            player.sendMessage(ChatColor.RED + "That ID is already in use, or this checkout already has an ID.");
            return store.terminalLocation(player.getUniqueId(), id) == null;
        }
        prompts.computeIfPresent(player.getUniqueId(), (owner, prompt) ->
            checkoutLocation.equals(prompt.checkoutLocation()) ? null : prompt);
        player.sendMessage(ChatColor.GREEN + "Checkout ID assigned: " + id + ".");
        return true;
    }

    Block checkoutBlock(String checkoutLocation) {
        String[] parts = checkoutLocation.split(":");
        if (parts.length != 4) {
            return null;
        }
        try {
            World world = Bukkit.getWorld(UUID.fromString(parts[0]));
            return world == null ? null : world.getBlockAt(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    void clearShopPrompt(UUID player, String location) {
        prompts.computeIfPresent(player, (owner, prompt) ->
                (prompt.type() == PromptType.SHOP_ID || prompt.type() == PromptType.SHOP_READER)
                        && location.equals(prompt.checkoutLocation()) ? null : prompt);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can operate checkout signs.");
            return true;
        }
        if (command.getName().equalsIgnoreCase("zipshop")) {
            return shops.command(player, args);
        }
        if (args.length == 0) {
            return false;
        }
        if (args[0].equalsIgnoreCase("checkout")) {
            if (args.length != 2) return false;
            try {
                String reader = store.terminalLocation(player.getUniqueId(), args[1]);
                if (reader == null) player.sendMessage(ChatColor.RED + "No reader with that ID belongs to you.");
                else shops.openStaffChoice(player, reader);
            } catch (SQLException exception) {
                error(player, exception);
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("wallet")) {
            if (args.length != 1) {
                return false;
            }
            try {
                openWallet(player);
            } catch (SQLException exception) {
                error(player, exception);
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("transfer")) {
            if (args.length != 3) {
                return false;
            }
            try {
                transferToPlayer(player, args[1], args[2], false);
            } catch (SQLException exception) {
                error(player, exception);
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("government")) {
            if (args.length != 1 && args.length != 3 && args.length != 4) {
                return false;
            }
            if (args.length > 1 && !player.hasPermission("zipbank.admin")) {
                player.sendMessage(ChatColor.RED + "Only administrators can deposit to or transfer from the government budget.");
                return true;
            }
            if (args.length == 3) {
                if (!args[1].equalsIgnoreCase("deposit")) {
                    return false;
                }
                try {
                    long amount = Money.parse(args[2]);
                    if (store.depositToGovernment(player.getUniqueId(), amount)) {
                        store.logTransaction(player.getUniqueId(), "Government Deposit", -amount);
                        player.sendMessage(ChatColor.GREEN + "Deposited " + Money.format(amount) + " into the government budget.");
                    } else {
                        player.sendMessage(ChatColor.RED + "Deposit failed: check your balance and the budget limit.");
                    }
                } catch (IllegalArgumentException exception) {
                    player.sendMessage(ChatColor.RED + exception.getMessage());
                } catch (SQLException exception) {
                    error(player, exception);
                }
                return true;
            }
            if (args.length == 4) {
                if (!args[1].equalsIgnoreCase("transfer")) {
                    return false;
                }
                try {
                    transferToPlayer(player, args[2], args[3], true);
                } catch (SQLException exception) {
                    error(player, exception);
                }
                return true;
            }
            try {
                Long balance = store.balance(BankStore.GOVERNMENT);
                player.sendMessage(ChatColor.GREEN + "Government Budget: " + Money.format(balance == null ? 0 : balance) + ".");
            } catch (SQLException exception) {
                error(player, exception);
            }
            return true;
        }
        boolean assign = args[0].equalsIgnoreCase("assign");
        boolean cancel = args[0].equalsIgnoreCase("cancel");
        boolean charge = args[0].equalsIgnoreCase("charge");
        boolean refund = args[0].equalsIgnoreCase("refund");
        if ((!assign && !cancel && !charge && !refund)
                || ((assign || cancel) && args.length != 2)
                || ((charge || refund) && args.length != 3)) {
            return false;
        }
        try {
            if (assign) {
                Block block = player.getTargetBlockExact(8);
                if (block == null) {
                    player.sendMessage(ChatColor.RED + "Look at an unassigned checkout sign you own within 8 blocks.");
                } else {
                    assignCheckout(player, location(block), args[1]);
                }
                return true;
            }
            long baseAmount = cancel ? 0 : Money.parse(args[1]);
            long amount = baseAmount;
            if (!cancel) {
                long tax = BankStore.salesTax(baseAmount);
                amount = baseAmount + tax;
            }
            if (refund) {
                amount = -amount;
            }
            String id = args[cancel ? 1 : 2];
            String checkoutLocation = store.terminalLocation(player.getUniqueId(), id);
            if (checkoutLocation == null) {
                player.sendMessage(ChatColor.RED + "No checkout with that ID belongs to you.");
                return true;
            }
            Block block = checkoutBlock(checkoutLocation);
            if (block == null || !(block.getState() instanceof Sign sign)
                    || !text(sign.getSide(Side.FRONT).line(0)).equals("[ZipPay]")) {
                player.sendMessage(ChatColor.RED + "That checkout sign or its world is unavailable.");
                return true;
            }
            if (!store.setPending(checkoutLocation, player.getUniqueId(), amount)) {
                player.sendMessage(ChatColor.RED + "Could not update that checkout.");
                return true;
            }
            updateSign(sign, amount);
            if (cancel) {
                player.sendMessage(ChatColor.GREEN + "Checkout cleared.");
            } else {
                long displayAmount = refund ? baseAmount : amount;
                String displayMessage = refund ? "Refund set to " : "Checkout set to ";
                player.sendMessage(ChatColor.GREEN + displayMessage + Money.format(Math.abs(displayAmount)) + 
                    " (including " + Money.format(BankStore.salesTax(baseAmount)) + " tax).");
            }
        } catch (IllegalArgumentException exception) {
            player.sendMessage(ChatColor.RED + exception.getMessage());
        } catch (SQLException exception) {
            error(player, exception);
        }
        return true;
    }
}