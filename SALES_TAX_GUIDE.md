# Sales Tax Guide - 20% Tax System

## Overview
The ZipBank plugin now implements a **20% sales tax** system where:
- Shop owners enter the **base price** (price without tax)
- Tax is **automatically calculated** (base price × 20%)
- Customers pay the **total amount** (base price + tax)
- Tax is collected transparently at checkout (card tap or cash payment)

## How It Works

### For Shop Owners

#### Setting a Charge
```
/zippay charge <base_price> <checkout_id>
```

**Example:**
```
/zippay charge 50.00 mystore
```

This will:
- Set base price: $50.00
- Calculate tax: $50.00 × 20% = $10.00
- Total charge: $60.00
- Message: "Checkout set to $60.00 (including $10.00 tax)"

#### Setting a Refund
```
/zippay refund <base_amount> <checkout_id>
```

**Example:**
```
/zippay refund 30.00 mystore
```

This will:
- Set refund base: $30.00
- Calculate tax back: $30.00 × 20% = $6.00
- Total refund: $36.00
- Message: "Refund set to $36.00 (including $6.00 tax)"

### For Customers

#### Paying by Card
1. Shop owner sets a charge with `/zippay charge`
2. Customer right-clicks with debit card on the checkout sign
3. Customer sees the total amount (including tax)
4. Payment is processed - customer pays the full amount

#### Paying with Cash
1. Shop owner sets a charge
2. Customer right-clicks with cash items on the checkout sign
3. Customer taps each bill/coin until the amount is covered
4. Payment is processed with change given if needed

## Money Flow Example

### Charge of $100 base price
```
Input:      /zippay charge 100.00 store1
Tax calc:   $100.00 × 20% = $20.00
Total:      $100.00 + $20.00 = $120.00

Payment Settlement:
├── Customer pays:     $120.00
├── Shop owner gets:   $100.00 (base price)
└── Government gets:   $20.00 (tax)
```

### Refund of $50 base amount
```
Input:      /zippay refund 50.00 store1
Tax calc:   $50.00 × 20% = $10.00
Total:      $50.00 + $10.00 = $60.00

Payment Settlement:
├── Customer receives:   $60.00
├── Shop owner pays:     $50.00 (base price)
└── Government pays:     $10.00 (tax reversal)
```

## System Behaviors

### Tax Calculation
- **Formula:** `tax = base_price × 20 ÷ 100`
- **Rounding:** Uses integer division (amounts in cents, no fractions)
- **Applied to:** All charges and refunds

### Payment Priority
- **Card/Bank:** Charged to customer's bank account, tax deducted from shop owner's balance
- **Cash:** Physical cash items used, government portion tracked

### Display
- Checkout signs show the total amount due
- Messages include tax breakdown for transparency
- Example: "Checkout set to $60.00 (including $10.00 tax)"

## Common Scenarios

### 1. Selling an item for $25 (before tax)
```bash
/zippay charge 25 storeid
# Customer pays: $25 + ($25 × 20%) = $30
# You keep: $25
# Government gets: $5
```

### 2. Offering a refund of $15 (before tax)
```bash
/zippay refund 15 storeid
# Customer receives: $15 + ($15 × 20%) = $18
# You pay: $15
# Government refunds: $3
```

### 3. Clearing a charge
```bash
/zippay cancel storeid
# Removes any pending charge
```

## Tax Visibility
The system is designed for transparency:
- Messages show both total and tax amount
- Customers see the breakdown when viewing checkout amounts
- Shop owners can see in messages how much tax will be collected
- Government account tracks all tax revenue

## Technical Details
- Tax percentage: 20% (defined in `BankStore.java`)
- Applies to all payment methods (card, cash, transfers)
- Collected at settlement, not at charge time
- Government account receives all tax payments
