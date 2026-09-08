# Decision Log

## Architecture at a glance

```
POST /api/v1/transactions/process
        |
        v
TransactionService (orchestrator, NOT transactional)
        | 1. claim()   : INSERT transaction_records row (PK = transactionId)  -> its own transaction
        | 2. execute() : atomic conditional UPDATE on wallets + store response -> its own transaction
        v
H2 (in-memory)
```

## 1. How did you handle the concurrency race condition?

Two different races had to be solved, with two different mechanisms:

**Race A — duplicate webhook deliveries (same transactionId).**
The `transaction_records` table uses `transactionId` as its **primary key**, so the
database itself guarantees a unique winner: every request first tries to `INSERT`
a `PENDING` row in its own committed transaction. Concurrent duplicates violate
the unique constraint (translated by Spring into `DataIntegrityViolationException`)
and are turned away. A duplicate then **polls** the record until it leaves
`PENDING` (original execution takes only a few milliseconds; the 5 s timeout only
guards against a crashed original) and receives the **exact cached JSON response**
the original caller got — satisfying "409 Conflict **or** the cached original response".

**Race B — simultaneous debits overdrawing the wallet.**
The balance is never read-then-written in Java. Debits run as a single atomic,
database-level conditional update:

```sql
UPDATE wallets SET balance = balance - :amount WHERE id = :id AND balance >= :amount
```

The check and the deduction happen under the row lock held by the `UPDATE`
itself, so 10 concurrent debits serialize at the database. The query returns the
number of rows updated: `0` rows means insufficient funds and **nothing was
deducted**. There is no application-level lock, no `synchronized`, no
`SELECT ... FOR UPDATE` — the database is the single source of truth, and the
solution works unchanged on PostgreSQL/MySQL in production.

**Why two separate transactions (claim vs. execute)?**
If a duplicate's constraint violation happened inside the same transaction as the
wallet update, JPA/Hibernate could mark the whole transaction rollback-only and
the winner's balance update would be lost. Keeping claim and execute in separate
proxy-invoked `@Transactional` methods makes the duplicate detection completely
side-effect-free.

## 2. Where did your AI assistant give you an incorrect or sub-optimal suggestion?

- **In-memory lock map (`ConcurrentHashMap<UUID, Object>` + `synchronized`)** —
  an early AI suggestion for both idempotency and balance protection. It is
  sub-optimal because it (a) only works within one JVM instance (breaks the
  moment the service scales horizontally), (b) leaks entries unless carefully
  cleaned up, and (c) is untestable-by-reviewers in a meaningful way. The
  database unique constraint + atomic conditional update replace it entirely.
- **`SELECT ... FOR UPDATE` then check balance in Java, then update** — the AI
  suggested this as the default pessimistic-locking pattern. It is correct but
  strictly worse than the single conditional `UPDATE`: it needs two round trips,
  holds the lock longer, and adds an entity lock to manage. I kept the simpler,
  faster atomic update.
- **Returning 409 for every duplicate** — the first AI draft threw `409` even
  when the original had already completed successfully. Returning the cached
  original response (`200` + identical body) is friendlier to payment-gateway
  retries, so the code supports both per the spec, preferring the replay.
