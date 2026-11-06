package com.zipbank;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BankStoreTest {
    @Test
    void persistsAccountsCardsAndTerminals(@TempDir Path directory) throws Exception {
        String database = "jdbc:sqlite:" + directory.resolve("bank.db");
        UUID owner = UUID.randomUUID();
        UUID firstCard;
        try (BankStore store = new BankStore(database)) {
            assertTrue(store.createAccount(owner));
            assertFalse(store.createAccount(owner));
            assertTrue(store.changeBalance(owner, 1250));
            firstCard = store.createCard(owner);
            assertNotEquals(firstCard, store.createCard(owner));
            assertTrue(store.registerTerminal("world:1:2:3", owner));
            assertTrue(store.setPending("world:1:2:3", owner, 500));
        }
        try (BankStore store = new BankStore(database)) {
            assertEquals(1250L, store.balance(owner));
            assertTrue(store.ownsCard(owner, firstCard));
            assertFalse(store.ownsCard(UUID.randomUUID(), firstCard));
            assertEquals(owner, store.terminal("world:1:2:3").owner());
            assertEquals(500, store.terminal("world:1:2:3").pending());
        }
    }

    @Test
    void chargesAndRefundsOnceAndRollsBackFailures() throws Exception {
        try (BankStore store = new BankStore("jdbc:sqlite::memory:")) {
            UUID shop = UUID.randomUUID();
            UUID customer = UUID.randomUUID();
            store.createAccount(shop);
            store.createAccount(customer);
            store.changeBalance(customer, 1000);
            store.registerTerminal("checkout", shop);
            assertFalse(store.setPending("checkout", customer, 100));
            assertTrue(store.setPending("checkout", shop, 600));
            assertTrue(store.settle("checkout", customer, store.terminal("checkout").token()));
            assertEquals(400L, store.balance(customer));
            assertEquals(480L, store.balance(shop));
            assertEquals(120L, store.balance(BankStore.GOVERNMENT));
            assertFalse(store.settle("checkout", customer, store.terminal("checkout").token()));
            assertTrue(store.setPending("checkout", shop, -250));
            assertTrue(store.settle("checkout", customer, store.terminal("checkout").token()));
            assertEquals(650L, store.balance(customer));
            assertEquals(280L, store.balance(shop));
            assertEquals(70L, store.balance(BankStore.GOVERNMENT));
            assertTrue(store.setPending("checkout", shop, -500));
            assertFalse(store.settle("checkout", customer, store.terminal("checkout").token()));
            assertEquals(650L, store.balance(customer));
            assertEquals(280L, store.balance(shop));
            assertEquals(-500L, store.terminal("checkout").pending());
            assertFalse(store.settle("checkout", shop, store.terminal("checkout").token()));
            assertTrue(store.changeBalance(shop, BankStore.MAX_BALANCE - 280));
            assertTrue(store.setPending("checkout", shop, 100));
            assertFalse(store.settle("checkout", customer, store.terminal("checkout").token()));
            assertEquals(650L, store.balance(customer));
            assertEquals(BankStore.MAX_BALANCE, store.balance(shop));
            assertEquals(100L, store.terminal("checkout").pending());
        }
    }

    @Test
    void refundsMayTakeGovernmentBudgetNegative() throws Exception {
        try (BankStore store = new BankStore("jdbc:sqlite::memory:")) {
            UUID shop = UUID.randomUUID();
            UUID customer = UUID.randomUUID();
            store.createAccount(shop);
            store.createAccount(customer);
            store.changeBalance(shop, 1000);
            store.registerTerminal("checkout", shop);
            assertTrue(store.setPending("checkout", shop, -1000));
            assertTrue(store.settle("checkout", customer, store.terminal("checkout").token()));
            assertEquals(-200L, store.balance(BankStore.GOVERNMENT));
            assertEquals(200L, store.balance(shop));
            assertEquals(1000L, store.balance(customer));
        }
    }

    @Test
    void playerAndGovernmentTransfers() throws Exception {
        try (BankStore store = new BankStore("jdbc:sqlite::memory:")) {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            store.createAccount(a);
            store.createAccount(b);
            store.changeBalance(a, 500);
            assertTrue(store.transfer(a, b, 200));
            assertFalse(store.transfer(a, b, 301));
            assertFalse(store.transfer(a, a, 1));
            assertEquals(300L, store.balance(a));
            assertEquals(200L, store.balance(b));
            assertTrue(store.transferFromGovernment(b, 100));
            assertEquals(300L, store.balance(b));
            assertEquals(-100L, store.balance(BankStore.GOVERNMENT));
            assertTrue(store.depositToGovernment(b, 250));
            assertEquals(150L, store.balance(BankStore.GOVERNMENT));
            assertEquals(50L, store.balance(b));
            assertFalse(store.depositToGovernment(b, 51));
            assertTrue(store.transferFromGovernment(b, 250));
            assertFalse(store.transferFromGovernment(UUID.randomUUID(), 100));
            assertEquals(-100L, store.balance(BankStore.GOVERNMENT));
        }
    }

    @Test
    void rejectsBalanceOverflowAndMissingAccounts() throws Exception {
        try (BankStore store = new BankStore("jdbc:sqlite::memory:")) {
            UUID owner = UUID.randomUUID();
            assertFalse(store.changeBalance(owner, 1));
            store.createAccount(owner);
            assertFalse(store.changeBalance(owner, -1));
            assertTrue(store.changeBalance(owner, BankStore.MAX_BALANCE));
            assertFalse(store.changeBalance(owner, 1));
        }
    }

    @Test
    void validatesMoneyWithoutRounding() {
        assertEquals(1, Money.parse("0.01"));
        assertEquals(1230, Money.parse("12.3"));
        assertEquals("-12.30", Money.format(-1230));
        for (String invalid : new String[] {"0", "-1", "+1", "1.001", "1e3", "10000000", "NaN", ""}) {
            assertThrows(IllegalArgumentException.class, () -> Money.parse(invalid), invalid);
        }
    }

    @Test
    void migratesExistingCheckoutsAndKeepsIdsUniquePerOwner(@TempDir Path directory) throws Exception {
        String database = "jdbc:sqlite:" + directory.resolve("legacy.db");
        UUID owner = UUID.randomUUID();
        UUID otherOwner = UUID.randomUUID();
        UUID oldCard = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(database); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE accounts (owner TEXT PRIMARY KEY, balance INTEGER NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE cards (id TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES accounts(owner))");
            statement.execute("CREATE TABLE terminals (location TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES accounts(owner), pending INTEGER NOT NULL DEFAULT 0)");
            statement.execute("INSERT INTO accounts VALUES ('" + owner + "', 1234)");
            statement.execute("INSERT INTO cards VALUES ('" + oldCard + "', '" + owner + "')");
            statement.execute("INSERT INTO terminals VALUES ('old-sign', '" + owner + "', 42)");
        }
        try (BankStore store = new BankStore(database)) {
            store.createAccount(otherOwner);
            assertEquals(owner, store.terminal("old-sign").owner());
            assertEquals(42, store.terminal("old-sign").pending());
            assertNotNull(store.terminal("old-sign").token());
            assertTrue(store.ownsCard(owner, oldCard));
            assertFalse(store.hasCardPin(owner, oldCard));
            assertTrue(store.setCardPin(owner, oldCard, "1234"));
            assertTrue(store.assignTerminalId("old-sign", owner, "Checkout"));
            assertEquals("old-sign", store.terminalLocation(owner, "checkout"));
            assertTrue(store.registerTerminal("second-sign", owner));
            assertFalse(store.assignTerminalId("second-sign", owner, "CHECKOUT"));
            assertFalse(store.assignTerminalId("old-sign", otherOwner, "other"));
            assertTrue(store.registerTerminal("other-sign", otherOwner));
            assertTrue(store.assignTerminalId("other-sign", otherOwner, "checkout"));
            assertEquals("other-sign", store.terminalLocation(otherOwner, "Checkout"));
        }
        try (BankStore store = new BankStore(database)) {
            assertEquals("old-sign", store.terminalLocation(owner, "checkout"));
            assertEquals(42, store.terminal("old-sign").pending());
        }
    }

    @Test
    void pinPersistsAndLocksAfterFiveWrongAttempts(@TempDir Path directory) throws Exception {
        String database = "jdbc:sqlite:" + directory.resolve("pins.db");
        UUID owner = UUID.randomUUID();
        UUID card;
        try (BankStore store = new BankStore(database)) {
            store.createAccount(owner);
            card = store.createCard(owner);
            assertFalse(store.hasCardPin(owner, card));
            assertEquals(BankStore.PinResult.UNSET, store.verifyCardPin(owner, card, "0000", 1));
            assertThrows(IllegalArgumentException.class, () -> store.setCardPin(owner, card, "123"));
            assertTrue(store.setCardPin(owner, card, "0123"));
            assertFalse(store.setCardPin(owner, card, "9999"));
            assertEquals(BankStore.PinResult.NOT_FOUND, store.verifyCardPin(UUID.randomUUID(), card, "0123", 1));
            for (int attempt = 0; attempt < 4; attempt++) {
                assertEquals(BankStore.PinResult.INVALID, store.verifyCardPin(owner, card, "9999", 100));
            }
            assertEquals(BankStore.PinResult.LOCKED, store.verifyCardPin(owner, card, "9999", 100));
        }
        try (BankStore store = new BankStore(database)) {
            assertTrue(store.hasCardPin(owner, card));
            assertEquals(BankStore.PinResult.LOCKED, store.verifyCardPin(owner, card, "0123", 200));
            assertEquals(BankStore.PinResult.OK, store.verifyCardPin(owner, card, "0123", 300_101));
            assertEquals(BankStore.PinResult.INVALID, store.verifyCardPin(owner, card, "9999", 300_102));
        }
        try (var connection = DriverManager.getConnection(database); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT pin_hash FROM cards WHERE id = '" + card + "'")) {
            assertTrue(result.next());
            assertNotEquals("0123", result.getString(1));
        }
    }

    @Test
    void rejectsChangedOrReplacedCheckoutTransaction() throws Exception {
        try (BankStore store = new BankStore("jdbc:sqlite::memory:")) {
            UUID shop = UUID.randomUUID();
            UUID customer = UUID.randomUUID();
            store.createAccount(shop);
            store.createAccount(customer);
            store.changeBalance(customer, 1000);
            store.registerTerminal("checkout", shop);
            store.setPending("checkout", shop, 100);
            String original = store.terminal("checkout").token();
            store.setPending("checkout", shop, 100);
            assertFalse(store.settle("checkout", customer, original));
            store.removeTerminal("checkout");
            store.registerTerminal("checkout", shop);
            store.setPending("checkout", shop, 100);
            assertFalse(store.settle("checkout", customer, original));
            assertEquals(1000L, store.balance(customer));
            assertTrue(store.settle("checkout", customer, store.terminal("checkout").token()));
        }
    }

    @Test
    void keypadConstrainsAmountsAndMasksPinLength() {
        KeypadInput amount = new KeypadInput(false);
        amount.decimal();
        amount.digit(0);
        amount.digit(1);
        amount.digit(2);
        assertEquals("0.01", amount.value());
        amount.backspace();
        assertEquals("0.0", amount.value());
        amount.clear();
        for (int digit = 0; digit < 9; digit++) {
            amount.digit(digit);
        }
        assertEquals("0123456", amount.value());
        KeypadInput pin = new KeypadInput(true);
        pin.digit(0);
        pin.digit(1);
        pin.digit(2);
        pin.digit(3);
        pin.digit(4);
        pin.decimal();
        assertEquals("0123", pin.value());
    }

    @Test
    void cashChangeUsesRequestedBillsAndCoins() {
        var change = CashDenominations.change(18_686);
        assertEquals(1L, change.get(10_000L));
        assertEquals(4L, change.get(2_000L));
        assertEquals(1L, change.get(500L));
        assertEquals(1L, change.get(100L));
        assertEquals(1L, change.get(50L));
        assertEquals(1L, change.get(25L));
        assertEquals(1L, change.get(10L));
        assertEquals(1L, change.get(1L));
        assertTrue(CashDenominations.change(0).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> CashDenominations.change(-1));
        for (long amount = 0; amount <= 25_000; amount++) {
            long total = CashDenominations.change(amount).entrySet().stream()
                .mapToLong(entry -> entry.getKey() * entry.getValue()).sum();
            assertEquals(amount, total);
        }
    }

    @Test
    void cashCheckoutCreditsShopOnceAndRejectsChangedCharges() throws Exception {
        try (BankStore store = new BankStore("jdbc:sqlite::memory:")) {
            UUID shop = UUID.randomUUID();
            UUID customer = UUID.randomUUID();
            store.createAccount(shop);
            store.createAccount(customer);
            store.changeBalance(customer, 1000);
            store.registerTerminal("checkout", shop);
            store.setPending("checkout", shop, 125);
            String original = store.terminal("checkout").token();
            store.setPending("checkout", shop, 125);
            assertFalse(store.settleCash("checkout", customer, original));
            String current = store.terminal("checkout").token();
            assertFalse(store.settleCash("checkout", shop, current));
            assertTrue(store.settleCash("checkout", customer, current));
            assertEquals(100L, store.balance(shop));
            assertEquals(1000L, store.balance(customer));
            assertFalse(store.settleCash("checkout", customer, current));
            assertFalse(store.settle("checkout", customer, current));
            store.setPending("checkout", shop, -125);
            assertFalse(store.settleCash("checkout", customer, store.terminal("checkout").token()));
            assertEquals(-125L, store.terminal("checkout").pending());
            assertTrue(store.changeBalance(shop, BankStore.MAX_BALANCE - 100));
            store.setPending("checkout", shop, 100);
            assertFalse(store.settleCash("checkout", customer, store.terminal("checkout").token()));
            assertEquals(100L, store.terminal("checkout").pending());
            assertEquals(BankStore.MAX_BALANCE, store.balance(shop));
        }
    }

    @Test
    void cashWalletPersistsPerPlayerAndRejectsInvalidWithdrawals(@TempDir Path directory) throws Exception {
        String database = "jdbc:sqlite:" + directory.resolve("wallet.db");
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try (BankStore store = new BankStore(database)) {
            assertTrue(store.changeWallet(owner, 100, 64));
            assertTrue(store.changeWallet(owner, 1, 25));
            assertFalse(store.changeWallet(owner, 2, 1));
            assertFalse(store.changeWallet(owner, 100, -65));
            assertFalse(store.changeWallet(other, 100, -1));
            assertTrue(store.changeWallet(owner, 100, -5));
            assertEquals(59, store.walletContents(owner).get(100L));
            assertTrue(store.walletContents(other).isEmpty());
        }
        try (BankStore store = new BankStore(database)) {
            assertEquals(59, store.walletContents(owner).get(100L));
            assertEquals(25, store.walletContents(owner).get(1L));
            assertTrue(store.changeWallet(owner, 100, -59));
            assertFalse(store.walletContents(owner).containsKey(100L));
            assertTrue(store.changeWallet(owner, 100, Integer.MAX_VALUE));
            assertFalse(store.changeWallet(owner, 100, 1));
            assertEquals(Integer.MAX_VALUE, store.walletContents(owner).get(100L));
        }
    }

    @Test
    void walletCardPersistsAndRequiresOwnership(@TempDir Path directory) throws Exception {
        String database = "jdbc:sqlite:" + directory.resolve("card-wallet.db");
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID card;
        byte[] item = {1, 2, 3, 4};
        try (BankStore store = new BankStore(database)) {
            store.createAccount(owner);
            store.createAccount(other);
            card = store.createCard(owner);
            assertTrue(store.setCardPin(owner, card, "0123"));
            UUID second = store.createCard(owner);
            assertFalse(store.storeWalletCard(other, card, item));
            assertFalse(store.storeWalletCard(owner, UUID.randomUUID(), item));
            assertFalse(store.storeWalletCard(owner, card, new byte[0]));
            assertTrue(store.storeWalletCard(owner, card, item));
            assertFalse(store.storeWalletCard(owner, second, item));
            assertNull(store.walletCard(other));
            assertFalse(store.removeWalletCard(other, card));
            assertFalse(store.removeWalletCard(owner, second));
        }
        try (BankStore store = new BankStore(database)) {
            assertEquals(card, store.walletCard(owner).id());
            assertArrayEquals(item, store.walletCard(owner).item());
            assertEquals(BankStore.PinResult.OK, store.verifyCardPin(owner, card, "0123", 100));
            assertTrue(store.removeWalletCard(owner, card));
            assertNull(store.walletCard(owner));
            assertTrue(store.storeWalletCard(owner, card, item));
        }
    }
}