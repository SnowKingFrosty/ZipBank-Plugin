# ZipBank

ZipBank is a Paper 26.3 plugin with bank accounts, ATM signs, debit cards, and player-owned checkout signs.

## Install

Requires Java 25, Paper 26.3, Vault, and a Vault-compatible economy plugin. Build with `mvn package` and place `target/zipbank-1.0.0.jar` in the server's `plugins` folder. Restart the server. SQLite is included in the plugin; account and checkout data are stored in `plugins/ZipBank/zipbank.db`. Back up this database regularly while the server is stopped.

## ATM

With `zipbank.atm.create` (operators by default), write `[ZipATM]` on the first line of the front of a sign. Right-click it to open the ATM. Players can create one account and choose Deposit, Withdraw, History, or Debit Card. Deposit and Withdraw each offer Vault wallet and cash options. Cash withdrawal requires enough inventory space; depositing cash deposits all cash in the storage inventory and excludes the offhand and armor slots. Choose a four-digit PIN in chat when requesting a card, or type `cancel`. The card is issued after the PIN is set. Each card has its own ID and PIN and can only be used by its account owner. Keep cards safe; issuing a new card does not revoke older cards.

ZipBank cancels PIN setup chat messages so they are not broadcast, but other server plugins or logging tools may still see or record them. PINs are stored as salted hashes. After five incorrect payment PIN attempts, that card is locked for five minutes. Cards issued before PINs were added prompt their owner to set one the first time they are tapped; the owner must hold the card and tap again after setup.

Configure new debit cards in `plugins/ZipBank/config.yml` using `debit-card.material` (a Bukkit material name), `debit-card.name` (supports `&` color codes), and `debit-card.custom-model-data` (a non-negative integer, or `null` for no custom model). Restart the server after changing the file. Existing cards retain their appearance and remain valid.

Cash is issued as iron nuggets for 1, 10, 25 and 50 cents, and iron ingots for 1, 5, 10, 20 and 100 currency units. Configure each denomination's name and custom model data under `cash` in the same file. A resource pack is needed for custom appearances. Only ZipBank-issued, tagged items count as cash; ordinary iron nuggets and ingots do not. Previously issued cash remains valid after changing its appearance. Cash uses the same bank balance that can be exchanged with the Vault wallet; the Vault economy must support at least two decimal places (or unlimited precision).

## Cash Wallet

Use `/zippay wallet` to open your personal cash storage. With an empty cursor, left-click a cash stack in your inventory to store the stack, or right-click it to store one item. In the wallet, left-click a denomination to take one item or right-click it to take up to 64; shift-click also takes up to 64. Only ZipBank cash is accepted, and changes are saved immediately. Cash is stored by denomination and uses the current configured appearance when taken out. This storage is separate from your bank balance and Vault wallet: take cash out before using it at an ATM or checkout.

The separated bottom row holds one debit card. With an empty cursor, click a card in your inventory to store it, then click the card in the wallet to retrieve it. Only a card registered to you can be stored, and its ID, PIN, and item appearance are preserved. Take it out before tapping a checkout sign.

## Checkout

Create an account first, then write `[ZipPay]` on the first line of a sign. The sign creator owns the checkout; line two becomes `Tap Card Here`. Enter a checkout ID in chat when prompted. IDs are 1-16 letters, numbers, underscores, or hyphens; they are case-insensitive and must be unique among your own checkouts. Type `cancel` to defer assignment. The `zipbank.pay.create` permission defaults to everyone.

Use the checkout ID from anywhere, provided the sign's world is loaded:

- `/zippay charge <amount> <id>` to request a payment from a customer.
- `/zippay refund <amount> <id>` to request a refund from your bank balance to a customer; the sign displays a negative amount.
- `/zippay cancel <id>` to clear the pending amount.
- `/zippay assign <id>` while looking at an unassigned checkout within eight blocks to assign its ID.

Existing checkout signs retain their balances and pending amounts after upgrading, but need an ID assigned with `/zippay assign <id>` before they can be managed with the new commands. The same command works after cancelling the chat prompt or disconnecting during setup.

The customer right-clicks the sign holding their own debit card in their main hand, then enters the card PIN on the masked keypad. A successful confirmation transfers money between bank accounts and clears the pending amount, so it cannot be paid twice. If the amount changes while the customer is entering their PIN, they must tap again to review it. Failed transactions leave the amount pending. Owners can pay their own readers only for a self-purchase reserved for them through ZipShop. Only the owner or someone with `zipbank.admin` (operators by default) can break a checkout. Each sign has its own pending amount; setting a new amount replaces the previous one.

To pay a positive charge with cash, right-click the sign once per bill or coin held in the main hand. The action bar shows the remaining amount. Selected cash stays in the player's inventory until enough has been selected; then it is removed, the shop's bank account is credited, and any change is placed in the player's inventory. If there is not enough room for change, make room and tap the final bill or coin again. Partial selections are discarded on disconnect, checkout changes, or switching signs; no cash is consumed. Cash cannot collect refunds; use a debit card for refunds.

### Staffed checkout

Create and link a `[ZipShop]` scanner and map to your `[ZipPay]` reader as described below. The customer hands their items to the owner. The owner holds each item or stack and right-clicks the `[ZipShop]` sign to scan it; each item must have been priced by that owner with `/zipshop price <amount>`. Each click scans the entire held stack; repeat scans of the same item and price merge into one line with a combined quantity. The idle map shows "Scan Items To Start". To discard an unsubmitted cart, use `/zipshop clear`.

