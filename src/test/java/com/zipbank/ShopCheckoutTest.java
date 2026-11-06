package com.zipbank;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.block.BlockFace;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

class ShopCheckoutTest {
    @Test
    void chargesOneShulkerPerTwentySevenStacks() {
        assertEquals(0, ShopPacking.boxesForStacks(0));
        assertEquals(1, ShopPacking.boxesForStacks(1));
        assertEquals(1, ShopPacking.boxesForStacks(27));
        assertEquals(2, ShopPacking.boxesForStacks(28));
        assertEquals(2, ShopPacking.boxesForStacks(54));
        assertEquals(3, ShopPacking.boxesForStacks(55));
        long base = 100 + ShopPacking.boxesForStacks(28) * 200L;
        assertEquals(600, base + BankStore.salesTax(base));
    }

    @Test
    void cartLimitIncludesTax() {
        assertFalse(ShopCheckout.validCartTotal(0));
        assertTrue(ShopCheckout.validCartTotal(833_333_333));
        assertFalse(ShopCheckout.validCartTotal(833_333_334));
        assertFalse(ShopCheckout.validCartTotal(Long.MAX_VALUE));
    }

    @Test
    void payAreaUsesBottomRightForEachWallDirection() {
        BoundingBox northSouth = new BoundingBox(-0.375, -0.375, -0.03, 0.375, 0.375, 0.03);
        BoundingBox eastWest = new BoundingBox(-0.03, -0.375, -0.375, 0.03, 0.375, 0.375);
        assertTrue(ShopCheckout.isPayHit(BlockFace.NORTH, northSouth, new Vector(-0.3, -0.3, -0.03)));
        assertTrue(ShopCheckout.isPayHit(BlockFace.SOUTH, northSouth, new Vector(0.3, -0.3, 0.03)));
        assertTrue(ShopCheckout.isPayHit(BlockFace.EAST, eastWest, new Vector(0.03, -0.3, -0.3)));
        assertTrue(ShopCheckout.isPayHit(BlockFace.WEST, eastWest, new Vector(-0.03, -0.3, 0.3)));
        assertFalse(ShopCheckout.isPayHit(BlockFace.NORTH, northSouth, new Vector(0.3, -0.3, -0.03)));
        assertFalse(ShopCheckout.isPayHit(BlockFace.NORTH, northSouth, new Vector(-0.3, 0.3, -0.03)));
    }
}
