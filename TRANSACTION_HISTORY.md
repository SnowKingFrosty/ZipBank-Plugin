# Transaction History Feature

## Overview
This update adds a transaction history feature to the ZipBank ATM GUI, allowing players to view their past transactions.

## Changes Made

### 1. BankStore.java
- **New Record**: Added `Transaction` record class to represent a transaction with fields:
  - `id`: Unique transaction ID
  - `timestamp`: Milliseconds since epoch
  - `type`: Transaction type (e.g., "Cash Deposit", "Vault Withdrawal")
  - `amount`: Transaction amount (positive for deposits, negative for withdrawals)
  - `balanceAfter`: Bank balance after the transaction

- **New Table**: Created `transactions` SQLite table to store transaction history:
  ```sql
  CREATE TABLE IF NOT EXISTS transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner TEXT NOT NULL REFERENCES accounts(owner),
    timestamp INTEGER NOT NULL,
    type TEXT NOT NULL,
    amount INTEGER NOT NULL,
    balance_after INTEGER NOT NULL
  )
  ```

- **New Methods**:
  - `logTransaction(UUID owner, String type, long amount)`: Logs a transaction to the database
  - `getTransactionHistory(UUID owner, int limit)`: Retrieves the most recent transactions (limited to specified count)

### 2. ZipBankPlugin.java

- **New Holder Class**: Added `HistoryHolder` to represent the transaction history inventory

- **New Method**: `openTransactionHistory(Player player)`: Opens a transaction history GUI showing up to 28 recent transactions with:
  - Transaction type (e.g., "Cash Deposit")
  - Transaction timestamp (formatted as "yyyy-MM-dd HH:mm:ss")
  - Transaction amount (with + or - sign)
  - Bank balance after the transaction
  - Back button to return to the ATM

- **Modified ATM GUI**: Added a "Transaction History" button (Material.BOOK) at slot 24 in the ATM inventory

- **Updated Inventory Click Handler**: Added click handling for:
  - Transaction history button (slot 24) to open the history screen
  - Back button (slot 35) on the history screen to return to the ATM
  - Added `HistoryHolder` to the inventory click checks

- **Updated Inventory Drag Handler**: Added `HistoryHolder` to prevent dragging items in the transaction history inventory

- **Transaction Logging Calls**: Added logging for all transaction types:
  - Cash deposits: `store.logTransaction(player.getUniqueId(), "Cash Deposit", amount)`
  - Cash withdrawals: `store.logTransaction(player.getUniqueId(), "Cash Withdrawal", -amount)`
  - Vault deposits: `store.logTransaction(owner, "Vault Deposit", amount)`
  - Vault withdrawals: `store.logTransaction(owner, "Vault Withdrawal", -amount)`

## Usage

1. Right-click an ATM sign to open the ATM GUI
2. Click the "Transaction History" button (book icon) in the bottom-right area
3. View your recent transactions with timestamps, amounts, and balances
4. Click the back button to return to the ATM

## Transaction Types
- **Cash Deposit**: Money transferred from physical cash to bank account
- **Cash Withdrawal**: Money transferred from bank account to physical cash
- **Vault Deposit**: Money transferred from player wallet (Vault economy) to bank account
- **Vault Withdrawal**: Money transferred from bank account to player wallet

## Database Persistence
All transactions are permanently stored in the SQLite database (`plugins/ZipBank/zipbank.db`), allowing players to review their transaction history at any time.

## Backward Compatibility
- Existing databases without the transactions table will automatically have the table created on first run
- Players who upgrade will start logging transactions from that point forward
- The feature is fully read-only for players (no ability to modify or delete transactions)
