# PACTO — API Reference

## Overview

- Base URL: http://localhost:8080
- Format: JSON
- Authentication: Not implemented in the demo MVP
- Currency: Virtual credits
- Error format: Problem Details for handled domain errors

## Wallet

### Get wallet balance

GET /api/users/{userId}/wallet/balance

Returns the available, reserved and total balances
of a user's wallet.

#### Response — 200 OK

{
    "userId": "uuid",
    "walletId": "uuid",
    "availableBalance": 100,
    "reservedBalance": 0,
    "totalBalance": 100
}

#### Errors

- 400: Invalid UUID.
- 404: User not found.
- 500: Inconsistent internal wallet state.

### Get wallet statement

GET /api/users/{userId}/wallet/statement

#### Query parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| page | 0 | Zero-based page number |
| size | 20 | Page size, between 1 and 100 |

#### Response — 200 OK

{
    "content": [
        {
            "entryId": "uuid",
            "transactionId": "uuid",
            "kind": "INITIAL_GRANT",
            "accountType": "USER_AVAILABLE",
            "delta": 100,
            "createdAt": "2026-11-24T18:00:00Z"
        }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
}

#### Errors

- 400: Invalid UUID or pagination parameters.
- 404: User not found.
- 500: Inconsistent internal wallet state.

#### Pagination rules

- Page numbering starts at zero.
- Maximum page size is 100.
- Offset must not exceed Integer.MAX_VALUE.
- Pages outside the result range return empty content.
- Entries are ordered by createdAt DESC and id ASC.
- System issuance entries are excluded.
- Offset pagination may shift when new entries are added.

## Financial guarantees

- Read operations do not create ledger entries.
- Wallet balances are retrieved from ledger accounts.
- Statements are retrieved from ledger entries.
- Reserved balances include all wallet escrow accounts.
- Total balance equals available plus reserved balance.

## Security limitations

These endpoints are intended for demonstration purposes.
Authentication and authorization are not implemented.
A user ID is not an authentication credential.
