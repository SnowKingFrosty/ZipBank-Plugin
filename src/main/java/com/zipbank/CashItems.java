package com.zipbank;

import java.util.LinkedHashMap;
import java.util.Map;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

final class CashItems {
    private static final String[] KEYS = {"bill-100", "bill-20", "bill-10", "bill-5", "bill-1", "coin-50", "coin-25", "coin-10", "coin-1"};
    private final Map<Long, ItemStack> templates = new LinkedHashMap<>();
    private final NamespacedKey valueKey;

    CashItems(JavaPlugin plugin) {
        valueKey = new NamespacedKey(plugin, "cash-value");
        FileConfiguration config = plugin.getConfig();
        for (int index = 0; index < CashDenominations.VALUES.length; index++) {
            long value = CashDenominations.VALUES[index];
            String path = "cash." + KEYS[index];
            Object model = config.get(path + ".custom-model-data");
            if (!(model instanceof Integer) || (Integer) model < 0) {
                throw new IllegalArgumentException(path + ".custom-model-data must be a non-negative integer.");
            }
            String name = config.getString(path + ".name", (value >= 100 ? "Bill " : "Coin ") + Money.format(value));
            ItemStack item = new ItemStack(value >= 100 ? Material.IRON_INGOT : Material.IRON_NUGGET);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(name));
            meta.setCustomModelData((Integer) model);
            meta.getPersistentDataContainer().set(valueKey, PersistentDataType.LONG, value);
            item.setItemMeta(meta);
            templates.put(value, item);
        }
    }

    long value(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 0;
        }
        Long value = item.getItemMeta().getPersistentDataContainer().get(valueKey, PersistentDataType.LONG);
        if (value == null || !templates.containsKey(value)
                || item.getType() != (value >= 100 ? Material.IRON_INGOT : Material.IRON_NUGGET)) {
            return 0;
        }
        return value;
    }

    Map<Long, Integer> counts(ItemStack[] contents) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (ItemStack item : contents) {
            long value = value(item);
            if (value != 0) {
                counts.merge(value, item.getAmount(), Integer::sum);
            }
        }
        return counts;
    }

    ItemStack[] remove(ItemStack[] contents, Map<Long, Integer> counts) {
        ItemStack[] result = copy(contents);
        Map<Long, Integer> remaining = new LinkedHashMap<>(counts);
        for (int slot = 0; slot < result.length; slot++) {
            ItemStack item = result[slot];
            long value = value(item);
            int needed = remaining.getOrDefault(value, 0);
            if (value == 0 || needed <= 0) {
                continue;
            }
            int removed = Math.min(needed, item.getAmount());
            remaining.put(value, needed - removed);
            if (removed == item.getAmount()) {
                result[slot] = null;
            } else {
                item.setAmount(item.getAmount() - removed);
            }
        }
        return remaining.values().stream().allMatch(count -> count == 0) ? result : null;
    }

    ItemStack[] add(ItemStack[] contents, long amount) {
        ItemStack[] result = copy(contents);
        for (Map.Entry<Long, Long> entry : CashDenominations.change(amount).entrySet()) {
            result = addDenomination(result, entry.getKey(), entry.getValue());
            if (result == null) {
                return null;
            }
        }
        return result;
    }

    ItemStack displayTemplate(long value) {
        ItemStack template = templates.get(value);
        if (template == null) {
            return null;
        }
        ItemStack display = template.clone();
        ItemMeta meta = display.getItemMeta();
        meta.getPersistentDataContainer().remove(valueKey);
        display.setItemMeta(meta);
        return display;
    }

    ItemStack[] addDenomination(ItemStack[] contents, long value, long quantity) {
        ItemStack template = templates.get(value);
        if (template == null || quantity < 0) {
            throw new IllegalArgumentException("Invalid cash denomination or quantity");
        }
        ItemStack[] result = copy(contents);
        long remaining = quantity;
        for (int slot = 0; slot < result.length && remaining > 0; slot++) {
            ItemStack current = result[slot];
            if (current != null && !current.getType().isAir() && !current.isSimilar(template)) {
                continue;
            }
            int existing = current == null || current.getType().isAir() ? 0 : current.getAmount();
            int added = (int) Math.min(remaining, template.getMaxStackSize() - existing);
            if (added <= 0) {
                continue;
            }
            if (existing == 0) {
                current = template.clone();
                result[slot] = current;
            }
            current.setAmount(existing + added);
            remaining -= added;
        }
        return remaining == 0 ? result : null;
    }

    private ItemStack[] copy(ItemStack[] contents) {
        ItemStack[] result = new ItemStack[contents.length];
        for (int slot = 0; slot < contents.length; slot++) {
            result[slot] = contents[slot] == null ? null : contents[slot].clone();
        }
        return result;
    }
}