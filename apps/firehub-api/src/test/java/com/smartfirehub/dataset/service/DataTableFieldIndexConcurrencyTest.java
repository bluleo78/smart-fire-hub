package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * WD-100 — 같은 필드의 인덱스를 두 요청이 동시에 켤 때(더블클릭) 둘 다 같은 이름으로 CREATE 해 두 번째가 이름 중복으로 실패하지 않는지.
 *
 * <p>경합을 우연에 맡기지 않는다. 첫 트랜잭션이 인덱스를 만든 뒤 <b>커밋하지 않고</b> 멈춰 있는 동안 두 번째 요청을 보낸다. 미커밋 인덱스는 카탈로그 조회에 보이지
 * 않으므로, 직렬화가 없으면 두 번째 요청은 "없음"으로 보고 같은 이름으로 CREATE 하다가 첫 트랜잭션 커밋 뒤 이름 중복으로 실패한다. 직렬화가 있으면 두 번째 요청은
 * 잠금에서 기다렸다가 커밋된 인덱스를 보고 건너뛴다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 두 트랜잭션이 서로의 커밋을 봐야 한다. 제품 호출부({@code
 * DatasetService})는 모두 {@code @Transactional} 이므로, 각 요청을 테넌트 트랜잭션 안에서 실행해 그 조건을 재현한다.
 */
class DataTableFieldIndexConcurrencyTest extends IntegrationTestBase {

  @Autowired private DataTableService dataTableService;
  @Autowired private DSLContext dsl;

  private static final String TABLE = "wd100_concurrent";

  @AfterEach
  void dropTable() {
    inTenantFixture(() -> dataTableService.dropTable(TABLE));
  }

  private long indexCountOn(String column) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT count(*) FROM pg_index i"
                        + " JOIN pg_class tc ON tc.oid = i.indrelid"
                        + " JOIN pg_namespace ns ON ns.oid = tc.relnamespace"
                        + " JOIN pg_attribute a ON a.attrelid = tc.oid AND a.attnum = i.indkey[0]"
                        + " WHERE ns.nspname = ? AND tc.relname = ? AND a.attname = ?"
                        + " AND i.indnatts = 1 AND NOT i.indisunique",
                    DataSchema.current(),
                    TABLE,
                    column)
                .get(0, Long.class));
  }

  /**
   * 두 번째 요청이 첫 트랜잭션에 막혀 끝나지 않는지 잠시 지켜본다. DB 로 대기 상태를 조회하지 않는 이유: 테스트 커넥션 풀이 2개라(첫 트랜잭션·두 번째 요청이 하나씩
   * 쥔다) 관측 조회가 커넥션을 못 얻어 멈춘다. 직렬화가 있든 없든(잠금 대기 / 같은 이름의 카탈로그 행 대기) 두 번째 요청은 첫 트랜잭션이 끝날 때까지 막혀야 정상이다
   * — 여기서 끝나 버리면 경합을 재현하지 못한 것이다.
   */
  private static void assertStillBlocked(CompletableFuture<?> waiter) throws Exception {
    Thread.sleep(1_000);
    if (waiter.isDone()) {
      waiter.get(); // 예외로 끝났으면 그대로 드러낸다.
      throw new AssertionError("두 번째 요청이 첫 트랜잭션을 기다리지 않고 끝났다 — 경합을 재현하지 못했다");
    }
  }

  @Test
  void 같은_필드를_동시에_켜도_예외가_없고_인덱스는_하나만_생긴다() throws Exception {
    inTenantFixture(
        () ->
            dataTableService.createTable(
                TABLE,
                List.of(new DatasetColumnRequest("val", "val", "TEXT", null, true, false, null))));

    CountDownLatch firstCreated = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);

    // 첫 요청: 인덱스를 만든 뒤 커밋 전에 멈춘다.
    CompletableFuture<Void> first =
        CompletableFuture.runAsync(
            () ->
                inTenantFixture(
                    () -> {
                      dataTableService.setColumnIndex(TABLE, "val", true, "TEXT");
                      firstCreated.countDown();
                      try {
                        assertThat(releaseFirst.await(30, TimeUnit.SECONDS)).isTrue();
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                      }
                    }));
    assertThat(firstCreated.await(30, TimeUnit.SECONDS)).isTrue();

    // 두 번째 요청: 첫 트랜잭션이 열려 있는 동안 같은 필드를 켠다.
    CompletableFuture<Void> second =
        CompletableFuture.runAsync(
            () ->
                inTenantFixture(() -> dataTableService.setColumnIndex(TABLE, "val", true, "TEXT")));
    assertStillBlocked(second);
    releaseFirst.countDown();

    first.get(30, TimeUnit.SECONDS);
    // 직렬화가 없으면 여기서 이름 중복(relation already exists / duplicate key) 예외가 난다.
    second.get(30, TimeUnit.SECONDS);

    assertThat(indexCountOn("val")).isEqualTo(1L);
  }
}
