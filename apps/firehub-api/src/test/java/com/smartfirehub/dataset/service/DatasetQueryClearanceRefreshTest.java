package com.smartfirehub.dataset.service;

import static com.smartfirehub.jooq.Tables.ROLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.smartfirehub.dataset.dto.SqlQueryRequest;
import com.smartfirehub.dataset.dto.SqlQueryResponse;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.repository.QueryHistoryRepository;
import com.smartfirehub.global.datastore.DataStoreDb;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.permission.repository.jooq.JooqPermissionRepository;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.pipeline.service.PipelineDataSchema;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.repository.jooq.JooqDatasetAccessRepository;
import com.smartfirehub.securitylevel.sql.GuardedSqlExecutor;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TxRecordingInteractiveGate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * WD-682 — 데이터셋 /query 의 실행 직전 재판정(PG, WD-638)은 열람자 자격을 요청 캐시가 아니라 DB 에서 다시 읽어야 한다.
 *
 * <p>{@link ClearanceResolver#current()} 는 요청 속성에 자격을 캐시한다. 앞 판정(허가 전)이 그 캐시를 채운 뒤 허가를 기다리는 동안 관리자가
 * 열람자 역할의 최대 등급을 낮추면, 재판정이 캐시된(낡은) 자격으로 통과해 이제는 볼 수 없는 데이터셋을 실행하게 된다. 허가를 얻는 순간을 대기 중 변경 시점으로 흉내 낸다
 * — 게이트 대역이 acquire 에서 역할 등급을 낮춘다(스레드 없이 결정적으로).
 *
 * <p>실제 판정·자격 계산·감사·이력 저장소를 쓰고 게이트만 대역이다. 요청 경로처럼 요청 속성과 인증(principal = userId)을 세운다.
 */
class DatasetQueryClearanceRefreshTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  /** 물리 테이블을 만들고 지우는 데이터 저장소 — 질의는 여기서 돈다. 분리 모드에서는 플랫폼 DB 와 다른 서버다(WD-704). */
  @Autowired @DataStoreDb private DSLContext dataDsl;

  @Autowired private PasswordEncoder encoder;
  @Autowired private DatasetRepository datasetRepository;
  @Autowired private DatasetColumnRepository columnRepository;
  @Autowired private DataTableService dataTableService;
  @Autowired private DataTableRowService dataTableRowService;
  @Autowired private GuardedSqlExecutor guardedSqlExecutor;
  @Autowired private ClearanceResolver clearanceResolver;
  @Autowired private QueryHistoryRepository queryHistoryRepository;
  @Autowired private DatasetDataWriteLock writeLock;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private PipelineDataSchema pipelineDataSchema;
  @Autowired private DatasetAuditRecorder datasetAuditRecorder;

  private SecurityFixture fx;
  private String schema;
  private String table;
  private long owner;
  private long viewer;
  private long roleId;
  private long datasetId;

  /** 열람자 역할의 최대 등급을 공개로 낮춘다 — 관리자의 등급 하향을 흉내 낸다(픽스처 트랜잭션, 즉시 커밋). */
  private void lowerViewerRoleToPublic() {
    long publicLevel = fx.levelId("공개");
    inTenantFixture(
        () ->
            dsl.update(ROLE)
                .set(ROLE.MAX_SECURITY_LEVEL_ID, publicLevel)
                .where(ROLE.ID.eq(roleId))
                .execute());
  }

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    owner = fx.createUser("qcr_o");
    viewer = fx.createUser("qcr_v");
    // 열람자 자격을 이 테스트의 역할 하나(민감)로만 정한다 — 나중에 이 역할의 최대 등급을 낮춘다.
    fx.removeUserRole(viewer);
    roleId = fx.createRole("qcr_r_" + System.nanoTime(), fx.levelId("민감"), "dataset:read");
    fx.assignRole(viewer, roleId);
    schema = DataSchema.current();
    table = "qcr" + System.nanoTime();
    // 양성 대조가 실제로 실행되도록 물리 테이블도 만든다 — 질의가 도는 데이터 저장소에 만든다.
    dataDsl.execute("CREATE TABLE " + schema + "." + table + " (v int)");
    dataDsl.execute("INSERT INTO " + schema + "." + table + " VALUES (4242)");
    datasetId = fx.createDatasetRow(table, fx.levelId("민감"), owner);
    // 요청 경로 흉내 — ClearanceResolver 가 요청 속성에 캐시하고, principal(userId)로 자격을 계산한다.
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(viewer, null, List.of()));
  }

  @AfterEach
  void tearDown() {
    RequestContextHolder.resetRequestAttributes();
    SecurityContextHolder.clearContext();
    awaitSecurityAudit();
    inTenantFixture(
        () -> {
          dsl.execute("DELETE FROM query_history WHERE dataset_id = ?", datasetId);
          dsl.execute("DELETE FROM audit_log WHERE user_id = ?", viewer);
          return null;
        });
    fx.deleteDatasetRow(datasetId);
    fx.deleteRole(roleId);
    fx.deleteUser(viewer);
    fx.deleteUser(owner);
    dataDsl.execute("DROP TABLE IF EXISTS " + schema + "." + table);
  }

  /** 게이트만 대역인 실제 서비스. */
  private DatasetDataService service(TxRecordingInteractiveGate gate) {
    return new DatasetDataService(
        datasetRepository,
        columnRepository,
        dataTableService,
        dataTableRowService,
        guardedSqlExecutor,
        clearanceResolver,
        queryHistoryRepository,
        writeLock,
        new TransactionTemplate(transactionManager),
        pipelineDataSchema,
        datasetAuditRecorder,
        gate);
  }

  private SqlQueryRequest selectRequest() {
    return new SqlQueryRequest("SELECT v FROM " + table, 10);
  }

  /** 이 데이터셋의 쿼리 이력 행 수. */
  private int historyCount() {
    return inTenantFixture(
        () ->
            dsl.fetchOne("SELECT count(*) FROM query_history WHERE dataset_id = ?", datasetId)
                .get(0, Integer.class));
  }

  /** 열람자의 해당 action_type 감사 행 수(비동기 기록이 끝난 뒤). */
  private int auditCount(String action) {
    awaitSecurityAudit();
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT count(*) FROM audit_log WHERE user_id = ? AND action_type = ?",
                    viewer,
                    action)
                .get(0, Integer.class));
  }

  /** 양성 대조 — 대기 중 변경이 없으면 같은 열람자·같은 SQL 이 실행되고 이력이 남는다(경로 자체가 고장 나 공허하게 403 이 되지 않음). */
  @Test
  void noChangeWhileWaiting_executesAndSavesHistory() {
    TxRecordingInteractiveGate gate = new TxRecordingInteractiveGate();

    SqlQueryResponse r = service(gate).executeQuery(datasetId, selectRequest(), viewer);

    assertThat(r.error()).isNull();
    assertThat(r.rows()).hasSize(1);
    assertThat(gate.acquireTx).containsExactly(false);
    assertThat(historyCount()).isEqualTo(1);
  }

  /**
   * 앞 판정은 통과(민감 자격) → 허가를 기다리는 동안 열람자 역할의 최대 등급을 공개로 낮춤 → 실행 직전 재판정이 새 자격으로 403(거부 감사 포함), 실행·이력 저장
   * 없음. 캐시된 자격으로 재판정하던 구현은 통과해 실행하고 이력을 남긴다.
   */
  @Test
  void clearanceLoweredWhileWaiting_rejudgment403_noExecutionNoHistory() {
    // 허가를 얻는 순간(트랜잭션 밖) 등급을 낮춘다 — 운영에서 허가를 기다리는 동안 관리자가 커밋하는 변경과 같다.
    TxRecordingInteractiveGate gate = new TxRecordingInteractiveGate(this::lowerViewerRoleToPublic);

    Throwable e =
        catchThrowable(() -> service(gate).executeQuery(datasetId, selectRequest(), viewer));

    // 앞 판정은 통과해 허가까지 왔다(앞 판정 403 이었다면 acquire 가 불리지 않는다).
    assertThat(gate.acquireTx).containsExactly(false);
    assertThat(e).isInstanceOf(CodedApiException.class);
    assertThat(((CodedApiException) e).status()).isEqualTo(HttpStatus.FORBIDDEN);
    // 재판정 거부는 감사로 남는다(쓰기 트랜잭션 롤백과 무관).
    assertThat(auditCount("DATASET_ACCESS_DENIED")).isEqualTo(1);
    // 실행되지 않았다 — 감사 등급(민감) 접근 기록이 없고, 이력도 없다.
    assertThat(auditCount("DATASET_ACCESS")).isZero();
    assertThat(historyCount()).isZero();
  }

  /**
   * readFresh 는 새로 읽은 자격을 돌려주되 요청 캐시는 그대로 둔다 — 등급을 낮춘 뒤 readFresh() 는 새 자격, current() 는 앞뒤 모두 캐시된 옛
   * 자격(한 요청 안의 이후 판정이 두 자격을 섞어 보지 않게).
   */
  @Test
  void readFresh_returnsFreshValue_andLeavesRequestCacheUnchanged() {
    int before = clearanceResolver.current().rank();
    lowerViewerRoleToPublic();

    assertThat(clearanceResolver.current().rank()).as("요청 캐시").isEqualTo(before);
    assertThat(clearanceResolver.readFresh().rank()).as("새로 읽은 자격").isLessThan(before);
    assertThat(clearanceResolver.current().rank()).as("캐시는 그대로").isEqualTo(before);
  }

  /** 요청 밖(스케줄러·러너 — 요청 속성 없음)에서도 readFresh 는 실패하지 않는다. 인증이 없으면 fail-closed(아무것도 못 보는 자격). */
  @Test
  void readFresh_outsideRequest_isSafe() {
    RequestContextHolder.resetRequestAttributes();

    assertThat(clearanceResolver.readFresh().userId()).isEqualTo(viewer);

    SecurityContextHolder.clearContext();
    assertThat(clearanceResolver.readFresh().rank()).isEqualTo(Clearance.NO_RANK);
  }

  /**
   * WD-687 — 재판정이 자격을 새로 읽으며(readFresh, WD-682) 더하는 쿼리 수를 TC 수준에서 잰다(부하 환경 대신). PG 재판정처럼 쓰기 트랜잭션
   * 안에서 readFresh 로 재판정한다.
   *
   * <p>공유 싱글턴 DSLContext 설정은 건드리지 않는다 — 세는 리스너를 단 파생 설정(derive)의 DSLContext 로 자격 계산 저장소만 따로 조립한
   * ClearanceResolver 를 쓴다(운영과 같은 저장소 구현·같은 트랜잭션 연결). 판정 자체(매핑 조회)는 공유 DSL 이라 세지 않는다. 트랜잭션·GUC 같은
   * 기반 문장이 섞여도 흔들리지 않게 역할·권한 표를 읽는 문장만 세고, 지연 1회성 조회가 숫자를 흐리지 않게 먼저 한 번 데운다. 기대: 2건(역할·등급 조인 1 + 권한
   * 코드 1).
   */
  @Test
  void rejudgment_readFreshAddsTwoRolePermissionQueries() {
    List<String> seen = new CopyOnWriteArrayList<>();
    ExecuteListener counter =
        new ExecuteListener() {
          @Override
          public void executeStart(ExecuteContext ctx) {
            seen.add(ctx.sql());
          }
        };
    DSLContext counted = DSL.using(dsl.configuration().deriveAppending(counter));
    ClearanceResolver countedResolver =
        new ClearanceResolver(
            new JooqDatasetAccessRepository(counted),
            new PermissionService(new JooqPermissionRepository(counted)));
    TransactionTemplate writeTx = new TransactionTemplate(transactionManager);
    String sql = selectRequest().sql();
    Runnable rejudge =
        () ->
            writeTx.executeWithoutResult(
                s -> guardedSqlExecutor.judgeDatasetQuery(countedResolver.readFresh(), sql));

    // 데우기 — 첫 호출에만 도는 지연 조회를 측정에서 뺀다.
    rejudge.run();
    seen.clear();
    rejudge.run();

    assertThat(rolePermissionQueries(seen)).as("readFresh 의 역할·권한 조회: %s", seen).isEqualTo(2);
  }

  /** 역할·권한 표(user_role·role_permission·permission)를 읽는 문장 수. */
  private static long rolePermissionQueries(List<String> sqls) {
    return sqls.stream()
        .map(String::toLowerCase)
        .filter(q -> q.contains("user_role") || q.contains("permission"))
        .count();
  }
}
