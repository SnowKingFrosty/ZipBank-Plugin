package com.zipbank;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Rotation;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.map.MinecraftFont;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

final class ShopCheckout implements Listener {
    private final ZipBankPlugin plugin;
    private final BankStore store;
    private final NamespacedKey priceKey;
    private final NamespacedKey ownerKey;
    private final NamespacedKey displayKey;
    private final Map<UUID, Cart> carts = new HashMap<>();
    private final Map<UUID, String> frames = new HashMap<>();
    final Map<UUID, String> pendingIds = new HashMap<>();

    private record Line(String name, int count, long total) { }
    private record DisplayState(Cart cart, int revision) { }
    private static final class Cart {
        final String location;
        final boolean staffed;
        final List<Line> lines = new ArrayList<>();
        final List<ItemStack> scanned = new ArrayList<>();
        long base;
        int revision;
        Cart(String location, boolean staffed) { this.location = location; this.staffed = staffed; }
    }

    private static final class Choice implements InventoryHolder {
        final UUID customer;
        final String location;
        final UUID frame;
        final Cart cart;
        final int revision;
        final int boxes;
        Inventory inventory;

        Choice(UUID customer, String location, UUID frame, Cart cart, int boxes) {
            this.customer = customer;
            this.location = location;
            this.frame = frame;
            this.cart = cart;
            this.revision = cart.revision;
            this.boxes = boxes;
        }

        @Override
        public Inventory getInventory() { return inventory; }
    }

    ShopCheckout(ZipBankPlugin plugin, BankStore store) throws SQLException {
        this.plugin = plugin;
        this.store = store;
        priceKey = new NamespacedKey(plugin, "shop-price");
        ownerKey = new NamespacedKey(plugin, "shop-owner");
        displayKey = new NamespacedKey(plugin, "shop-display");
        for (Map.Entry<String, Integer> entry : store.shopMaps().entrySet()) {
            BankStore.Shop shop = store.shop(entry.getKey());
            MapView map = Bukkit.getMap(entry.getValue());
            if (shop == null || shop.frame() == null || map == null) {
                plugin.getLogger().warning("Shop display unavailable at " + entry.getKey() + "; set it up again.");
                continue;
            }
            try {
                frames.put(UUID.fromString(shop.frame()), entry.getKey());
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Invalid shop frame ID at " + entry.getKey() + "; set it up again.");
                continue;
            }
            installRenderer(map, entry.getKey());
        }
    }

    boolean validId(String id) {
        return id.matches("[A-Za-z0-9_-]{1,16}") && !id.equalsIgnoreCase("cancel");
    }

