package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.audit.repository.AuditLogRepository;
import com.smartfirehub.dashboard.service.DashboardService;
import com.smartfirehub.global.security.PermissionChecker;
import com.smartfirehub.support.IntegrationTestBase;
import java.lang.reflect.Method;
import org.jooq.JSONB;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.interceptor.TransactionAttributeSource;

/**
 * RLS 정책(V99) 아래에서 조용히 0행이 되는 경로를 트랜잭션 프록시로 고정한다.
 *
 * <p>왜 행위 테스트가 아니라 메타데이터 단언인가: 이 Task 시점에는 아직 정책이 없어 행위로는
 * 결함이 드러나지 않는다(정책은 V99 로 분리돼 있다). 그래서 "트랜잭션이 열린다"는 전제 자체를
 * 고정하고, 실제 격리 행위는 Task 5 의 RLS 테스트가 검증한다.
 *
 * <p>GUC 는 TenantAwareTransactionManager.doBegin 에서만 주입되므로, 트랜잭션이 없으면
 * TenantContext 에 값이 있어도 아무 일도 일어나지 않는다 — 예외도 로그도 없이 0행이다.
 */
class AuthzPathTransactionTest extends IntegrationTestBase {

  @Autowired private TransactionAttributeSource transactionAttributeSource;

  private boolean isTransactional(Class<?> type, String methodName, Class<?>... paramTypes)
      throws NoSuchMethodException {
    Method method = type.getMethod(methodName, paramTypes);
    return transactionAttributeSource.getTransactionAttribute(method, type) != null;
  }

  @Test
  @DisplayName("PermissionChecker.hasPermission 은 자체 트랜잭션을 연다")
  void permissionCheckerOpensTransaction() throws Exception {
    // 유일한 프로덕션 호출자는 PipelineAsyncRunner 의 @Async 스레드다(python/ai 스텝 권한검사).
    // 앰비언트 트랜잭션이 없으므로 자체 트랜잭션이 없으면 정책 아래에서 권한 0개가 된다.
    assertThat(isTransactional(PermissionChecker.class, "hasPermission", Long.class, String.class))
        .isTrue();
  }

  @Test
  @DisplayName("AuditLogRepository 는 클래스 레벨 트랜잭션을 갖는다")
  void auditLogRepositoryIsTransactional() throws Exception {
    // AuditLogService.log 에는 @Transactional 이 없다. 트랜잭션이 있는 호출자(로그인 등)는
    // 편승하지만 비동기 알림 경로는 편승할 트랜잭션이 없다 → INSERT 가 WITH CHECK 위반이 된다.
    assertThat(
            isTransactional(
                AuditLogRepository.class,
                "save",
                Long.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                JSONB.class))
        .isTrue();
  }

  /** audit_log 를 직접 읽는 프로덕션 클래스. 새로 생기면 여기에 추가해야 한다. */
  private static final Class<?>[] AUDIT_LOG_READERS = {
    AuditLogRepository.class, DashboardService.class
  };

  @Test
  @DisplayName("audit_log 를 읽는 경로는 전부 트랜잭션을 연다")
  void auditLogReadPathsAreTransactional() throws Exception {
    // V99 에서 audit_log 만 형태 (b)(IS NOT DISTINCT FROM)를 쓴다. 그 비대칭이 여기 있다:
    // 나머지 4테이블은 컨텍스트가 없으면 술어가 NULL 이 되어 0행(fail-closed)이지만, audit_log 는
    // 술어가 `tenant_id IS NOT DISTINCT FROM NULL` 이 되어 **모든 테넌트의 NULL 테넌트 행이
    // 참**이 된다. 즉 트랜잭션 없는 읽기는 0행이 아니라 교차 테넌트 로그인 감사(사용자명·IP·
    // User-Agent)를 돌려준다.
    //
    // 이 단언이 고정하는 것은 정확히 "이 메서드들이 트랜잭션 프록시를 갖는다" 하나뿐이다.
    // 형태 (b) 의 실제 안전 조건은 그보다 강하다 — **테넌트 컨텍스트가 설정되어 있어야** 한다.
    // 트랜잭션은 GUC 주입의 필요조건일 뿐 충분조건이 아니다(TenantAwareTransactionManager 는
    // doBegin 에서 TenantContext 값을 주입하는데, 그 값이 비어 있으면 트랜잭션이 있어도 GUC 는
    // 비어 있다). 컨텍스트 충족은 요청 스코프에서 JwtAuthenticationFilter 가 책임진다.
    // 그 부분은 이 단언의 범위 밖이다. 아래 noScheduledAuditLogReader 는 그 구멍의 **일부**만
    // 막는다 — AUDIT_LOG_READERS 클래스 자신에 @Scheduled 가 선언되는 경우뿐이고, 그 클래스를
    // 단지 호출하기만 하는 새 배경 진입점(@Scheduled/@Async/@Job/@PostConstruct)은 잡지 못한다.
    // 그 잔여 구멍의 구조적 해법은 BackgroundPathTransactionTest 의 리포지토리 맵이다.
    assertThat(isTransactional(AuditLogRepository.class, "findById", Long.class)).isTrue();
    assertThat(
            isTransactional(
                AuditLogRepository.class,
                "findByResource",
                String.class,
                String.class,
                String.class))
        .isTrue();
    // 대시보드도 audit_log 를 직접 읽는다(AuditLogRepository 를 거치지 않는 별도 경로다).
    // AuditLogRepository.findAll 과 달리 이쪽은 메서드 레벨 @Transactional 이라 하나씩 고정한다.
    assertThat(isTransactional(DashboardService.class, "getStats")).isTrue();
    assertThat(isTransactional(DashboardService.class, "getSystemHealth")).isTrue();
    assertThat(isTransactional(DashboardService.class, "getAttentionItems")).isTrue();
    assertThat(
            isTransactional(
                DashboardService.class,
                "getActivityFeed",
                String.class,
                String.class,
                int.class,
                int.class))
        .isTrue();
  }

