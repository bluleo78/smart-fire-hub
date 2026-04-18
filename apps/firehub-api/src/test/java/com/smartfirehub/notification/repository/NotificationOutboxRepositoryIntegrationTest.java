package com.smartfirehub.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 스펙 4장·5장 OutboxRepository 라이프사이클 통합 검증. */
class NotificationOutboxRepositoryIntegrationTest extends IntegrationTestBase {

    @Autowired
    private NotificationOutboxRepository repo;

    @Test
    void insertIfAbsent_idempotent() {
        UUID corr = UUID.randomUUID();
        var row = sampleRow("key-idem-" + corr, corr);
        repo.insertIfAbsent(row);
        repo.insertIfAbsent(row);   // 두 번째는 UNIQUE 충돌 → DO NOTHING

        assertThat(repo.findByCorrelation(corr)).hasSize(1);
    }

    @Test
    void claimDue_marksSendingAndReturnsRows() {
        UUID corr = UUID.randomUUID();
        repo.insertIfAbsent(sampleRow("key-claim-" + corr, corr));

        var claimed = repo.claimDue(10, "test-instance");
        assertThat(claimed).hasSizeGreaterThanOrEqualTo(1);
        assertThat(claimed.get(0).status()).isEqualTo("SENDING");
    }

    @Test
    void claimDue_skipLockedConcurrent() throws Exception {
        UUID corr = UUID.randomUUID();
        repo.insertIfAbsent(sampleRow("key-skiplocked-" + corr, corr));

        var pool = Executors.newFixedThreadPool(2);
        try {
            var f1 = CompletableFuture.supplyAsync(() -> repo.claimDue(10, "i1"), pool);
            var f2 = CompletableFuture.supplyAsync(() -> repo.claimDue(10, "i2"), pool);
            long thisCorrClaimed = f1.get().stream()
                    .filter(r -> r.correlationId().equals(corr)).count()
                    + f2.get().stream().filter(r -> r.correlationId().equals(corr)).count();
            assertThat(thisCorrClaimed).isEqualTo(1);    // 둘 다 같은 행을 집지 못함
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void markSent_setsStatus() {
        UUID corr = UUID.randomUUID();
        repo.insertIfAbsent(sampleRow("key-sent-" + corr, corr));

        var claimed = repo.claimDue(10, "i").stream()
                .filter(r -> r.correlationId().equals(corr))
                .findFirst().orElseThrow();
        repo.markSent(claimed.id(), "ext-123");

        var rows = repo.findByCorrelation(corr);
        assertThat(rows.get(0).status()).isEqualTo("SENT");
    }

    @Test
    void reclaimZombies_resetsLongClaimedRows() {
        UUID corr = UUID.randomUUID();
        repo.insertIfAbsent(sampleRow("key-zombie-" + corr, corr));
        repo.claimDue(10, "i");

        int reclaimed = repo.reclaimZombies(Instant.now().plusSeconds(60));   // 강제 cutoff 미래
        assertThat(reclaimed).isGreaterThanOrEqualTo(1);
        assertThat(repo.findByCorrelation(corr).get(0).status()).isEqualTo("PENDING");
    }

    private NotificationOutboxRepository.NotificationOutboxRow sampleRow(String key, UUID corr) {
        return new NotificationOutboxRepository.NotificationOutboxRow(
                null, key, corr, "TEST_EVENT", null,
                ChannelType.CHAT, null, null,
                null, null, "{\"t\":\"x\"}", "STANDARD",
                "PENDING", 0, Instant.now()
        );
    }
}
