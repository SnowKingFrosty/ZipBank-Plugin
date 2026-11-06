package com.zipbank;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class BankStore implements AutoCloseable {
    static final long MAX_BALANCE = 999_999_999_999L;
    private final Connection connection;

    record Terminal(UUID owner, long pending, String token) { }
    record WalletCard(UUID id, byte[] item) { }
    record Transaction(long id, long timestamp, String type, long amount, long balanceAfter) { }
    static final UUID GOVERNMENT = new UUID(0L, 0L);
    static final long TAX_PERCENT = 20;

    static long salesTax(long amount) {
        return amount * TAX_PERCENT / 100;
    }

    enum PinResult { OK, INVALID, LOCKED, UNSET, NOT_FOUND }

    BankStore(String url) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC", true, BankStore.class.getClassLoader());
        } catch (ClassNotFoundException exception) {
            throw new SQLException("SQLite driver not found", exception);
        }
        connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("CREATE TABLE IF NOT EXISTS accounts (owner TEXT PRIMARY KEY, balance INTEGER NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE IF NOT EXISTS cards (id TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES accounts(owner))");
            statement.execute("CREATE TABLE IF NOT EXISTS cash_wallet (owner TEXT NOT NULL, denomination INTEGER NOT NULL, quantity INTEGER NOT NULL CHECK(quantity >= 0), PRIMARY KEY(owner, denomination))");
            statement.execute("CREATE TABLE IF NOT EXISTS wallet_card (owner TEXT PRIMARY KEY, card_id TEXT NOT NULL UNIQUE REFERENCES cards(id), item BLOB NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS terminals (location TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES accounts(owner), pending INTEGER NOT NULL DEFAULT 0, checkout_id TEXT)");
            statement.execute("CREATE TABLE IF NOT EXISTS transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL REFERENCES accounts(owner), timestamp INTEGER NOT NULL, type TEXT NOT NULL, amount INTEGER NOT NULL, balance_after INTEGER NOT NULL)");
            boolean hasCheckoutId = false;
            try (ResultSet columns = statement.executeQuery("PRAGMA table_info(terminals)")) {
                while (columns.next()) {
                    if ("checkout_id".equals(columns.getString("name"))) {
                        hasCheckoutId = true;
                    }
                }
            }
            if (!hasCheckoutId) {
                statement.execute("ALTER TABLE terminals ADD COLUMN checkout_id TEXT");
            }
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS terminals_owner_checkout_id ON terminals(owner, checkout_id COLLATE NOCASE)");
            addColumnIfMissing(statement, "terminals", "pending_token", "TEXT");
            statement.execute("UPDATE terminals SET pending_token = lower(hex(randomblob(16))) WHERE pending != 0 AND pending_token IS NULL");
            addColumnIfMissing(statement, "cards", "pin_hash", "TEXT");
            addColumnIfMissing(statement, "cards", "failed_attempts", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(statement, "cards", "locked_until", "INTEGER NOT NULL DEFAULT 0");
        }
    }

    private void addColumnIfMissing(Statement statement, String table, String column, String type) throws SQLException {
        boolean found = false;
        try (ResultSet columns = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (columns.next()) {
                found |= column.equals(columns.getString("name"));
            }
        }
        if (!found) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }

    boolean createAccount(UUID owner) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT OR IGNORE INTO accounts(owner) VALUES (?)")) {
            statement.setString(1, owner.toString());
            return statement.executeUpdate() == 1;
        }
    }

    Long balance(UUID owner) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT balance FROM accounts WHERE owner = ?")) {
            statement.setString(1, owner.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : null;
            }
        }
    }

    boolean changeBalance(UUID owner, long amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE accounts SET balance = balance + ? WHERE owner = ? AND balance + ? BETWEEN 0 AND ?")) {
            statement.setLong(1, amount);
            statement.setString(2, owner.toString());
            statement.setLong(3, amount);
            statement.setLong(4, MAX_BALANCE);
            return statement.executeUpdate() == 1;
        }
    }

    Map<Long, Integer> walletContents(UUID owner) throws SQLException {
        Map<Long, Integer> contents = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT denomination, quantity FROM cash_wallet WHERE owner = ? AND quantity > 0")) {
            statement.setString(1, owner.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    contents.put(result.getLong(1), result.getInt(2));
                }
            }
        }
        return contents;
    }

    boolean changeWallet(UUID owner, long denomination, int change) throws SQLException {
        boolean valid = false;
        for (long value : CashDenominations.VALUES) {
            valid |= value == denomination;
        }
        if (!valid || change == 0) {
            return false;
        }
        if (change > 0) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO cash_wallet(owner, denomination, quantity) VALUES (?, ?, ?) "
                    + "ON CONFLICT(owner, denomination) DO UPDATE SET quantity = quantity + ? "
                    + "WHERE quantity + ? <= 2147483647")) {
                statement.setString(1, owner.toString());
                statement.setLong(2, denomination);
                statement.setInt(3, change);
                statement.setInt(4, change);
                statement.setInt(5, change);
                return statement.executeUpdate() == 1;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE cash_wallet SET quantity = quantity + ? WHERE owner = ? AND denomination = ? AND quantity + ? >= 0")) {
            statement.setInt(1, change);
            statement.setString(2, owner.toString());
            statement.setLong(3, denomination);
            statement.setInt(4, change);
            return statement.executeUpdate() == 1;
        }
    }

    UUID createCard(UUID owner) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO cards(id, owner) VALUES (?, ? )")) {
            statement.setString(1, id.toString());
            statement.setString(2, owner.toString());
            statement.executeUpdate();
        }
        return id;
    }

    boolean ownsCard(UUID owner, UUID card) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM cards WHERE id = ? AND owner = ?")) {
            statement.setString(1, card.toString());
            statement.setString(2, owner.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    WalletCard walletCard(UUID owner) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT card_id, item FROM wallet_card WHERE owner = ?")) {
            statement.setString(1, owner.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new WalletCard(UUID.fromString(result.getString(1)), result.getBytes(2)) : null;
            }
        }
    }

    boolean storeWalletCard(UUID owner, UUID card, byte[] item) throws SQLException {
        if (item == null || item.length == 0) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO wallet_card(owner, card_id, item) SELECT ?, ?, ? WHERE EXISTS (SELECT 1 FROM cards WHERE id = ? AND owner = ?)")) {
            statement.setString(1, owner.toString());
            statement.setString(2, card.toString());
            statement.setBytes(3, item);
            statement.setString(4, card.toString());
            statement.setString(5, owner.toString());
            return statement.executeUpdate() == 1;
        }
    }

    boolean removeWalletCard(UUID owner, UUID card) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM wallet_card WHERE owner = ? AND card_id = ?")) {
            statement.setString(1, owner.toString());
            statement.setString(2, card.toString());
            return statement.executeUpdate() == 1;
        }
    }

    boolean setCardPin(UUID owner, UUID card, String pin) throws SQLException {
        String hash = PinCode.hash(pin);
        try (PreparedStatement statement = connection.prepareStatement("UPDATE cards SET pin_hash = ? WHERE id = ? AND owner = ? AND pin_hash IS NULL")) {
            statement.setString(1, hash);
            statement.setString(2, card.toString());
            statement.setString(3, owner.toString());
            return statement.executeUpdate() == 1;
        }
    }

    boolean hasCardPin(UUID owner, UUID card) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pin_hash FROM cards WHERE id = ? AND owner = ?")) {
            statement.setString(1, card.toString());
            statement.setString(2, owner.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getString(1) != null;
            }
        }
    }

    PinResult verifyCardPin(UUID owner, UUID card, String pin, long now) throws SQLException {
        String hash;
        int failures;
        long lockedUntil;
        try (PreparedStatement statement = connection.prepareStatement("SELECT pin_hash, failed_attempts, locked_until FROM cards WHERE id = ? AND owner = ?")) {
            statement.setString(1, card.toString());
            statement.setString(2, owner.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return PinResult.NOT_FOUND;
                }
                hash = result.getString(1);
                failures = result.getInt(2);
                lockedUntil = result.getLong(3);
            }
        }
        if (hash == null) {
            return PinResult.UNSET;
        }
        if (lockedUntil > now) {
            return PinResult.LOCKED;
        }
        if (lockedUntil != 0) {
            failures = 0;
        }
        boolean correct = PinCode.matches(pin, hash);
        failures = correct ? 0 : failures + 1;
        long newLock = failures >= 5 ? now + 300_000 : 0;
        try (PreparedStatement statement = connection.prepareStatement("UPDATE cards SET failed_attempts = ?, locked_until = ? WHERE id = ? AND owner = ?")) {
            statement.setInt(1, failures);
            statement.setLong(2, newLock);
            statement.setString(3, card.toString());
            statement.setString(4, owner.toString());
            statement.executeUpdate();
        }
        return correct ? PinResult.OK : newLock != 0 ? PinResult.LOCKED : PinResult.INVALID;
    }

    boolean registerTerminal(String location, UUID owner) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT OR IGNORE INTO terminals(location, owner) VALUES (?, ? )")) {
            statement.setString(1, location);
            statement.setString(2, owner.toString());
            return statement.executeUpdate() == 1;
        }
    }

    boolean assignTerminalId(String location, UUID owner, String checkoutId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE OR IGNORE terminals SET checkout_id = ? WHERE location = ? AND owner = ? AND checkout_id IS NULL")) {
            statement.setString(1, checkoutId);
            statement.setString(2, location);
            statement.setString(3, owner.toString());
            return statement.executeUpdate() == 1;
        }
    }

    String terminalLocation(UUID owner, String checkoutId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT location FROM terminals WHERE owner = ? AND checkout_id = ? COLLATE NOCASE")) {
            statement.setString(1, owner.toString());
            statement.setString(2, checkoutId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    Terminal terminal(String location) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT owner, pending, pending_token FROM terminals WHERE location = ?")) {
            statement.setString(1, location);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new Terminal(UUID.fromString(result.getString(1)), result.getLong(2), result.getString(3)) : null;
            }
        }
    }

    void removeTerminal(String location) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM terminals WHERE location = ?")) {
            statement.setString(1, location);
            statement.executeUpdate();
        }
    }

    boolean setPending(String location, UUID owner, long amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE terminals SET pending = ?, pending_token = ? WHERE location = ? AND owner = ?")) {
            statement.setLong(1, amount);
            statement.setString(2, UUID.randomUUID().toString());
            statement.setString(3, location);
            statement.setString(4, owner.toString());
            return statement.executeUpdate() == 1;
        }
    }

    private boolean payToGovernment(long tax) throws SQLException {
        createAccount(GOVERNMENT);
        if (tax == 0) {
            return true;
        }
        // The government budget may go negative to cover refunded tax.
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE accounts SET balance = balance + ? WHERE owner = ? AND balance + ? <= ?")) {
            statement.setLong(1, tax);
            statement.setString(2, GOVERNMENT.toString());
            statement.setLong(3, tax);
            statement.setLong(4, MAX_BALANCE);
            return statement.executeUpdate() == 1;
        }
    }

    boolean transfer(UUID from, UUID to, long amount) throws SQLException {
        connection.setAutoCommit(false);
        try {
            if (from.equals(to) || amount <= 0 || !changeBalance(from, -amount) || !changeBalance(to, amount)) {
                connection.rollback();
                return false;
            }
            connection.commit();
            return true;
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    // Works while the budget is negative, unlike changeBalance.
    boolean depositToGovernment(UUID from, long amount) throws SQLException {
        connection.setAutoCommit(false);
        try {
            if (GOVERNMENT.equals(from) || amount <= 0 || !changeBalance(from, -amount) || !payToGovernment(amount)) {
                connection.rollback();
                return false;
            }
            connection.commit();
            return true;
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    // The government budget may go negative when paying out.
    boolean transferFromGovernment(UUID to, long amount) throws SQLException {
        connection.setAutoCommit(false);
        try {
            if (amount <= 0 || GOVERNMENT.equals(to) || balance(to) == null || !changeBalance(to, amount)
                    || !payToGovernment(-amount)) {
                connection.rollback();
                return false;
            }
            connection.commit();
            return true;
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    boolean settle(String location, UUID customer, String expectedToken) throws SQLException {
        connection.setAutoCommit(false);
        try {
            Terminal terminal = terminal(location);
                if (terminal == null || terminal.pending() == 0 || terminal.owner().equals(customer)
                    || expectedToken == null || !expectedToken.equals(terminal.token())) {
                connection.rollback();
                return false;
            }
            long amount = terminal.pending();
            UUID payer = amount > 0 ? customer : terminal.owner();
            UUID payee = amount > 0 ? terminal.owner() : customer;
            long positive = Math.abs(amount);
            long tax = salesTax(positive);
            // Charges send the tax to the government; refunds take it back from the government.
            boolean moved = amount > 0
                    ? changeBalance(payer, -positive) && changeBalance(payee, positive - tax) && payToGovernment(tax)
                    : changeBalance(payer, -(positive - tax)) && changeBalance(payee, positive) && payToGovernment(-tax);
            if (!moved
                    || !setPending(location, terminal.owner(), 0)) {
                connection.rollback();
                return false;
            }
            connection.commit();
            return true;
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    boolean settleCash(String location, UUID customer, String expectedToken) throws SQLException {
        connection.setAutoCommit(false);
        try {
            Terminal terminal = terminal(location);
            if (terminal == null || terminal.pending() <= 0 || terminal.owner().equals(customer)
                    || expectedToken == null || !expectedToken.equals(terminal.token())
                    || !changeBalance(terminal.owner(), terminal.pending() - salesTax(terminal.pending()))
                    || !payToGovernment(salesTax(terminal.pending()))
                    || !setPending(location, terminal.owner(), 0)) {
                connection.rollback();
                return false;
            }
            connection.commit();
            return true;
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    void logTransaction(UUID owner, String type, long amount) throws SQLException {
        Long balance = balance(owner);
        if (balance == null) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO transactions (owner, timestamp, type, amount, balance_after) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, owner.toString());
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, type);
            statement.setLong(4, amount);
            statement.setLong(5, balance);
            statement.executeUpdate();
        }
    }

    java.util.List<Transaction> getTransactionHistory(UUID owner, int limit) throws SQLException {
        java.util.List<Transaction> transactions = new java.util.ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, timestamp, type, amount, balance_after FROM transactions WHERE owner = ? ORDER BY id DESC LIMIT ?")) {
            statement.setString(1, owner.toString());
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    transactions.add(new Transaction(
                            result.getLong(1),
                            result.getLong(2),
                            result.getString(3),
                            result.getLong(4),
                            result.getLong(5)
                    ));
                }
            }
        }
        return transactions;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}