  @Test
  @DisplayName("audit_log 를 읽는 클래스 자신에 @Scheduled 메서드가 없다")
  void noScheduledAuditLogReader() {
    // 위 테스트가 못 잡는 실패 형태를 여기서 잡는다: @Scheduled + @Transactional 인 감사 스윕이
    // 생기면 트랜잭션 단언은 그대로 통과하지만, 스케줄러 스레드에는 요청이 없어 TenantContext 가
    // 비어 있다. 그러면 GUC 가 비고 형태 (b) 술어가 참이 되어 전 테넌트의 NULL 테넌트 로그인
    // 감사를 읽게 된다 — 정확히 형태 (b) 가 fail-open 인 그 경로다.
    //
    // 컨텍스트 충족 자체는 정적으로 단언할 수 없으므로, 컨텍스트가 구조적으로 없는 진입점이
    // 생기지 않는다는 것으로 대신 고정한다. 현재 이 프로젝트에 @Scheduled 는 여러 개 있으나
    // (TriggerEventService·FileCleanupService 등) audit_log 를 읽는 클래스에는 없다.
    //
    // 이 가드의 범위는 **여기 나열된 클래스에 직접 선언된 @Scheduled** 뿐이다. 그 클래스를 호출만
    // 하는 새 배경 진입점은 잡지 못한다 — 그 형태의 결함(ProactiveJobAsyncRunner)이 실제로 이
    // band 를 통과했다. 그쪽의 구조적 해법은 BackgroundPathTransactionTest 의 리포지토리 맵이다.
    //
    // isAnnotationPresent 가 아니라 getMergedRepeatableAnnotations 를 쓰는 이유: @Scheduled 는
    // @Repeatable 이라 두 번 붙으면 컴파일러가 @Schedules 컨테이너로 합성하고, 그러면
    // isAnnotationPresent(Scheduled.class) 가 false 가 되어 가드가 조용히 뚫린다. 메타애노테이션
    // (@Scheduled 를 품은 커스텀 애노테이션)도 같은 이유로 놓친다. 이 API 는 두 경우를 모두 본다.
    for (Class<?> reader : AUDIT_LOG_READERS) {
      for (Method method : reader.getDeclaredMethods()) {
        assertThat(
                AnnotatedElementUtils.getMergedRepeatableAnnotations(method, Scheduled.class)
                    .isEmpty())
            .as(
                "%s.%s 가 @Scheduled 다 — 요청 스코프 밖이라 TenantContext 가 비고,"
                    + " audit_log 형태 (b) 정책이 교차 테넌트 행을 돌려준다."
                    + " 스케줄 감사 작업이 필요하면 TenantContext.runScoped 로 테넌트를 명시하라.",
                reader.getSimpleName(),
                method.getName())
            .isTrue();
      }
    }
  }

  /**
   * 위 가드가 쓰는 탐지 API 자체가 {@code @Repeatable} 을 실제로 본다는 것을 고정한다.
   *
   * <p>왜: 이 웨이브 이전의 {@code isAnnotationPresent(Scheduled.class)} 는 두 번 붙은 {@code
   * @Scheduled} 를 놓쳤다(컴파일러가 {@code @Schedules} 컨테이너로 합성하기 때문). 가드를 고쳤다는
   * 주장은 탐지 API 를 직접 시험해야 검증된다 — 실제 리더 클래스에는 {@code @Scheduled} 가 하나도
   * 없어서 위 테스트는 API 를 바꿔도 통과하기 때문이다.
   */
  @Test
  @DisplayName("@Scheduled 탐지가 반복 애노테이션(@Schedules 합성)도 본다")
  void repeatableScheduledIsDetected() throws Exception {
    Method doubly = RepeatableProbe.class.getDeclaredMethod("twice");
    assertThat(doubly.isAnnotationPresent(Scheduled.class))
        .as("반복 애노테이션은 컨테이너로 합성되므로 옛 API 는 놓친다 — 이 사실 자체를 고정한다")
        .isFalse();
    assertThat(AnnotatedElementUtils.getMergedRepeatableAnnotations(doubly, Scheduled.class))
        .as("가드가 쓰는 API 는 반복 애노테이션을 봐야 한다")
        .hasSize(2);
  }

  /** 반복 {@code @Scheduled} 탐지 검증용 프로브. 스프링 빈이 아니라 실행되지 않는다. */
  private static final class RepeatableProbe {
    @Scheduled(fixedDelay = 60000)
    @Scheduled(fixedDelay = 120000)
    void twice() {}
  }
}
