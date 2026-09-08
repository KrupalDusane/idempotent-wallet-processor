# Idempotent Wallet / Payment Event Processor

Spring Boot 3 (Java 17) + H2 in-memory. Handles duplicate payment-gateway
webhooks and concurrent debits without ever allowing a negative balance.

## Run the tests (this is how it will be evaluated)

```bash
mvn test
```

Zero external setup: H2 in-memory, schema auto-created, JUnit 5 +
`@DisplayName`, and every test prints its intent and result to the console.

## Run the app

```bash
mvn spring-boot:run
```

H2 console: http://localhost:8080/h2-console (JDBC URL `jdbc:h2:mem:walletdb`, user `sa`, no password).

## API

**Create / reset a wallet**

```bash
curl -X POST http://localhost:8080/api/v1/wallets \
  -H 'Content-Type: application/json' \
  -d '{"userId": "11111111-1111-1111-1111-111111111111", "balance": 500.00}'
```

**Process a transaction (idempotent)**

```bash
curl -X POST http://localhost:8080/api/v1/transactions/process \
  -H 'Content-Type: application/json' \
  -d '{"transactionId": "22222222-2222-2222-2222-222222222222",
       "userId": "11111111-1111-1111-1111-111111111111",
       "amount": 100.00, "type": "DEBIT"}'
```

| Situation | Status |
|---|---|
| Original request processed | `201 Created` |
| Duplicate (cached original response replayed) | `200 OK` |
| Duplicate while original in-flight / original failed | `409 Conflict` |
| Insufficient funds (distinct transactionId) | `422 Unprocessable Entity` |
| Validation error | `400 Bad Request` |

**Get wallet**

```bash
curl http://localhost:8080/api/v1/wallets/11111111-1111-1111-1111-111111111111
```

## How it works (short version)

1. **Idempotency:** `transactionId` is the PK of `transaction_records`. First
   request inserts a `PENDING` row; duplicates hit the unique constraint, wait
   for the original to finish, and receive the cached response JSON.
2. **No negative balances:** debits are a single atomic
   `UPDATE ... SET balance = balance - :amount WHERE balance >= :amount`
   executed at the database under the row lock.

See `DECISIONS.md` for the full decision log.