The owner sees their scans on the `[ZipShop]` map and presses **PAY** to choose whether the customer wants shulkers; `/zippay checkout <reader-id>` opens the same choice without a map. Each shulker costs 2.00 before tax and holds up to 27 stacks; items that cannot be nested are delivered separately. The `[ZipPay]` sign then displays the total including tax. Whoever successfully pays that reader receives the scanned items, packed if selected. The owner must remain online with the full scanned quantities in their storage inventory; the customer needs enough storage space for the purchase and any cash change. If either condition is not met, payment fails without moving items or money. The owner can cancel the pending transaction with `/zippay cancel <reader-id>`. A pending staffed order persists across restarts until paid, cancelled, or replaced.

To buy items at their own shop instead, the owner scans them and selects a **Buy for yourself** option in the same choice menu. The reader is reserved for the owner, who can pay by card or cash; the items remain in their inventory or are packed if shulkers were selected. The normal price and sales tax apply: the shop receives the base amount and the government receives the tax. Card payment requires the full total in the bank account before the base amount is credited back.

## ZipShop self-checkout

ZipShop is an **honor system**: it does not prevent theft, remove goods from customers, or verify that everything taken from a chest was scanned. Priced items retain their shop tags after payment. Owners should protect their shop and use the feature only where they trust customers.

1. Create a bank account and a `[ZipPay]` reader with an ID. The `zipbank.pay.create` permission is also required for ZipShop.
2. Hold an item or stack and use `/zipshop price <amount>`. The amount is the price **per item before tax**; the whole held stack receives the price. Place the items in a chest for customers. Only the original owner can reprice tagged items.
3. Place a sign with `[ZipShop]` on its first line. You receive a shop map, then enter a unique shop checkout ID in chat followed by the ID of your existing ZipPay reader, which must be within eight blocks. IDs follow the same rules as ZipPay IDs. If setup is cancelled or interrupted, look at the sign and use `/zipshop setup <shop-id> <reader-id>` to complete or change it.
4. Hold the shop map and right-click a clear wall within eight blocks of the sign. An invisible item frame displays the map. Only the owner or an admin can remove the display. If you did not receive the map because your inventory was full, or need a replacement, use `/zipshop display <shop-id>` after configuring the sign.
5. A customer holds an item or stack and right-clicks the shop sign once to scan the entire held stack. A successful scan plays a note-block bit beep. Every click adds another scan, up to 256 scans; the customer can use `/zipshop clear` to start over. The map shows that customer's five most recent scans, subtotal, tax, and total; other customers see their own carts. Tax is 20% of the cart subtotal, rounded down to the cent. The total including tax cannot exceed 9999999.99.
6. The customer right-clicks the bottom-right **PAY** area and chooses whether to buy shulkers. Each shulker costs 2.00 before tax and holds up to 27 stacks; the checkout calculates how many are needed. Items that cannot be nested, such as shulker boxes, remain in the player's inventory. Choosing no leaves all items in the inventory without a shulker charge.
7. The customer pays the displayed amount by debit card or cash at the linked ZipPay reader. If shulkers were selected, all scanned packable items must still be in the player's storage inventory, and there must be room for the resulting shulkers and any cash change. This is checked before payment and again at the reader. After a successful payment the items are packed into the shulkers in the player's inventory. Duplicate scans require the corresponding quantity of items. The reader must be idle and is reserved for that customer until payment succeeds or the owner clears it with `/zippay cancel <reader-id>`. Scans are cleared when the reader is activated or the customer disconnects; an activated reader and its packing request remain reserved after disconnect or restart. The owner can replace or cancel its charge using the normal ZipPay commands.

Shop signs, IDs, reader links, and map associations persist across restarts. If the sign, reader, or map is removed, repair or reconfigure it before accepting payments. Each map is associated with its installed frame; copying the map does not create another checkout.

Minecraft inventories and SQLite cannot be updated in one atomic transaction. A server crash during payment and shulker packing may require manual reconciliation.

Amounts must be positive, with at most two decimal places, and no greater than 9999999.99. Bank balances are limited to 9999999999.99. Your economy provider may impose additional precision or balance restrictions.

Vault, player inventories, and SQLite cannot share a transaction: an abrupt server failure during a transfer involving Vault or physical cash can require manual reconciliation. The plugin attempts to reverse failed transfers and logs failed wallet reversals. Card checkout payments and refunds are atomic within SQLite. Protect your server against item duplication and untrusted plugins or operators that can create tagged items; physical cash cannot prevent such duplication.

## Sales Tax and Government Budget

Every checkout charge (card or cash) carries a 20% sales tax, rounded down to the cent. Shop owners enter the base price (without tax); the system automatically calculates and adds the tax at checkout. The customer pays the full amount (base + tax); the tax is deducted before the shop owner is credited and goes to a separate "Government Budget" account that players cannot use. Refunds reverse the tax: the customer receives the full refunded amount (base + tax), the shop owner pays the base amount, and the government budget pays the tax portion back. The budget is allowed to go negative.

### Pricing Example
When you use `/zippay charge 100 store1`:
- Base price you enter: $100.00
- Tax calculated: $100.00 × 20% = $20.00
- Total customer pays: $120.00
- You receive: $100.00
- Government receives: $20.00

- `/zippay transfer <amount> <player>` sends money from your bank balance to another player's account (no tax). The recipient must already have a ZipBank account.

Anyone can view the budget balance; only admins (`zipbank.admin`) can change it:

- (All players) `/zippay government` shows the government budget balance.
- `/zippay government deposit <amount>` moves money from your bank balance into the budget.
- `/zippay government transfer <amount> <player>` pays a player from the budget.
