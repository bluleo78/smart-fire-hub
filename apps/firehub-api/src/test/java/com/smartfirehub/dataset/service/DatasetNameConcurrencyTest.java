package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.dataset.dto.CloneDatasetRequest;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.dto.UpdateDatasetRequest;
import com.smartfirehub.dataset.exception.DuplicateDatasetNameException;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.securitylevel.pythonread.PythonReadGrantSync;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PausedTransactionRace;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * WD-540 — 같은 이름으로 데이터셋을 동시에 만들거나(생성·복제) 동시에 같은 새 이름으로 바꾸면 모두 성공해 이름이 겹치던 문제.
 *
 * <p>이름 중복 검사는 「조회자가 볼 수 있는 데이터셋」 범위라 DB 유일 제약이 없다(V142, WD-155). 검사 뒤 쓰기 사이에 다른 트랜잭션이 끼어들면 서로의 미커밋
 * 행을 못 봐 둘 다 통과한다. 서비스가 이름 검사 직전에 「테넌트+이름」 키의 트랜잭션 범위 잠금을 잡아 줄 세우면, 두 번째 요청은 첫 번째 커밋 뒤의 행을 보고 순차일
 * 때와 같은 409 를 받아야 한다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 각 요청이 실제처럼 자기 트랜잭션으로 커밋해야 서로를 기다린다. 테스트 커넥션 풀이 2개라
 * 동시에 DB 를 쓰는 요청은 둘뿐이다(잠금 대기자 1·보유자 1 — 보유자가 끝나면 다음이 커넥션을 얻는다). 결정적 재현이 필요한 경로는 {@link
 * PausedTransactionRace} 로 첫 트랜잭션을 커밋 전에 멈추고 두 번째가 실제로 대기했는지까지 단언한다(대기 없이 통과하면 공허한 테스트).
 */
class DatasetNameConcurrencyTest extends IntegrationTestBase {

  private static final Logger log = LoggerFactory.getLogger(DatasetNameConcurrencyTest.class);

  private static final List<DatasetColumnRequest> COLUMNS =
      List.of(new DatasetColumnRequest("val", "Value", "TEXT", null, true, false, null));

  @Autowired private DatasetService datasetService;
  @Autowired private DatasetRepository datasetRepository;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;

  /**
   * PYTHON 읽기 권한 동기화를 끈다. 생성·복제는 커밋 뒤 콜백에서 이 동기화를 REQUIRES_NEW 로 연다 — 콜백 시점에 아직 자기 커넥션을 쥔 채 새 커넥션을
   * 하나 더 요구하므로, 풀이 2개인 테스트에서는 두 요청이 겹치기만 해도 서로 커넥션을 기다리다 30초 타임아웃 뒤 진행한다(이름 잠금과 무관한 기존 동작 — 잠금은
   * 커밋에서 이미 풀린다). 이 대기가 「두 번째 요청이 잠금에서 기다렸다」로 잘못 관측되지 않게 하고, 경합 판정만 남긴다. 커밋 뒤 콜백이 커넥션을 하나 더 쓰는 문제
   * 자체는 WD-594 에서 다룬다 — 고쳐지면 이 목을 걷어낸다.
   */
  @MockitoBean private PythonReadGrantSync pythonReadGrantSync;

  /** 테스트마다 이름·테이블명이 겹치지 않게 붙이는 접미사 — 롤백이 없어 행이 남기 때문이다. */
  private String suffix;

  private Long userId;

  @BeforeEach
  void setUp() {
    suffix = Long.toString(System.nanoTime());
    userId =
        TestUsers.createMember(
                dsl,
                fixtureTransactionTemplate,
                encoder,
                "wd540_" + suffix,
                "wd540_" + suffix + "@example.com",
                "Password123",
                "WD540",
                DEFAULT_TEST_TENANT_ID)
            .id();
  }

  /**
   * 이 테스트가 만든 데이터셋을 이름 접두어로 모두 찾아 지운다 — 수정 전 코드에서 중복으로 생긴 행까지 정리한다. 테스트와 같은 로그인 상태로 지우고, 한 건이 실패해도
   * 나머지는 계속 지운다(한 건 실패로 나머지 데이터셋·물리 테이블이 남지 않게). 실패는 로그로 남긴다.
   */
  @AfterEach
  void tearDown() {
    List<Long> ids =
        inTenantFixture(
            () ->
                dsl.select(field("id", Long.class))
                    .from(table("dataset"))
                    .where(field("name", String.class).like(namePrefix() + "%"))
                    .fetch(r -> r.value1()));
    for (Long id : ids) {
      try {
        runAsUser(() -> inTenantFixture(() -> datasetService.deleteDataset(id)));
      } catch (RuntimeException e) {
        log.warn("WD-540 테스트 데이터셋 정리 실패: id={}", id, e);
      }
    }
  }

  private String namePrefix() {
    return "WD540 " + suffix;
  }

