package com.smartfirehub.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Task 3 D2: 데이터셋 변경 SSE 알림(데이터셋 이름이 실린다)은 <b>같은 테넌트</b>의 연결 중 <b>그 데이터셋을 볼 수 있는</b> 사용자에게만 간다. 예전
 * broadcastAll 은 모든 테넌트의 모든 접속자에게 보냈다.
 *
 * <p>수신자 4연결: 테넌트 1 의 민감 자격(high, 받아야 함) · 공개 자격(low, 못 받음), 같은 high 사용자가 테넌트 2 화면으로 연 연결(VIEW 판정은
 * 통과하는 사용자라 테넌트 필터만이 막는다), 다른 데이터셋 알림의 양성 대조(공개 데이터셋은 low 도 받음).
 */
class DatasetChangeNotificationScopeTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private ObjectMapper om;
  @Autowired private DatasetAccessGuard guard;
  @Autowired private ClearanceResolver clearanceResolver;
  @Autowired private PermissionService permissionService;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private final List<Long> datasets = new ArrayList<>();
  private Long tenant2;
  private long hiddenId;
  private long publicId;
  private long low;
  private long high;

  /** 전송 횟수를 세는 연결 — 실제 HTTP 응답 없이 send 를 기록한다. */
  static class RecordingEmitter extends SseEmitter {
    final AtomicInteger sent = new AtomicInteger();

    @Override
    public void send(SseEventBuilder builder) {
      sent.incrementAndGet();
    }
  }

  /** 등록 순서대로 기록용 연결을 돌려주는 레지스트리. */
  static class RecordingRegistry extends SseEmitterRegistry {
    final List<RecordingEmitter> created = new ArrayList<>();

    RecordingRegistry(ObjectMapper om) {
      super(om);
    }

    @Override
    SseEmitter createEmitter() {
      RecordingEmitter e = new RecordingEmitter();
      created.add(e);
      return e;
    }
  }

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    String m = "dn" + System.nanoTime();
    long creator = fx.createUser("dn_creator");
    users.add(creator);
    hiddenId = fx.createDatasetRow(m + "_hidden", fx.levelId("민감"), creator);
    datasets.add(hiddenId);
    publicId = fx.createDatasetRow(m + "_public", fx.levelId("공개"), creator);
    datasets.add(publicId);
    low = userAt("공개");
    high = userAt("민감");
    tenant2 = TenantRlsTestSupport.createActiveTenant(dsl, "dn-t2");
  }

  private long userAt(String level) {
    long uid = fx.createUser("dn_u");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid = fx.createRole("dn_r_" + System.nanoTime(), fx.levelId(level), "dataset:read");
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    datasets.forEach(fx::deleteDatasetRow);
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
    TenantRlsTestSupport.deleteTenants(dsl, tenant2);
  }

  @Test
  void datasetChanged_reachesOnlySameTenantRecipientsWhoCanViewIt() {
    RecordingRegistry registry = new RecordingRegistry(om);
    NotificationService service =
        new NotificationService(registry, guard, clearanceResolver, permissionService);
    registry.register(high, DEFAULT_TEST_TENANT_ID);
    registry.register(low, DEFAULT_TEST_TENANT_ID);
    registry.register(high, tenant2); // 같은 사용자의 다른 테넌트 화면
    RecordingEmitter highT1 = registry.created.get(0);
    RecordingEmitter lowT1 = registry.created.get(1);
    RecordingEmitter highT2 = registry.created.get(2);

    // 폴러는 테넌트별로 TenantContext 를 세우고 부른다(TriggerEventService.pollDatasetChanges).
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    service.notifyDatasetChanged(hiddenId, "secret-name");

    assertThat(highT1.sent).as("같은 테넌트 + VIEW 가능").hasValue(1);
    assertThat(lowT1.sent).as("같은 테넌트지만 VIEW 불가").hasValue(0);
    assertThat(highT2.sent).as("다른 테넌트 연결").hasValue(0);

    // 양성 대조: 공개 데이터셋 알림은 같은 테넌트의 두 사용자 모두 받고, 다른 테넌트 연결은 여전히 못 받는다.
    service.notifyDatasetChanged(publicId, "public-name");
    assertThat(highT1.sent).hasValue(2);
    assertThat(lowT1.sent).hasValue(1);
    assertThat(highT2.sent).hasValue(0);
  }

  /**
   * 사용자 지정 역할 하나만 가진 테넌트 1 멤버. {@link #userAt} 과 같지만 권한 코드를 고른다(WD-187 — apiconnection:read 유무를 가르기
   * 위해).
   */
  private long userWith(String... permissionCodes) {
    long uid = fx.createUser("dn_api");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid = fx.createRole("dn_api_r_" + System.nanoTime(), fx.levelId("공개"), permissionCodes);
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  /**
   * WD-19 + WD-187: API 연결 상태 알림(연결 이름·오류 메시지·호출 URL 이 실린다)은 <b>현재 테넌트</b> 연결 중 <b>이 워크스페이스 ACTIVE
   * 멤버이고 apiconnection:read 를 가진</b> 사용자에게만 간다. 예전에는 같은 테넌트 연결 전원에게 갔다 — 권한 없는 멤버(사용자 지정 역할)·정지
   * 멤버·제거 멤버의 열린 연결까지. 테넌트 컨텍스트가 없으면 아무에게도 보내지 않는다.
   *
   * <p>정지·제거 멤버는 apiconnection:read 역할을 그대로 가진 채로 두어, 역할이 아니라 멤버십 상태만이 그들을 막는다는 것을 보인다. 제거 멤버는 멤버십
   * 행만 지운다(실제 제거는 역할도 지우지만, 역할이 남아도 멤버십이 없으면 받지 않아야 한다). 권한 보유자의 테넌트 2 연결은 수신 집합에 드는 사람의 연결이라 테넌트
   * 필터만이 막는다.
   */
  @Test
  void apiConnectionStatus_reachesOnlyActiveMembersWithApiConnectionRead() {
    long reader = userWith("dataset:read", "apiconnection:read");
    long noPermission = high; // dataset:read 만 가진 사용자 지정 역할
    long suspended = userWith("dataset:read", "apiconnection:read");
    long removed = userWith("dataset:read", "apiconnection:read");
    dsl.execute(
        "update membership set status = 'SUSPENDED' where user_id = ? and tenant_id = ?",
        suspended,
        DEFAULT_TEST_TENANT_ID);
    dsl.execute(
        "delete from membership where user_id = ? and tenant_id = ?",
        removed,
        DEFAULT_TEST_TENANT_ID);

    RecordingRegistry registry = new RecordingRegistry(om);
    NotificationService service =
        new NotificationService(registry, guard, clearanceResolver, permissionService);
    registry.register(reader, DEFAULT_TEST_TENANT_ID);
    registry.register(noPermission, DEFAULT_TEST_TENANT_ID);
    registry.register(suspended, DEFAULT_TEST_TENANT_ID);
    registry.register(removed, DEFAULT_TEST_TENANT_ID);
    registry.register(reader, tenant2); // 같은 권한 보유자의 다른 테넌트 화면
    RecordingEmitter readerT1 = registry.created.get(0);
    RecordingEmitter noPermissionT1 = registry.created.get(1);
    RecordingEmitter suspendedT1 = registry.created.get(2);
    RecordingEmitter removedT1 = registry.created.get(3);
    RecordingEmitter readerT2 = registry.created.get(4);

    // 헬스체크 스케줄러처럼 트랜잭션 없이 TenantContext 만 세우고 부른다 — 수신자 조회가 스스로 트랜잭션을 열어야 RLS 아래에서 보인다
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    service.broadcastApiConnectionStatus(
        "API_CONNECTION_DOWN",
        "API 연결 'secret-api'이(가) 응답하지 않습니다 (https://internal.example/health)",
        java.util.Map.of());
    service.broadcastApiConnectionStatus(
        "API_CONNECTION_UP", "API 연결 'secret-api'이(가) 복구되었습니다", java.util.Map.of());

    assertThat(readerT1.sent).as("ACTIVE 멤버 + apiconnection:read").hasValue(2);
    assertThat(noPermissionT1.sent).as("apiconnection:read 없는 멤버").hasValue(0);
    assertThat(suspendedT1.sent).as("정지(SUSPENDED) 멤버").hasValue(0);
    assertThat(removedT1.sent).as("워크스페이스에서 제거된 멤버").hasValue(0);
    assertThat(readerT2.sent).as("다른 테넌트 연결(WD-19 회귀)").hasValue(0);

    TenantContext.clear();
    service.broadcastApiConnectionStatus("API_CONNECTION_UP", "x", java.util.Map.of());
    assertThat(readerT1.sent).as("테넌트 없음 → 미전송").hasValue(2);
  }

  /** 리뷰 M2: 한 회차의 여러 데이터셋 알림이 같은 자격 캐시를 쓰면 사용자당 자격 계산은 한 번이다(결과는 그대로). */
  @Test
  void datasetChanged_sharedClearanceCache_resolvesEachRecipientOnce() {
    RecordingRegistry registry = new RecordingRegistry(om);
    java.util.concurrent.atomic.AtomicInteger resolves =
        new java.util.concurrent.atomic.AtomicInteger();
    ClearanceResolver counting =
        new ClearanceResolver(null, null) {
          @Override
          public com.smartfirehub.securitylevel.access.Clearance resolve(long userId) {
            resolves.incrementAndGet();
            return clearanceResolver.resolve(userId);
          }
        };
    NotificationService service =
        new NotificationService(registry, guard, counting, permissionService);
    registry.register(high, DEFAULT_TEST_TENANT_ID);
    registry.register(low, DEFAULT_TEST_TENANT_ID);
    RecordingEmitter highT1 = registry.created.get(0);
    RecordingEmitter lowT1 = registry.created.get(1);

    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    java.util.Map<Long, com.smartfirehub.securitylevel.access.Clearance> cache =
        new java.util.HashMap<>();
    service.notifyDatasetChanged(hiddenId, "h", cache);
    service.notifyDatasetChanged(publicId, "p", cache);

    assertThat(resolves).as("사용자 2명 × 데이터셋 2개인데 자격 계산은 2번").hasValue(2);
    assertThat(highT1.sent).hasValue(2);
    assertThat(lowT1.sent).hasValue(1);
  }
}
