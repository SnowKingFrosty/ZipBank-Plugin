package com.zipbank;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

final class ShopPacking {
    private ShopPacking() { }

    static List<ItemStack> packedStacks(ItemStack[] scanned) {
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemStack item : scanned) {
            if (item == null || item.getType().isAir() || Tag.SHULKER_BOXES.isTagged(item.getType())) continue;
            int remaining = item.getAmount();
            for (ItemStack existing : stacks) {
                if (existing.isSimilar(item)) {
                    int moved = Math.min(remaining, existing.getMaxStackSize() - existing.getAmount());
                    existing.setAmount(existing.getAmount() + moved);
                    remaining -= moved;
                    if (remaining == 0) break;
                }
            }
            while (remaining > 0) {
                ItemStack stack = item.clone();
                int count = Math.min(remaining, stack.getMaxStackSize());
                stack.setAmount(count);
                stacks.add(stack);
                remaining -= count;
            }
        }
        return stacks;
    }

    static int boxCount(ItemStack[] scanned) {
        return boxesForStacks(packedStacks(scanned).size());
    }

    static int boxesForStacks(int stacks) {
        return stacks / 27 + (stacks % 27 == 0 ? 0 : 1);
    }

    static ItemStack[] pack(ItemStack[] contents, ItemStack[] scanned) {
        ItemStack[] result = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            result[i] = contents[i] == null ? null : contents[i].clone();
        }
        List<ItemStack> stacks = packedStacks(scanned);
        for (ItemStack requested : stacks) {
            int remaining = requested.getAmount();
            for (int slot = 0; slot < result.length && remaining > 0; slot++) {
                ItemStack held = result[slot];
                if (held == null || !held.isSimilar(requested)) continue;
                int count = Math.min(remaining, held.getAmount());
                remaining -= count;
                if (count == held.getAmount()) result[slot] = null;
                else held.setAmount(held.getAmount() - count);
            }
            if (remaining != 0) return null;
        }
        for (int start = 0; start < stacks.size(); start += 27) {
            ItemStack box = new ItemStack(Material.SHULKER_BOX);
            BlockStateMeta meta = (BlockStateMeta) box.getItemMeta();
            ShulkerBox state = (ShulkerBox) meta.getBlockState();
            for (int i = 0; i < 27 && start + i < stacks.size(); i++) {
                state.getSnapshotInventory().setItem(i, stacks.get(start + i));
            }
            meta.setBlockState(state);
            box.setItemMeta(meta);
            int empty = -1;
            for (int slot = 0; slot < result.length; slot++) {
                if (result[slot] == null || result[slot].getType().isAir()) {
                    empty = slot;
                    break;
                }
            }
            if (empty < 0) return null;
            result[empty] = box;
        }
        return result;
    }

    static ItemStack[] remove(ItemStack[] contents, ItemStack[] requested) {
        ItemStack[] result = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) result[i] = contents[i] == null ? null : contents[i].clone();
        for (ItemStack item : requested) {
            if (item == null || item.isEmpty()) continue;
            int remaining = item.getAmount();
            for (int slot = 0; slot < result.length && remaining > 0; slot++) {
                ItemStack held = result[slot];
                if (held == null || !held.isSimilar(item)) continue;
                int count = Math.min(remaining, held.getAmount());
                remaining -= count;
                if (count == held.getAmount()) result[slot] = null;
                else held.setAmount(held.getAmount() - count);
            }
            if (remaining > 0) return null;
        }
        return result;
    }

    static ItemStack[] add(ItemStack[] contents, ItemStack[] items) {
        ItemStack[] result = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) result[i] = contents[i] == null ? null : contents[i].clone();
        for (ItemStack item : items) {
            if (item == null || item.isEmpty()) continue;
            int remaining = item.getAmount();
            for (int pass = 0; pass < 2 && remaining > 0; pass++) {
                for (int slot = 0; slot < result.length && remaining > 0; slot++) {
                    ItemStack held = result[slot];
                    boolean empty = held == null || held.isEmpty();
                    if (pass == 0 && (empty || !held.isSimilar(item))) continue;
                    if (pass == 1 && !empty) continue;
                    int existing = empty ? 0 : held.getAmount();
                    int count = Math.min(remaining, item.getMaxStackSize() - existing);
                    if (count <= 0) continue;
                    if (empty) {
                        held = item.clone();
                        result[slot] = held;
                    }
                    held.setAmount(existing + count);
                    remaining -= count;
                }
            }
            if (remaining > 0) return null;
        }
        return result;
    }
}