  /**
   * 테스트 사용자로 로그인한 상태에서 실행한다. 이름 중복 검사는 「조회자가 볼 수 있는 데이터셋」 범위라, 인증이 없으면 자격이 none 이 되어 아무 행도 보지 못해
   * 중복이 영영 걸리지 않는다. SecurityContext 는 스레드별이라 작업 스레드마다 세운다.
   */
  private <T> T asUser(Supplier<T> work) {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    try {
      return work.get();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  /** {@link #asUser} 의 반환값 없는 버전. */
  private void runAsUser(Runnable work) {
    asUser(
        () -> {
          work.run();
          return null;
        });
  }

  private CreateDatasetRequest createRequest(String name, String tableName) {
    return new CreateDatasetRequest(name, tableName, null, null, "TABLE", "SOURCE", COLUMNS, null);
  }

  private DatasetDetailResponse create(String name, String tableName) {
    return asUser(
        () ->
            inTenantFixture(
                () -> datasetService.createDataset(createRequest(name, tableName), userId)));
  }

  private int countByName(String name) {
    return inTenantFixture(
        () -> dsl.fetchCount(table("dataset"), field("name", String.class).eq(name)));
  }

  @Test
  void 같은_이름으로_5건을_동시에_만들면_1건만_성공하고_나머지는_이름_중복이다() throws Exception {
    String name = namePrefix() + " five";
    int n = 5;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(n);
    List<Future<Throwable>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < n; i++) {
        String tableName = "wd540_" + suffix + "_five_" + i;
        futures.add(
            pool.submit(
                () -> {
                  start.await(10, TimeUnit.SECONDS);
                  try {
                    create(name, tableName);
                    return null;
                  } catch (Throwable e) {
                    return e;
                  }
                }));
      }
      start.countDown();
      List<Throwable> errors = new ArrayList<>();
      int succeeded = 0;
      for (Future<Throwable> f : futures) {
        Throwable e = f.get(90, TimeUnit.SECONDS);
        if (e == null) {
          succeeded++;
        } else {
          errors.add(e);
        }
      }

      assertThat(succeeded).isEqualTo(1);
      assertThat(errors).hasSize(n - 1).allMatch(DuplicateDatasetNameException.class::isInstance);
      assertThat(countByName(name)).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void 생성이_커밋되기_전_같은_이름의_생성은_기다렸다가_이름_중복이다() throws Exception {
    String name = namePrefix() + " cc";

    PausedTransactionRace.Outcome<DatasetDetailResponse> out =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () ->
                runAsUser(
                    () ->
                        datasetService.createDataset(
                            createRequest(name, "wd540_" + suffix + "_a"), userId)),
            () ->
                asUser(
                    () ->
                        datasetService.createDataset(
                            createRequest(name, "wd540_" + suffix + "_b"), userId)));

    assertThat(out.secondBlocked()).isTrue();
    assertThat(out.secondError()).isInstanceOf(DuplicateDatasetNameException.class);
    assertThat(countByName(name)).isEqualTo(1);
  }

  @Test
  void 생성이_커밋되기_전_같은_이름으로의_복제는_기다렸다가_이름_중복이다() throws Exception {
    String name = namePrefix() + " clone";
    DatasetDetailResponse source = create(namePrefix() + " src", "wd540_" + suffix + "_src");

    PausedTransactionRace.Outcome<DatasetDetailResponse> out =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () ->
                runAsUser(
                    () ->
                        datasetService.createDataset(
                            createRequest(name, "wd540_" + suffix + "_a"), userId)),
            () ->
                asUser(
                    () ->
                        datasetService.cloneDataset(
                            source.id(),
                            new CloneDatasetRequest(
                                name, "wd540_" + suffix + "_c", null, false, false),
                            userId)));

    assertThat(out.secondBlocked()).isTrue();
    assertThat(out.secondError()).isInstanceOf(DuplicateDatasetNameException.class);
    assertThat(countByName(name)).isEqualTo(1);
  }

  @Test
  void 두_데이터셋을_같은_새_이름으로_동시에_바꾸면_1건만_성공하고_나머지는_이름_중복이다() throws Exception {
    String name = namePrefix() + " renamed";
    DatasetDetailResponse a = create(namePrefix() + " a", "wd540_" + suffix + "_a");
    DatasetDetailResponse b = create(namePrefix() + " b", "wd540_" + suffix + "_b");

    PausedTransactionRace.Outcome<Void> out =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () ->
                runAsUser(
                    () ->
                        datasetService.updateDataset(
                            a.id(), new UpdateDatasetRequest(name, null, null), userId)),
            () -> {
              runAsUser(
                  () ->
                      datasetService.updateDataset(
                          b.id(), new UpdateDatasetRequest(name, null, null), userId));
              return null;
            });

