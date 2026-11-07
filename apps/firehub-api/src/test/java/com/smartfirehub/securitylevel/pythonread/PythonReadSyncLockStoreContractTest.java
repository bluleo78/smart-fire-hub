package com.smartfirehub.securitylevel.pythonread;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.service.PostgresDataTableStore;
import com.smartfirehub.global.datastore.DataStoreDb;
import com.smartfirehub.support.IntegrationTestBase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * "한 직렬화 = 한 저장소" 계약(WD-493) — Python 읽기 권한 동기화 잠금이 어느 DB 에서 잡히는지 고정한다.
 *
 * <p>테넌트마다 백엔드는 하나라 PG 판({@link PythonReadGrantSync#lockTenantSync})과 Tibero 판({@link
 * TiberoPythonReadGrantSync})은 겹치지 않는다. PG 판은 데이터 저장소 연결에서, Tibero 판은 {@code PlatformLocks} 로 플랫폼
 * DB 에서 잡는다. 예전에는 두 판이 같은 잠금 공간을 공유한다고 고정했지만(R4), 분리 모드에서는 데이터 저장소와 플랫폼 DB 가 서로 다른 DB 라 그 가정이 성립하지
 * 않는다.
 *
 * <p>(c) "테넌트 하나는 백엔드 하나만" — {@code PythonReadGrants} 가 resolver 결과로 둘 중 하나에만 위임하는 것은 {@link
 * PythonReadGrantsTest} 가 이미 고정하므로 여기서 다시 쓰지 않는다. 클래스 레벨 {@code @Transactional} 은 쓰지 않는다 — 서로 다른
 * 커넥션이 필요하다.
 */
class PythonReadSyncLockStoreContractTest extends IntegrationTestBase {

  /** tx2 가 tx1 커밋 전까지 대기하는지 관측하는 시간. */
  private static final long BLOCK_OBSERVE_MS = 1_500;

  /** 데이터 저장소 DSL — 분리 모드에서는 플랫폼 DB 와 다른 DB 다. */
  @Autowired @DataStoreDb private DSLContext dataDsl;

  /** 실제 동기화 호출자 — 주입받은 DSL 이 데이터 저장소 빈인지 본다. */
  @Autowired private PythonReadGrantSync pythonReadGrantSync;

  /** 실제 REPLACE 맞바꿈 호출자 — 주입받은 DSL 이 데이터 저장소 빈인지 본다. */
  @Autowired private PostgresDataTableStore postgresDataTableStore;