    boolean setup(Player player, String location, String id, String readerId) throws SQLException {
        if (!validId(id) || !validId(readerId)) {
            player.sendMessage(ChatColor.RED + "IDs must be 1-16 letters, numbers, underscores or hyphens, and cannot be cancel.");
            return false;
        }
        Block signBlock = plugin.checkoutBlock(location);
        BankStore.Shop shop = store.shop(location);
        if (signBlock == null || !(signBlock.getState() instanceof Sign sign)
                || !ZipBankPlugin.text(sign.getSide(Side.FRONT).line(0)).equals("[ZipShop]")
                || shop == null || !shop.owner().equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "The shop sign is no longer available or yours.");
            return true;
        }
        String reader = store.terminalLocation(player.getUniqueId(), readerId);
        Block readerBlock = reader == null ? null : plugin.checkoutBlock(reader);
        if (readerBlock == null || !(readerBlock.getState() instanceof Sign readerSign)
                || !ZipBankPlugin.text(readerSign.getSide(Side.FRONT).line(0)).equals("[ZipPay]")) {
            player.sendMessage(ChatColor.RED + "No available ZipPay reader with that ID belongs to you.");
            return false;
        }
        if (!readerBlock.getWorld().equals(signBlock.getWorld())
                || readerBlock.getLocation().distanceSquared(signBlock.getLocation()) > 64) {
            player.sendMessage(ChatColor.RED + "The ZipPay reader must be within 8 blocks of the shop sign.");
            return false;
        }
        if (!store.assignShop(location, player.getUniqueId(), id, reader)) {
            player.sendMessage(ChatColor.RED + "That shop ID is already in use.");
            return false;
        }
        plugin.clearShopPrompt(player.getUniqueId(), location);
        pendingIds.remove(player.getUniqueId());
        player.sendMessage(ChatColor.GREEN + "Shop " + id + " linked to reader " + readerId
                + ". Right-click a wall near the sign with your shop map to place the display.");
        return true;
    }

    boolean command(Player player, String[] args) {
        try {
            if (args.length == 2 && args[0].equalsIgnoreCase("price")) {
                price(player, Money.parse(args[1]));
                return true;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("clear")) {
                carts.remove(player.getUniqueId());
                player.sendMessage(ChatColor.GREEN + "Your scanned cart was cleared. An active reader charge must be cancelled by its owner.");
                return true;
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("setup")) {
                Block block = player.getTargetBlockExact(8);
                if (block == null || store.shop(ZipBankPlugin.location(block)) == null) {
                    player.sendMessage(ChatColor.RED + "Look at your ZipShop sign within 8 blocks.");
                } else {
                    setup(player, ZipBankPlugin.location(block), args[1], args[2]);
                }
                return true;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("display")) {
                display(player, args[1]);
                return true;
            }
        } catch (IllegalArgumentException exception) {
            player.sendMessage(ChatColor.RED + exception.getMessage());
            return true;
        } catch (SQLException exception) {
            plugin.error(player, exception);
            return true;
        }
        return false;
    }

    private void price(Player player, long amount) throws SQLException {
        if (!player.hasPermission("zipbank.pay.create") || store.balance(player.getUniqueId()) == null) {
            player.sendMessage(ChatColor.RED + "You need checkout creation permission and a bank account.");
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            player.sendMessage(ChatColor.RED + "Hold the item or stack to price.");
            return;
        }
        ItemMeta meta = item.getItemMeta();
        String previousOwner = meta.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (previousOwner != null && !previousOwner.equals(player.getUniqueId().toString())) {
            player.sendMessage(ChatColor.RED + "Only the original shop owner can reprice this item.");
            return;
        }
        meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        meta.getPersistentDataContainer().set(priceKey, PersistentDataType.LONG, amount);
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.removeIf(line -> ZipBankPlugin.text(line).startsWith("ZipShop price: "));
        lore.add(Component.text("ZipShop price: " + Money.format(amount) + " each (before tax)"));
        meta.lore(lore);
        item.setItemMeta(meta);
        player.sendMessage(ChatColor.GREEN + "Priced each item at " + Money.format(amount) + " before tax.");
    }

    void scan(Player player, Block block, Sign sign, BankStore.Shop shop, ItemStack item) {
        if (!ZipBankPlugin.text(sign.getSide(Side.FRONT).line(0)).equals("[ZipShop]") || shop.id() == null || shop.reader() == null) {
            player.sendMessage(ChatColor.RED + "This shop checkout is not configured.");
            return;
        }
        scanItem(player, block, shop.owner(), item, shop.owner().equals(player.getUniqueId()));
    }

    private void scanItem(Player player, Block block, UUID ownerId, ItemStack item, boolean staffed) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            player.sendMessage(ChatColor.YELLOW + "Hold a priced item or stack and right-click to scan it.");
            return;
        }
        ItemMeta meta = item.getItemMeta();
        Long price = meta.getPersistentDataContainer().get(priceKey, PersistentDataType.LONG);
        String owner = meta.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (price == null || price <= 0 || price > Money.MAX_TRANSACTION || !ownerId.toString().equals(owner)) {
            player.sendMessage(ChatColor.RED + "This item is not priced by this shop owner.");
            return;
        }
        String location = ZipBankPlugin.location(block);
        Cart cart = carts.get(player.getUniqueId());
        if (cart != null && (!cart.location.equals(location) || cart.staffed != staffed)) {
            player.sendMessage(ChatColor.RED + "Clear your current cart with /zipshop clear before using another checkout.");
            return;
        }
        if (cart == null) cart = new Cart(location, staffed);
        if (cart.scanned.size() >= 256) {
            player.sendMessage(ChatColor.RED + "This cart has too many scans. Finish checkout or clear the cart.");
            return;
        }
        long lineTotal = price * item.getAmount();
        long base = cart.base + lineTotal;
        if (!validCartTotal(base)) {
            player.sendMessage(ChatColor.RED + "The cart exceeds the maximum checkout amount.");
            return;
        }
        String name = meta.hasDisplayName() ? ZipBankPlugin.text(meta.displayName()) : item.getType().name().replace('_', ' ');
        Line merged = null;
        for (int i = 0; i < cart.lines.size(); i++) {
            Line existing = cart.lines.get(i);
            if (existing.name().equals(name) && existing.total() == price * existing.count()) {
                merged = new Line(name, existing.count() + item.getAmount(), existing.total() + lineTotal);
                cart.lines.remove(i);
                break;
            }
        }
        cart.lines.add(merged != null ? merged : new Line(name, item.getAmount(), lineTotal));
        if (cart.lines.size() > 5) cart.lines.remove(0);
        cart.scanned.add(item.clone());
        cart.base = base;
        cart.revision++;
        carts.put(player.getUniqueId(), cart);
        block.getWorld().playSound(block.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_NOTE_BLOCK_BIT, 1.0f, 1.0f);
        player.sendMessage(ChatColor.GREEN + "Scanned " + item.getAmount() + " x " + name + ". Total with tax: "
                + Money.format(base + BankStore.salesTax(base)) + ".");
        if (staffed) player.sendMessage(ChatColor.YELLOW + "Press Pay on the shop map or use /zippay checkout <reader-id> to choose packaging.");
    }

    void openStaffChoice(Player player, String location) throws SQLException {
        Cart cart = carts.get(player.getUniqueId());
        BankStore.Shop shop = cart == null ? null : store.shop(cart.location);
        BankStore.Terminal terminal = store.terminal(location);
        if (cart == null || !cart.staffed || shop == null || !location.equals(shop.reader())
                || !shop.owner().equals(player.getUniqueId()) || terminal == null
                || !terminal.owner().equals(player.getUniqueId()) || terminal.pending() != 0) {
            player.sendMessage(ChatColor.RED + "Scan items at your ZipShop sign linked to this idle reader first.");
            return;
        }
        Block block = plugin.checkoutBlock(location);
        if (block == null || !(block.getState() instanceof Sign sign)
                || !ZipBankPlugin.text(sign.getSide(Side.FRONT).line(0)).equals("[ZipPay]")) {
            player.sendMessage(ChatColor.RED + "The reader is unavailable.");
            return;
        }
        showChoice(player, cart.location, null, cart);
    }

    private void display(Player player, String id) throws SQLException {
        String location = store.shopLocation(player.getUniqueId(), id);
        BankStore.Shop shop = location == null ? null : store.shop(location);
        Block block = location == null ? null : plugin.checkoutBlock(location);
        if (shop == null || shop.reader() == null || block == null || !(block.getState() instanceof Sign sign)
                || !ZipBankPlugin.text(sign.getSide(Side.FRONT).line(0)).equals("[ZipShop]")) {
            player.sendMessage(ChatColor.RED + "No configured shop with that ID belongs to you.");
            return;
        }
        giveDisplay(player, location);
    }

    void giveDisplay(Player player, String location) {
        Block block = plugin.checkoutBlock(location);
        if (block == null) {
            player.sendMessage(ChatColor.RED + "The shop world is unavailable.");
            return;
        }

        if (player.getInventory().firstEmpty() == -1) {
            player.sendMessage(ChatColor.RED + "Make room in your inventory, then request the display map again.");
            return;
        }
        MapView map = Bukkit.createMap(block.getWorld());
        installRenderer(map, location);
        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        meta.setMapView(map);
        meta.displayName(Component.text("ZipShop display"));
        meta.lore(List.of(Component.text("Right-click a wall near your shop sign.")));
        meta.getPersistentDataContainer().set(displayKey, PersistentDataType.STRING, location);
        mapItem.setItemMeta(meta);
        player.getInventory().addItem(mapItem);
        player.sendMessage(ChatColor.GREEN + "Received the checkout map. Right-click a wall within 8 blocks of your sign to place it.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMapPlace(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.getItem() == null || !(event.getItem().getItemMeta() instanceof MapMeta meta)) return;
        String location = meta.getPersistentDataContainer().get(displayKey, PersistentDataType.STRING);
        if (location == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        try {
            placeDisplay(player, event.getClickedBlock(), event.getBlockFace(), event.getItem(), meta, location);
        } catch (SQLException exception) {
            plugin.error(player, exception);
        }
    }

    private void placeDisplay(Player player, Block support, BlockFace face, ItemStack mapItem, MapMeta meta, String location) throws SQLException {
        BankStore.Shop shop = store.shop(location);
        Block signBlock = plugin.checkoutBlock(location);
        MapView map = meta.getMapView();
        if (shop == null || !player.getUniqueId().equals(shop.owner()) || signBlock == null
                || !(signBlock.getState() instanceof Sign sign)
                || !ZipBankPlugin.text(sign.getSide(Side.FRONT).line(0)).equals("[ZipShop]") || map == null) {
            player.sendMessage(ChatColor.RED + "This map no longer belongs to an available shop you own.");
            return;
        }
        Block target = support.getRelative(face);
        org.bukkit.util.BoundingBox area = org.bukkit.util.BoundingBox.of(target);
        if (face.getModY() != 0 || !support.getType().isSolid() || !target.isPassable()
                || !support.getWorld().equals(signBlock.getWorld())
                || support.getLocation().distanceSquared(signBlock.getLocation()) > 64
                || !target.getWorld().getNearbyEntities(area, entity -> entity instanceof ItemFrame).isEmpty()) {
            player.sendMessage(ChatColor.RED + "Use a clear wall within 8 blocks of the shop sign.");
            return;
        }
        ItemFrame frame = target.getWorld().spawn(target.getLocation().add(0.5, 0.5, 0.5), ItemFrame.class, entity -> {
            entity.setFacingDirection(face, true);
            entity.setVisible(false);
            entity.setRotation(Rotation.NONE);
        });
        HangingPlaceEvent placement = new HangingPlaceEvent(frame, player, support, face, EquipmentSlot.HAND, mapItem);
        plugin.getServer().getPluginManager().callEvent(placement);
        if (placement.isCancelled()) {
            frame.remove();
            player.sendMessage(ChatColor.RED + "You cannot place a shop display there.");
            return;
        }
        try {
            if (!store.setShopDisplay(location, player.getUniqueId(), map.getId(), frame.getUniqueId())) {
                frame.remove();
                player.sendMessage(ChatColor.RED + "Could not save the shop display.");
                return;
            }
        } catch (SQLException exception) {
            frame.remove();
            throw exception;
        }
        String oldFrame = shop.frame();
        if (oldFrame != null) {
            try { frames.remove(UUID.fromString(oldFrame)); }
            catch (IllegalArgumentException exception) { plugin.getLogger().warning("Invalid previous shop frame ID: " + oldFrame); }
        }
        installRenderer(map, location);
        frames.put(frame.getUniqueId(), location);
        frame.setItem(mapItem);
        player.getInventory().setItemInMainHand(null);
        player.sendMessage(ChatColor.GREEN + "Shop display installed. Customers can click its bottom-right PAY area.");
    }

    private void installRenderer(MapView map, String location) {
        for (MapRenderer renderer : new ArrayList<>(map.getRenderers())) map.removeRenderer(renderer);
        map.addRenderer(new Display(location));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFrameClick(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof ItemFrame frame)) return;
        String location = frames.get(frame.getUniqueId());
        if (location == null) return;
        event.setCancelled(true);
        RayTraceResult hit = event.getPlayer().getWorld().rayTraceEntities(event.getPlayer().getEyeLocation(),
                event.getPlayer().getEyeLocation().getDirection(), 8, entity -> entity.equals(frame));
        if (hit == null) {
            event.getPlayer().sendMessage(ChatColor.RED + "Look directly at the PAY area on the map.");
            return;
        }
        if (!isPayHit(frame.getFacing(), frame.getBoundingBox(), hit.getHitPosition())) {
            event.getPlayer().sendMessage(ChatColor.YELLOW + "Click PAY at the bottom right of the map.");
            return;
        }
        try {
            openChoice(event.getPlayer(), location, frame);
        } catch (SQLException exception) {
            plugin.error(event.getPlayer(), exception);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFrameDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame) || !frames.containsKey(frame.getUniqueId())) return;
        if (!(event.getDamager() instanceof Player player) || !canManageFrame(player, frame)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFrameBreak(HangingBreakByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame frame) || !frames.containsKey(frame.getUniqueId())) return;
        if (!(event.getRemover() instanceof Player player) || !canManageFrame(player, frame)) {
            event.setCancelled(true);
        }
    }

    private boolean canManageFrame(Player player, ItemFrame frame) {
        try {
            BankStore.Shop shop = store.shop(frames.get(frame.getUniqueId()));
            UUID owner = shop != null ? shop.owner() : null;
            if (player.getUniqueId().equals(owner) || player.hasPermission("zipbank.admin")) return true;
            player.sendMessage(ChatColor.RED + "Only the shop owner can remove this display.");
        } catch (SQLException exception) {
            plugin.error(player, exception);
        }
        return false;
    }

    static boolean validCartTotal(long base) {
        return base > 0 && base <= Money.MAX_TRANSACTION && base + BankStore.salesTax(base) <= Money.MAX_TRANSACTION;
    }

    static boolean isPayHit(BlockFace facing, BoundingBox bounds, Vector hit) {
        Vector pos = hit.clone().subtract(bounds.getCenter());
        double x = pos.getX() * facing.getModZ() - pos.getZ() * facing.getModX();
        double width = facing.getModZ() != 0 ? bounds.getWidthX() : bounds.getWidthZ();
        return x >= width * 0.25 && pos.getY() <= -bounds.getHeight() * 0.28125;
    }

    private void openChoice(Player player, String location, ItemFrame frame) throws SQLException {
        BankStore.Shop shop = store.shop(location);
        Cart cart = carts.get(player.getUniqueId());
        if (shop == null || shop.frame() == null || !shop.frame().equals(frame.getUniqueId().toString())
                || shop.mapId() == null || !(frame.getItem().getItemMeta() instanceof MapMeta meta)
                || !meta.hasMapId() || meta.getMapId() != shop.mapId()
                || cart == null || !cart.location.equals(location) || cart.base == 0) {
            player.sendMessage(ChatColor.RED + "Scan items at this checkout first, and ensure its display is installed.");
            return;
        }
        if (cart.staffed) {
            openStaffChoice(player, shop.reader());
            return;
        }
        Block shopBlock = plugin.checkoutBlock(location);
        Block reader = shop.reader() == null ? null : plugin.checkoutBlock(shop.reader());
        if (shopBlock == null || !(shopBlock.getState() instanceof Sign shopSign)
                || !ZipBankPlugin.text(shopSign.getSide(Side.FRONT).line(0)).equals("[ZipShop]")
                || reader == null || !(reader.getState() instanceof Sign sign)
                || !ZipBankPlugin.text(sign.getSide(Side.FRONT).line(0)).equals("[ZipPay]")) {
            player.sendMessage(ChatColor.RED + "The shop sign or linked reader is unavailable. Ask the owner to check it.");
            return;
        }
        showChoice(player, location, frame.getUniqueId(), cart);
    }

    private void showChoice(Player player, String location, UUID frame, Cart cart) {
        int boxes = ShopPacking.boxCount(cart.scanned.toArray(ItemStack[]::new));
        Choice choice = new Choice(player.getUniqueId(), location, frame, cart, boxes);
        choice.inventory = Bukkit.createInventory(choice, 27, Component.text("Purchase shulkers?"));
        long boxedBase = cart.base + boxes * 200L;
        choice.inventory.setItem(11, option(Material.SHULKER_BOX, "Yes: " + boxes + " shulker(s)",
                Money.format(boxes * 200L) + " before tax; total " + Money.format(boxedBase + BankStore.salesTax(boxedBase))));
        choice.inventory.setItem(15, option(Material.BARRIER, "No thanks",
                (cart.staffed ? "Deliver items; total " : "Keep items; total ") + Money.format(cart.base + BankStore.salesTax(cart.base))));
        player.openInventory(choice.inventory);
    }

    private ItemStack option(Material material, String name, String description) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        meta.lore(List.of(Component.text(description)));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(ignoreCancelled = true)
    public void onChoiceClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Choice choice)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !choice.customer.equals(player.getUniqueId())
                || (event.getRawSlot() != 11 && event.getRawSlot() != 15)) return;
        boolean boxes = event.getRawSlot() == 11;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != choice) return;
            player.closeInventory();
            try {
                activate(player, choice, boxes);
            } catch (SQLException exception) {
                plugin.error(player, exception);
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onChoiceDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Choice) event.setCancelled(true);
    }

    private void activate(Player player, Choice choice, boolean useBoxes) throws SQLException {
        Cart cart = carts.get(player.getUniqueId());
        if (cart == null || cart != choice.cart || cart.revision != choice.revision) {
            player.sendMessage(ChatColor.RED + "Your cart changed. Review it and try again.");
            return;
        }
        if (cart.staffed) {
            activateStaff(player, choice, useBoxes);
            return;
        }
        BankStore.Shop shop = store.shop(choice.location);
        org.bukkit.entity.Entity entity = Bukkit.getEntity(choice.frame);
        if (cart != choice.cart || cart.revision != choice.revision || shop == null
                || shop.frame() == null || !shop.frame().equals(choice.frame.toString())
                || !(entity instanceof ItemFrame frame)
                || shop.mapId() == null || !(frame.getItem().getItemMeta() instanceof MapMeta meta)
                || !meta.hasMapId() || meta.getMapId() != shop.mapId()
                || !player.getWorld().equals(frame.getWorld())
                || player.getLocation().distanceSquared(frame.getLocation()) > 64) {
            player.sendMessage(ChatColor.RED + "Your cart or checkout changed. Review it and press Pay again.");
            return;
        }
        Block shopBlock = plugin.checkoutBlock(choice.location);
        Block reader = shop.reader() == null ? null : plugin.checkoutBlock(shop.reader());
        if (shopBlock == null || !(shopBlock.getState() instanceof Sign shopSign)
                || !ZipBankPlugin.text(shopSign.getSide(Side.FRONT).line(0)).equals("[ZipShop]")
                || reader == null || !(reader.getState() instanceof Sign sign)
                || !ZipBankPlugin.text(sign.getSide(Side.FRONT).line(0)).equals("[ZipPay]")) {
            player.sendMessage(ChatColor.RED + "The shop sign or linked reader is unavailable.");
            return;
        }
        ItemStack[] scanned = cart.scanned.toArray(ItemStack[]::new);
        long base = cart.base + (useBoxes ? choice.boxes * 200L : 0);
        if (!validCartTotal(base)) {
            player.sendMessage(ChatColor.RED + "The total including shulkers and tax exceeds the checkout limit.");
            return;
        }
        byte[] items = null;
        if (useBoxes && choice.boxes > 0) {
            if (ShopPacking.pack(player.getInventory().getStorageContents(), scanned) == null) {
                player.sendMessage(ChatColor.RED + "Keep all scanned items in your inventory and make room for the shulkers.");
                return;
            }
            items = ItemStack.serializeItemsAsBytes(scanned);
        }
        if (!store.setShopPending(shop.reader(), shop.owner(), player.getUniqueId(), base, items)) {
            player.sendMessage(ChatColor.RED + "The reader is busy or unavailable. Ask the owner to clear it if necessary.");
            return;
        }
        long total = base + BankStore.salesTax(base);
        carts.remove(player.getUniqueId());
        plugin.updateSign(sign, total);
        player.sendMessage(ChatColor.GREEN + "Pay " + Money.format(total) + " at the linked ZipPay reader.");
    }

    private void activateStaff(Player player, Choice choice, boolean useBoxes) throws SQLException {
        Cart cart = choice.cart;
        BankStore.Shop shop = store.shop(choice.location);
        Block shopBlock = plugin.checkoutBlock(choice.location);
        String readerLocation = shop == null ? null : shop.reader();
        Block reader = readerLocation == null ? null : plugin.checkoutBlock(readerLocation);
        BankStore.Terminal terminal = readerLocation == null ? null : store.terminal(readerLocation);
        if (reader == null || !(reader.getState() instanceof Sign sign)
                || !ZipBankPlugin.text(sign.getSide(Side.FRONT).line(0)).equals("[ZipPay]")
                || shopBlock == null || !(shopBlock.getState() instanceof Sign shopSign)
                || !ZipBankPlugin.text(shopSign.getSide(Side.FRONT).line(0)).equals("[ZipShop]")
                || shop == null || !shop.owner().equals(player.getUniqueId())
                || terminal == null || !terminal.owner().equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "The reader is unavailable.");
            return;
        }
        ItemStack[] scanned = cart.scanned.toArray(ItemStack[]::new);
        if (ShopPacking.remove(player.getInventory().getStorageContents(), scanned) == null) {
            player.sendMessage(ChatColor.RED + "Keep every scanned item in your storage inventory before activating the reader.");
            return;
        }
        long base = cart.base + (useBoxes ? choice.boxes * 200L : 0);
        if (!validCartTotal(base)) {
            player.sendMessage(ChatColor.RED + "The total including shulkers and tax exceeds the checkout limit.");
            return;
        }
        if (!store.setStaffPending(readerLocation, player.getUniqueId(), base,
                ItemStack.serializeItemsAsBytes(scanned), useBoxes)) {
            player.sendMessage(ChatColor.RED + "The reader is busy or unavailable.");
            return;
        }
        carts.remove(player.getUniqueId());
        plugin.updateSign(sign, base + BankStore.salesTax(base));
        player.sendMessage(ChatColor.GREEN + "Reader ready for " + Money.format(base + BankStore.salesTax(base))
                + ". Keep the scanned items in your inventory until the customer pays.");
    }

    record Fulfillment(ItemStack[] customer, Player owner, ItemStack[] ownerContents) {
        void applyOwner() {
            if (owner != null) owner.getInventory().setStorageContents(ownerContents);
        }
    }

    Fulfillment preparePacking(Player player, String location, BankStore.Terminal terminal, ItemStack[] contents) throws SQLException {
        BankStore.StaffOrder order = store.staffOrder(location, terminal.token());
        if (order != null) {
            Player owner = Bukkit.getPlayer(terminal.owner());
            if (owner == null || !owner.isOnline()) {
                player.sendMessage(ChatColor.RED + "The shop owner must be online with the scanned items to complete payment.");
                return null;
            }
            ItemStack[] scanned = ItemStack.deserializeItemsFromBytes(order.items());
            ItemStack[] remaining = ShopPacking.remove(owner.getInventory().getStorageContents(), scanned);
            if (remaining == null) {
                player.sendMessage(ChatColor.RED + "The shop owner no longer has all scanned items. Ask them to restore the items or cancel the checkout.");
                owner.sendMessage(ChatColor.RED + "Restore all scanned items to your storage inventory before the customer pays.");
                return null;
            }
            ItemStack[] delivery = order.pack() ? ShopPacking.pack(scanned, scanned) : scanned;
            if (delivery == null) {
                player.sendMessage(ChatColor.RED + "Could not pack the scanned items. Ask the owner to cancel and retry.");
                return null;
            }
            ItemStack[] customer = ShopPacking.add(contents, delivery);
            if (customer == null) {
                player.sendMessage(ChatColor.RED + "Make room in your inventory for the purchase and any change, then retry.");
                return null;
            }
            return new Fulfillment(customer, owner, remaining);
        }
        byte[] items = store.pendingItems(location, terminal.token());
        if (items == null) return new Fulfillment(contents, null, null);
        ItemStack[] packed = ShopPacking.pack(contents, ItemStack.deserializeItemsFromBytes(items));
        if (packed == null) {
            player.sendMessage(ChatColor.RED + "Keep all scanned items in your inventory and make room for the shulkers, then try paying again.");
        }
        return packed == null ? null : new Fulfillment(packed, null, null);
    }

    void quit(UUID player) {
        carts.remove(player);
        pendingIds.remove(player);
    }

    void remove(String location) {
        frames.values().removeIf(location::equals);
        carts.values().removeIf(cart -> cart.location.equals(location));
    }

    private final class Display extends MapRenderer {
        private final String location;
        private final Map<Player, DisplayState> revisions = new java.util.WeakHashMap<>();

        Display(String location) { super(true); this.location = location; }

        @Override
        public void render(MapView view, MapCanvas canvas, Player player) {
            Cart cart = carts.get(player.getUniqueId());
            if (cart != null && !cart.location.equals(location)) cart = null;
            DisplayState state = new DisplayState(cart, cart == null ? -1 : cart.revision);
            if (state.equals(revisions.get(player))) return;
            revisions.put(player, state);
            BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 128, 128);
            graphics.setColor(new Color(80, 180, 90));
            graphics.fillRect(96, 100, 32, 28);
            graphics.dispose();
            canvas.drawImage(0, 0, image);
            if (cart == null) {
                String idle = "Scan Items To Start";
                canvas.drawText(Math.max(0, (128 - MinecraftFont.Font.getWidth(idle)) / 2), 60, MinecraftFont.Font, idle);
            } else {
                int start = Math.max(0, cart.lines.size() - 5);
                for (int i = start; i < cart.lines.size(); i++) {
                    Line line = cart.lines.get(i);
                    String label = (line.count() + "x " + line.name()).replaceAll("[^A-Za-z0-9 _-]", "?");
                    String amount = Money.format(line.total());
                    while (!label.isEmpty() && MinecraftFont.Font.getWidth(label + " " + amount) > 120) {
                        label = label.substring(0, label.length() - 1);
                    }
                    canvas.drawText(4, 17 + (i - start) * 11, MinecraftFont.Font, label + " " + amount);
                }
                canvas.drawText(4, 77, MinecraftFont.Font, "Base " + Money.format(cart.base));
                canvas.drawText(4, 88, MinecraftFont.Font, "Tax " + Money.format(BankStore.salesTax(cart.base)));
                canvas.drawText(4, 104, MinecraftFont.Font, "Total");
                canvas.drawText(4, 115, MinecraftFont.Font, Money.format(cart.base + BankStore.salesTax(cart.base)));
            }
            canvas.drawText(99, 110, MinecraftFont.Font, "PAY");
        }
    }
}
