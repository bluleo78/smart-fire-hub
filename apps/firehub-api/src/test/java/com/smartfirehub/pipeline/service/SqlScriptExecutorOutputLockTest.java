package com.smartfirehub.pipeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 같은 출력 테이블에 "비우기 + 적재"가 겹칠 때의 직렬화(#731) — 실제 DB·실제 테넌트 파이프라인 롤로 두 트랜잭션을 겹쳐 확인한다.
 *
 * <p><b>왜 {@code SqlScriptExecutorSandboxTest} 에 넣지 않고 클래스를 따로 두는가.</b> 테스트 프로파일은 테넌트 파이프라인 풀 크기를
 * 1로 잡는다(LRU 축출 테스트용). 풀이 1이면 두 실행이 같은 커넥션을 차례로 쓰게 되어 <b>겹침 자체가 만들어지지 않는다</b> — 수정 여부와 무관하게 통과하는
 * 공허한 테스트가 된다. 그래서 이 클래스만 풀 크기를 운영 기본값(2)으로 올린다. 클래스 레벨 {@code @Transactional} 은 쓰지 않는다 — 픽스처가 커밋돼야
 * 다른 롤의 커넥션이 본다.
 */
@TestPropertySource(properties = "app.pipeline.tenant-pool.max-size=2")
class SqlScriptExecutorOutputLockTest extends IntegrationTestBase {

  @Autowired private SqlScriptExecutor sqlScriptExecutor;

  /** 메인 애플리케이션 커넥션 — 픽스처 DML·관문 잠금·검증 조회용. */
  @Autowired private DSLContext dsl;

  /** 출력·원천 테이블을 정식 생성 경로로 만든다(파이프라인 롤 권한까지 갖춘다). */
  @Autowired private DataTableService dataTableService;

  /**
   * 같은 출력 테이블에 대한 REPLACE(선행 DELETE + INSERT) 두 건이 <b>실제로 겹쳐도</b> 출력에는 한 번 분량만 남는다(#731).
   *
   * <p><b>겹침을 만드는 방법 — 타이밍(sleep)이 아니라 제3 커넥션의 관문.</b> 테스트 커넥션이 원천 테이블에 ACCESS EXCLUSIVE 잠금을 쥐고 있는
   * 동안 두 실행을 띄운다. 먼저 들어간 실행은 DELETE 를 마치고 {@code INSERT ... SELECT FROM 원천} 에서 멈춘다 — 즉 "비웠지만 아직
   * 커밋하지 않은" 상태가 결정적으로 유지된다. 두 실행이 모두 잠금 대기에 들어간 것을 {@code pg_locks} 로 확인한 뒤에 관문을 연다.
   *
   * <p><b>수정 전에는 실패한다(실측).</b> 직렬화 잠금이 없으면 뒤 실행의 DELETE 는 앞 실행이 지운 행의 잠금을 기다렸다가 <b>옛 스냅샷</b>으로 끝나 앞
   * 실행이 새로 넣은 행을 지우지 못한다 — 출력에 원천의 두 배(4행)가 남는다. {@code SqlScriptExecutor} 의 잠금 문장을 지우면 이 테스트가 4행으로
   * 빨개진다.
   *
   * <p>이 테스트는 잠금 문장이 테넌트 파이프라인 롤({@code pipeline_executor_t1})로도 실행 가능함을 함께 증명한다 — 권한이 없으면 두 실행이 모두
   * 예외로 끝난다.
   */
  @Test
  void 같은_출력에_REPLACE_두_건이_겹쳐도_행이_중복되지_않는다() throws Exception {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    String src = "p731_src_" + suffix;
    String out = "p731_out_" + suffix;
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("name", "name", "TEXT", null, true, false, null, false));
    dataTableService.createTable(src, columns);
    dataTableService.createTable(out, columns);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      // given: 원천 2행, 출력에는 이전 실행 결과 1행(모두 커밋 — 다른 롤의 커넥션이 봐야 한다).
      dsl.execute("INSERT INTO " + DataSchema.qualify(src) + " (name) VALUES ('a'), ('b')");
      dsl.execute("INSERT INTO " + DataSchema.qualify(out) + " (name) VALUES ('old')");

      String insert =
          "INSERT INTO "
              + DataSchema.qualify(out)
              + " (name) SELECT name FROM "
              + DataSchema.qualify(src);
      // 워커 스레드는 테넌트 컨텍스트(ThreadLocal)를 승계하지 않으므로 직접 세운다.
      Callable<String> replaceRun =
          () ->
              TenantContext.runScopedGet(
                  DEFAULT_TEST_TENANT_ID,
                  () ->
                      sqlScriptExecutor.execute(
                          List.of(OutputClearStatement.deleteAll(out)), insert));

      List<Future<String>> runs = new ArrayList<>();
      // when: 원천에 관문 잠금을 건 채 두 실행을 띄우고, 둘 다 잠금 대기에 들어간 뒤 관문을 연다.
      dsl.transaction(
          cfg -> {
            cfg.dsl()
                .execute("LOCK TABLE " + DataSchema.qualify(src) + " IN ACCESS EXCLUSIVE MODE");
            runs.add(pool.submit(replaceRun));
            runs.add(pool.submit(replaceRun));

            // 두 실행이 모두 "잠금을 기다리는 중"이 될 때까지 기다린다. pg_locks 만 본다 —
            // pg_stat_activity 와 조인하면 안 된다: 통계 뷰는 트랜잭션 안에서 첫 조회 시점의
            // 스냅샷으로 고정되어(stats_fetch_consistency=cache), 그 뒤에 접속한 백엔드가 영영
            // 보이지 않는다(실측 — 조인했을 때 대기 1건에서 멈췄다). 테스트 DB 는 이 JVM 전용
            // 컨테이너라 다른 세션의 잠금 대기가 섞일 일은 없다.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            int waiting = 0;
            while (System.nanoTime() < deadline) {
              waiting =
                  cfg.dsl()
                      .fetchOne(
                          "SELECT count(DISTINCT pid) FROM pg_locks"
                              + " WHERE NOT granted AND pid <> pg_backend_pid()")
                      .get(0, Integer.class);
              if (waiting >= 2) {
                break;
              }
              Thread.sleep(50);
            }
            // 겹침이 실제로 만들어졌는지 단언한다 — 이게 없으면 두 실행이 우연히 순차로 돌아
            // 수정 여부와 무관하게 통과하는 공허한 테스트가 된다.
            assertThat(waiting).as("두 실행이 모두 잠금 대기 중이어야 겹침이 성립한다").isEqualTo(2);
          });

      // then: 두 실행 모두 성공(뒤 실행은 거부되지 않고 기다렸다가 실행된다)하고,
      for (Future<String> run : runs) {
        assertThat(run.get(30, TimeUnit.SECONDS)).isEqualTo("SQL executed successfully");
      }
      // 출력에는 원천 한 번 분량(2행)만 남는다 — 이전 행('old')도, 앞 실행의 행도 남지 않는다.
      assertThat(
              dsl.fetch("SELECT name FROM " + DataSchema.qualify(out) + " ORDER BY name")
                  .getValues(0, String.class))
          .containsExactly("a", "b");
    } finally {
      pool.shutdownNow();
      dsl.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(out));
      dsl.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(src));
    }
  }

  /**
   * 자기 출력을 읽어 다시 채우는 REPLACE(WD-426) 두 건이 겹쳐도 차례로 적용된다 — 잠금 전용 선행 문장이 전체 비우기와 같은 출력 잠금(#731)을 본
   * 문장보다 <b>먼저, 별도 문장으로</b> 잡기 때문에 뒤 실행의 본 문장 스냅숏은 앞 실행이 커밋한 뒤에 잡혀 앞 실행의 결과를 읽는다.
   *
   * <p><b>겹침을 만드는 방법.</b> 테스트 커넥션이 출력 테이블에 ACCESS EXCLUSIVE 잠금을 쥔 동안 두 실행을 띄운다. 한 실행은 출력 잠금을 잡은 뒤
   * 잠금 전용 문장({@code DELETE … WHERE false})에서 테이블 잠금을 기다리고, 다른 실행은 출력 잠금(advisory)을 기다린다. 둘 다 대기 중인
   * 것을 {@code pg_locks} 로 확인한 뒤 관문을 연다.
   *
   * <p><b>잠금이 없으면 실패한다.</b> 두 본 문장이 같은 옛 스냅숏(2행)을 읽어 각자 넣고, 뒤 실행의 DELETE 는 앞 실행이 넣은 행을 보지 못해 — 출력이
   * 4행이 되거나 PK 유니크 인덱스에서 23505 로 실패한다. 잠금이 있으면 2행이 두 번 변환된다('a++', 'b++'). 출력에는 PK(code)가 있다.
   */
  @Test
  void 같은_출력을_스스로_읽는_REPLACE_두_건이_겹쳐도_차례로_적용된다() throws Exception {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    String out = "p426_out_" + suffix;
    dataTableService.createTable(
        out,
        List.of(
            new DatasetColumnRequest("code", "code", "TEXT", null, false, false, null, true),
            new DatasetColumnRequest("name", "name", "TEXT", null, true, false, null, false)));

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      dsl.execute(
          "INSERT INTO "
              + DataSchema.qualify(out)
              + " (code, name) VALUES ('K1', 'a'), ('K2', 'b')");
      Callable<String> reloadRun =
          () ->
              TenantContext.runScopedGet(
                  DEFAULT_TEST_TENANT_ID,
                  () ->
                      sqlScriptExecutor.execute(
                          List.of(OutputClearStatement.lockOnly(out)),
                          OutputClearStatement.reloadFromOwnOutput(
                              out,
                              List.of("code", "name"),
                              "SELECT code, name || '+' AS name FROM " + DataSchema.qualify(out))));

      List<Future<String>> runs = new ArrayList<>();
      dsl.transaction(
          cfg -> {
            cfg.dsl()
                .execute("LOCK TABLE " + DataSchema.qualify(out) + " IN ACCESS EXCLUSIVE MODE");
            runs.add(pool.submit(reloadRun));
            runs.add(pool.submit(reloadRun));

            // 위 테스트와 같은 이유로 pg_locks 만 본다(통계 뷰는 트랜잭션 안에서 스냅숏으로 고정된다).
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            int waiting = 0;
            while (System.nanoTime() < deadline) {
              waiting =
                  cfg.dsl()
                      .fetchOne(
                          "SELECT count(DISTINCT pid) FROM pg_locks"
                              + " WHERE NOT granted AND pid <> pg_backend_pid()")
                      .get(0, Integer.class);
              if (waiting >= 2) {
                break;
              }
              Thread.sleep(50);
            }
            assertThat(waiting).as("두 실행이 모두 잠금 대기 중이어야 겹침이 성립한다").isEqualTo(2);
          });

      for (Future<String> run : runs) {
        assertThat(run.get(30, TimeUnit.SECONDS)).isEqualTo("SQL executed successfully");
      }
      // 두 실행이 차례로 적용됐다 — 행 수는 그대로이고, 뒤 실행이 앞 실행의 결과를 읽어 한 번 더 변환했다.
      assertThat(
              dsl.fetch("SELECT name FROM " + DataSchema.qualify(out) + " ORDER BY code")
                  .getValues(0, String.class))
          .containsExactly("a++", "b++");
    } finally {
      pool.shutdownNow();
      dsl.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(out));
    }
  }
}