  /**
   * (a) PG 동기화 잠금은 데이터 저장소 연결에서 서로 막는다. REPLACE 맞바꿈도 같은 {@code lockTenantSync} 를 데이터 저장소 트랜잭션에서
   * 부르므로 (PostgresDataTableStore) 같은 호출끼리 겨루는 것이 맞바꿈과의 직렬화 계약을 대표한다. tx1 이 잠금을 쥔 채 멈추고 tx2 가 같은
   * 테넌트로 잠금을 요구하면, tx2 는 tx1 커밋 전에는 끝나지 않고 커밋 뒤에는 끝나야 한다.
   */
  @Test
  void pgSyncLockSerializesOnDataStore() throws Exception {
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean secondDone = new AtomicBoolean(false);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> tx1 =
          pool.submit(
              () ->
                  dataDsl.transaction(
                      cfg -> {
                        PythonReadGrantSync.lockTenantSync(cfg.dsl(), 7L);
                        held.countDown();
                        // 커밋 전 멈춤 — tx2 가 이 사이에 시작해 대기하게 한다.
                        release.await(30, TimeUnit.SECONDS);
                      }));
      assertThat(held.await(30, TimeUnit.SECONDS)).as("tx1 이 잠금을 얻지 못했다").isTrue();
      Future<?> tx2 =
          pool.submit(
              () ->
                  dataDsl.transaction(
                      cfg -> {
                        PythonReadGrantSync.lockTenantSync(cfg.dsl(), 7L);
                        secondDone.set(true);
                      }));
      Thread.sleep(BLOCK_OBSERVE_MS);
      // 대기 관측 — tx2 가 tx1 커밋 전에 잠금을 얻었다면 키가 갈라진 것이다.
      assertThat(secondDone.get()).as("tx2 가 tx1 커밋 전에 잠금을 얻었다").isFalse();
      release.countDown();
      tx1.get(30, TimeUnit.SECONDS);
      tx2.get(30, TimeUnit.SECONDS);
      assertThat(secondDone.get()).as("tx1 커밋 뒤에도 tx2 가 끝나지 않았다").isTrue();
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  /**
   * (b) Tibero 판은 플랫폼 잠금만 쓴다 — 데이터 저장소 DSL 이나 PG 정적 잠금을 부르지 않는다. 소스 본문에서 주석을 걷어 낸 뒤 확인한다(설명 주석은 두
   * 이름을 언급해도 된다).
   */
  @Test
  void tiberoSyncUsesPlatformLocksOnly() throws Exception {
    Path src =
        Paths.get(
            "src/main/java/com/smartfirehub/securitylevel/pythonread/TiberoPythonReadGrantSync.java");
    String code = stripComments(Files.readString(src));
    assertThat(code).doesNotContain("lockTenantSync").doesNotContain("@DataStoreDb");
    assertThat(code).contains("locks.xactLock(");
  }

  /**
   * (a') 실제 호출자가 데이터 저장소에서 잠그는지 배선으로 고정한다. (a) 는 테스트가 직접 {@code @DataStoreDb} DSL 을 넘기므로, 실제 호출자가
   * 플랫폼 DSL 을 넘기도록 바뀌어도 통과해 버린다. 그래서 동기화({@code PythonReadGrantSync})와 REPLACE 맞바꿈({@code
   * PostgresDataTableStore})이 주입받은 DSLContext 가 바로 그 {@code @DataStoreDb} 빈인지 본다(분리 모드에서는 플랫폼 DSL 과
   * 다른 인스턴스다).
   */
  @Test
  void realCallersHoldDataStoreDsl() {
    Object sync = AopTestUtils.getUltimateTargetObject(pythonReadGrantSync);
    Object store = AopTestUtils.getUltimateTargetObject(postgresDataTableStore);
    assertThat(ReflectionTestUtils.getField(sync, "dsl"))
        .as("PythonReadGrantSync 는 @DataStoreDb DSLContext 를 받아야 한다")
        .isSameAs(dataDsl);
    assertThat(ReflectionTestUtils.getField(store, "dsl"))
        .as("PostgresDataTableStore 는 @DataStoreDb DSLContext 를 받아야 한다")
        .isSameAs(dataDsl);
  }

  /**
   * (a'') 맞바꿈은 그 데이터 저장소 DSL 의 트랜잭션 안에서 {@code lockTenantSync} 를 부른다 — 주석을 걷어 낸 {@code swapTable}
   * 본문에서 {@code dsl.transaction(} 안의 트랜잭션 DSL({@code DSL.using(cfg)})로 잠그는지 본다. (a') 의 {@code dsl}
   * 필드가 데이터 저장소 빈이므로 둘을 합치면 "맞바꿈의 동기화 잠금 = 데이터 저장소 잠금" 이 된다.
   */
  @Test
  void swapLocksTenantSyncOnDataStoreTransaction() throws Exception {
    Path src =
        Paths.get("src/main/java/com/smartfirehub/dataset/service/PostgresDataTableStore.java");
    String code = stripComments(Files.readString(src));
    int start = code.indexOf("public void swapTable(");
    assertThat(start).as("swapTable 이 있어야 한다").isGreaterThanOrEqualTo(0);
    String body = code.substring(start, code.indexOf("\n  }\n", start));
    String flat = body.replaceAll("\\s+", "");
    int tx = flat.indexOf("dsl.transaction(cfg->{vartxDsl=org.jooq.impl.DSL.using(cfg);");
    int lock = flat.indexOf("PythonReadGrantSync.lockTenantSync(txDsl,");
    assertThat(tx)
        .as("swapTable 은 dsl.transaction 의 트랜잭션 DSL(txDsl)을 만든다")
        .isGreaterThanOrEqualTo(0);
    assertThat(lock).as("swapTable 은 txDsl 로 lockTenantSync 를 부른다").isGreaterThan(tx);
  }

  /** 블록 주석(Javadoc 포함)과 줄 주석(줄 전체·줄 끝)을 제거한 본문. */
  static String stripComments(String source) {
    String noBlock = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(source).replaceAll("");
    return noBlock.lines().map(l -> l.replaceAll("//.*$", "")).collect(Collectors.joining("\n"));
  }
}
