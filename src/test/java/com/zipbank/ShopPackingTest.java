package com.zipbank;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class ShopPackingTest {
    private static final class Stack extends ItemStack {
        private final Material material;
        private int amount;
        Stack(Material material, int amount) {
            this.material = material;
            this.amount = amount;
        }
        @Override public Material getType() { return material; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int amount) { this.amount = amount; }
        @Override public int getMaxStackSize() { return 64; }
        @Override public boolean isEmpty() { return amount <= 0; }
        @Override public boolean isSimilar(ItemStack other) { return other instanceof Stack stack && material == stack.material; }
        @Override public ItemStack clone() { return new Stack(material, amount); }
    }

    @Test
    void removesExactScannedQuantitiesWithoutChangingOriginal() {
        ItemStack[] inventory = {new Stack(Material.STONE, 32), new Stack(Material.STONE, 20), new Stack(Material.DIRT, 10)};
        ItemStack[] result = ShopPacking.remove(inventory, new ItemStack[] {new Stack(Material.STONE, 40)});
        assertNotNull(result);
        assertNull(result[0]);
        assertEquals(12, result[1].getAmount());
        assertEquals(10, result[2].getAmount());
        assertEquals(32, inventory[0].getAmount());
        assertNull(ShopPacking.remove(inventory, new ItemStack[] {new Stack(Material.STONE, 40), new Stack(Material.STONE, 13)}));
        assertEquals(20, inventory[1].getAmount());
    }

    @Test
    void addsDeliveryWithoutLossOrMutatingOriginal() {
        ItemStack[] inventory = {new Stack(Material.STONE, 60), null, new Stack(Material.DIRT, 64)};
        ItemStack[] result = ShopPacking.add(inventory, new ItemStack[] {new Stack(Material.STONE, 8)});
        assertNotNull(result);
        assertEquals(64, result[0].getAmount());
        assertEquals(4, result[1].getAmount());
        assertEquals(60, inventory[0].getAmount());
        assertNull(inventory[1]);
        assertNull(ShopPacking.add(inventory, new ItemStack[] {new Stack(Material.STONE, 70)}));
    }
}