    assertThat(out.secondBlocked()).isTrue();
    assertThat(out.secondError()).isInstanceOf(DuplicateDatasetNameException.class);
    assertThat(countByName(name)).isEqualTo(1);
  }

  @Test
  void 생성이_커밋되기_전_같은_이름으로의_변경은_기다렸다가_이름_중복이다() throws Exception {
    String name = namePrefix() + " cr";
    DatasetDetailResponse b = create(namePrefix() + " b", "wd540_" + suffix + "_b");

    PausedTransactionRace.Outcome<Void> out =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () ->
                runAsUser(
                    () ->
                        datasetService.createDataset(
                            createRequest(name, "wd540_" + suffix + "_a"), userId)),
            () -> {
              runAsUser(
                  () ->
                      datasetService.updateDataset(
                          b.id(), new UpdateDatasetRequest(name, null, null), userId));
              return null;
            });

    assertThat(out.secondBlocked()).isTrue();
    assertThat(out.secondError()).isInstanceOf(DuplicateDatasetNameException.class);
    assertThat(countByName(name)).isEqualTo(1);
  }

  @Test
  void 이름을_그대로_두는_변경은_같은_이름의_잠금을_기다리지_않는다() throws Exception {
    // 이름이 안 바뀌는 PUT(설명만 수정)은 잠금을 잡지 않아야 한다 — 잡으면 무관한 수정이 같은 이름 생성에 줄을 선다.
    String name = namePrefix() + " keep";
    DatasetDetailResponse a = create(name, "wd540_" + suffix + "_a");

    PausedTransactionRace.Outcome<Void> out =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> datasetRepository.acquireNameLock(DEFAULT_TEST_TENANT_ID, name),
            () -> {
              runAsUser(
                  () ->
                      datasetService.updateDataset(
                          a.id(), new UpdateDatasetRequest(name, "설명", null), userId));
              return null;
            });

    assertThat(out.secondBlocked()).isFalse();
    assertThat(out.secondError()).isNull();
  }

  @Test
  void 같은_테넌트의_같은_이름_잠금은_기다리고_다른_테넌트는_기다리지_않는다() throws Exception {
    String name = namePrefix() + " tenant";
    // 테넌트 행이 없어도 된다 — 잠금 키만 테넌트 id 로 갈린다.
    long otherTenant = DEFAULT_TEST_TENANT_ID + 987_654_321L;

    PausedTransactionRace.Outcome<Void> sameTenant =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> datasetRepository.acquireNameLock(DEFAULT_TEST_TENANT_ID, name),
            () -> {
              inTenantFixture(
                  () -> datasetRepository.acquireNameLock(DEFAULT_TEST_TENANT_ID, name));
              return null;
            });
    PausedTransactionRace.Outcome<Void> otherTenantOut =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> datasetRepository.acquireNameLock(DEFAULT_TEST_TENANT_ID, name),
            () -> {
              TenantRlsTestSupport.runInTenantTransaction(
                  fixtureTransactionTemplate,
                  otherTenant,
                  () -> datasetRepository.acquireNameLock(otherTenant, name));
              return null;
            });

    assertThat(sameTenant.secondBlocked()).isTrue();
    assertThat(sameTenant.secondError()).isNull();
    assertThat(otherTenantOut.secondBlocked()).isFalse();
    assertThat(otherTenantOut.secondError()).isNull();
  }

  @Test
  void 이름은_다르고_테이블명이_같은_생성이_겹치면_늦은_쪽은_500이_아니라_테이블명_409다() throws Exception {
    // 테이블명은 유일 인덱스가 있어 중복 행은 안 생기지만, 검사 뒤 쓰기 사이에 겹치면 늦은 쪽이 인덱스 위반(500)으로 끝났다 — 순차일 때의 409 여야 한다.
    String tableName = "wd540_" + suffix + "_same";

    PausedTransactionRace.Outcome<DatasetDetailResponse> out =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () ->
                runAsUser(
                    () ->
                        datasetService.createDataset(
                            createRequest(namePrefix() + " t1", tableName), userId)),
            () ->
                asUser(
                    () ->
                        datasetService.createDataset(
                            createRequest(namePrefix() + " t2", tableName), userId)));

    assertThat(out.secondBlocked()).isTrue();
    assertThat(out.secondError())
        .isInstanceOf(DuplicateDatasetNameException.class)
        .hasMessage(DatasetService.TABLE_NAME_UNAVAILABLE_MESSAGE);
  }

  @Test
  void 이름_잠금을_트랜잭션_밖에서_부르면_예외로_거부한다() {
    // 트랜잭션 밖이면 잠금이 문장 끝에 바로 풀려 아무것도 막지 못한다 — 조용히 지나가지 않고 드러나야 한다.
    assertThatThrownBy(() -> datasetRepository.acquireNameLock(DEFAULT_TEST_TENANT_ID, "x"))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThatThrownBy(() -> datasetRepository.acquireTableNameLock(DEFAULT_TEST_TENANT_ID, "x"))
        .isInstanceOf(IllegalTransactionStateException.class);
  }
}
