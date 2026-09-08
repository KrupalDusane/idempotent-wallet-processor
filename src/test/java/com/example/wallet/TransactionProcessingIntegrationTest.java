package com.example.wallet;

import com.example.wallet.dto.ProcessTransactionRequest;
import com.example.wallet.model.TransactionStatus;
import com.example.wallet.model.TransactionType;
import com.example.wallet.repo.TransactionRecordRepository;
import com.example.wallet.repo.WalletRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Zero-config integration tests: Spring Boot + H2 in-memory + MockMvc.
 * The reviewer only needs to run: mvn test
 *
 * Every test prints its INTENT at the start and its RESULT at the end so the
 * console output is self-documenting.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransactionProcessingIntegrationTest {

    private static final String PROCESS_URL = "/api/v1/transactions/process";
    private static final String WALLET_URL = "/api/v1/wallets";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransactionRecordRepository recordRepository;

    @BeforeEach
    void resetDatabase() {
        recordRepository.deleteAll();
        walletRepository.deleteAll();
    }

    // ------------------------------------------------------------------
    // TEST 1 — Happy Path
    // ------------------------------------------------------------------
    @Test
    @DisplayName("Happy Path: Processes a single valid debit transaction successfully")
    void processesSingleValidDebit() throws Exception {
        System.out.println();
        System.out.println("[TEST 1 - HAPPY PATH]");
        System.out.println("[TEST 1] Intent  : Send one valid DEBIT of 100.00 to a wallet holding 500.00.");
        System.out.println("[TEST 1] Expect : HTTP 201, status=PROCESSED, balanceAfter=400.00.");

        UUID userId = createWallet(new BigDecimal("500.00"));
        String payload = debitPayload(UUID.randomUUID(), userId, "100.00");

        mockMvc.perform(post(PROCESS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROCESSED"))
                .andExpect(jsonPath("$.amount").value(100.00))
                .andExpect(jsonPath("$.balanceAfter").value(400.00));

        assertThat(walletRepository.findByUserId(userId).orElseThrow().getBalance())
                .as("wallet balance after the debit")
                .isEqualByComparingTo("400.00");
        assertThat(recordRepository.countByStatus(TransactionStatus.PROCESSED)).isEqualTo(1);

        System.out.println("[TEST 1] Result : PASSED - 201 returned, balance went 500.00 -> 400.00, one PROCESSED record stored.");
    }

    // ------------------------------------------------------------------
    // TEST 2 — Idempotency
    // ------------------------------------------------------------------
    @Test
    @DisplayName("Idempotency: 3 identical transactionIds sent simultaneously - balance is deducted exactly once")
    void identicalTransactionIdsAreProcessedExactlyOnce() throws Exception {
        System.out.println();
        System.out.println("[TEST 2 - IDEMPOTENCY]");
        System.out.println("[TEST 2] Intent  : Fire 3 requests with the SAME transactionId (100.00 DEBIT, wallet=500.00) at the same instant.");
        System.out.println("[TEST 2] Expect  : exactly 1x HTTP 201, the other 2x HTTP 200 (cached replay) or 409; balance deducted ONCE (400.00).");

        UUID userId = createWallet(new BigDecimal("500.00"));
        UUID sharedTransactionId = UUID.randomUUID();
        String payload = debitPayload(sharedTransactionId, userId, "100.00");

        CountDownLatch fire = new CountDownLatch(1);
        List<Integer> statuses = fireConcurrentRequests(3, fire, () -> payload);

        long succeeded = statuses.stream().filter(s -> s == 201).count();
        long duplicates = statuses.stream().filter(s -> s == 200 || s == 409).count();

        assertThat(succeeded)
                .as("exactly one of the 3 identical requests may succeed")
                .isEqualTo(1);
        assertThat(duplicates)
                .as("the other two must be answered with a cached replay (200) or 409")
                .isEqualTo(2);
        assertThat(walletRepository.findByUserId(userId).orElseThrow().getBalance())
                .as("balance must be deducted exactly once")
                .isEqualByComparingTo("400.00");
        assertThat(recordRepository.count())
                .as("only one transaction record may ever exist for this transactionId")
                .isEqualTo(1);

        System.out.println("[TEST 2] Result : PASSED - statuses=" + statuses
                + ", balance=400.00, exactly 1 transaction record. Balance deducted exactly once.");
    }

    // ------------------------------------------------------------------
    // TEST 3 — Race Condition
    // ------------------------------------------------------------------
    @Test
    @DisplayName("Race Condition: 10 concurrent debits of 100 on a 500 wallet - final balance is exactly 0 and exactly 5 fail with insufficient funds")
    void concurrentDebitsNeverProduceNegativeBalance() throws Exception {
        System.out.println();
        System.out.println("[TEST 3 - RACE CONDITION]");
        System.out.println("[TEST 3] Intent  : Fire 10 concurrent DEBITs of 100.00 (distinct transactionIds) at a wallet holding 500.00.");
        System.out.println("[TEST 3] Expect  : exactly 5x HTTP 201 and 5x HTTP 422 (INSUFFICIENT_FUNDS); final balance exactly 0.00, never negative.");

        UUID userId = createWallet(new BigDecimal("500.00"));

        CountDownLatch fire = new CountDownLatch(1);
        List<Integer> statuses = fireConcurrentRequests(10, fire,
                () -> debitPayload(UUID.randomUUID(), userId, "100.00"));

        long succeeded = statuses.stream().filter(s -> s == 201).count();
        long insufficient = statuses.stream().filter(s -> s == 422).count();

        assertThat(succeeded)
                .as("only 5 of the 10 debits can succeed against a 500 balance")
                .isEqualTo(5);
        assertThat(insufficient)
                .as("the other 5 must fail with INSUFFICIENT_FUNDS (HTTP 422)")
                .isEqualTo(5);
        assertThat(walletRepository.findByUserId(userId).orElseThrow().getBalance())
                .as("final balance must be exactly 0.00 and never went negative")
                .isEqualByComparingTo("0.00");
        assertThat(recordRepository.countByStatus(TransactionStatus.PROCESSED)).isEqualTo(5);
        assertThat(recordRepository.countByStatus(TransactionStatus.INSUFFICIENT_FUNDS)).isEqualTo(5);

        System.out.println("[TEST 3] Result : PASSED - 5x 201, 5x 422, final balance=0.00, no negative balance ever occurred.");
    }

    // ------------------------------------------------------------------
    // TEST 4 — Bonus: sequential duplicate replay
    // ------------------------------------------------------------------
    @Test
    @DisplayName("Idempotency: a duplicate sent AFTER completion replays the original response without touching the balance")
    void sequentialDuplicateReplaysCachedResponse() throws Exception {
        System.out.println();
        System.out.println("[TEST 4 - SEQUENTIAL DUPLICATE REPLAY]");
        System.out.println("[TEST 4] Intent  : Send the same transactionId twice, 200ms apart.");
        System.out.println("[TEST 4] Expect  : first=201, second=200 with the identical response body; balance deducted once.");

        UUID userId = createWallet(new BigDecimal("500.00"));
        UUID transactionId = UUID.randomUUID();
        String payload = debitPayload(transactionId, userId, "100.00");

        MvcResult first = mockMvc.perform(post(PROCESS_URL)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andReturn();

        Thread.sleep(200);

        MvcResult second = mockMvc.perform(post(PROCESS_URL)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(second.getResponse().getContentAsString())
                .as("duplicate must receive the exact cached original response")
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(walletRepository.findByUserId(userId).orElseThrow().getBalance())
                .isEqualByComparingTo("400.00");

        System.out.println("[TEST 4] Result : PASSED - duplicate received byte-identical cached response, balance still 400.00.");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private UUID createWallet(BigDecimal balance) throws Exception {
        UUID userId = UUID.randomUUID();
        String body = """
                {"userId": "%s", "balance": %s}
                """.formatted(userId, balance.toPlainString());
        mockMvc.perform(post(WALLET_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
        return userId;
    }

    private String debitPayload(UUID transactionId, UUID userId, String amount) throws Exception {
        return objectMapper.writeValueAsString(
                new ProcessTransactionRequest(transactionId, userId, new BigDecimal(amount), TransactionType.DEBIT));
    }

    @FunctionalInterface
    private interface PayloadSupplier {
        String get() throws Exception;
    }

    /**
     * Fires {@code count} identical-instant requests: all threads wait on a
     * latch so the requests genuinely collide in the same millisecond window.
     */
    private List<Integer> fireConcurrentRequests(int count, CountDownLatch fire, PayloadSupplier payloadSupplier)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        try {
            List<Callable<Integer>> tasks = IntStream.range(0, count)
                    .mapToObj(i -> (Callable<Integer>) () -> {
                        ready.countDown();
                        fire.await(10, TimeUnit.SECONDS);
                        return mockMvc.perform(post(PROCESS_URL)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payloadSupplier.get()))
                                .andReturn().getResponse().getStatus();
                    })
                    .toList();

            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(pool.submit(task));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .as("all worker threads must reach the start gate")
                    .isTrue();
            fire.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }
}
