package com.smartfirehub.global.tenant;

import java.util.List;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 기동 시 <b>이미 존재하는</b> ACTIVE 테넌트의 파이프라인 실행 롤·스키마 권한을 일괄 치유한다(#680).
 *
 * <p><b>왜 필요한가.</b> {@code PlatformTenantService.create} 에 롤 프로비저닝을 붙여도 그건
 * <b>앞으로 만들 테넌트</b>에만 적용된다. 롤 없이 태어난 기존 테넌트(2026-09-17 장애의 테넌트 2)는
 * 여전히 운영자가 손으로 고쳐야 하고, 그 수동 절차가 바로 이 장애의 원인이었다. 배포만으로
 * 자가치유되게 만드는 것이 이 클래스다.
 *
 * <p><b>왜 롤만으로는 부족하고 스키마 권한까지 손대는가.</b> {@link TenantSchemaProvisioner} 의
 * 자가치유는 <b>데이터 테이블을 만들 때</b>({@code DataTableService.createTable})만 돈다. 그런데
 * 파이프라인 실행은 테이블을 만들지 않고 <b>읽기만</b> 하므로, 데이터셋을 이미 다 만들어 둔
 * 테넌트는 롤을 나중에 만들어 줘도 스키마 GRANT 를 받을 기회가 영영 오지 않는다 — 테넌트 2가
 * 정확히 그 상태였다. 그래서 여기서 롤을 만든 <b>직후</b> 프로비저너를 한 번 불러 준다.
 *
 * <p><b>왜 Flyway 콜백이 아니라 {@code ApplicationReadyEvent} 인가.</b> 콜백은 원시 {@code
 * Connection} 만 받고 스프링 빈에 닿지 못해 {@link TenantSchemaProvisioner} 를 부를 수 없다.
 * {@code RolePasswordSyncCallback} 은 비밀번호 동기화(=콜백 커넥션으로 충분한 일)로 그대로 둔다.
 *
 * <p><b>기동을 절대 막지 않는다.</b> 테넌트 하나의 치유 실패가 전체 기동을 막으면, 이 클래스는
 * 장애를 줄이는 대신 새 장애 유형을 하나 추가하는 셈이 된다. 테넌트별로 잡아 로그만 남기고 계속
 * 진행한다({@link TenantSchemaProvisioner} 가 롤 부재를 건너뛰는 것과 같은 판단).
 */
@Component
public class TenantPipelineRoleBootstrap {

  private static final Logger log = LoggerFactory.getLogger(TenantPipelineRoleBootstrap.class);

  /**
   * ACTIVE 테넌트 목록과 스키마 존재 여부를 읽는 커넥션.
   *
   * <p><b>소유자({@code app}) 커넥션을 쓰지 않는다.</b> 그 빈은 dev·test·prod 전부에서 슈퍼유저라
   * 주입 지점을 하나라도 늘리지 않는 것이 규약이고({@code SchemaOwnerDataSourceExposureGuardTest}),
   * 여기서 필요한 것은 <b>읽기 두 건</b>뿐이다 — {@code tenant}(RLS 없는 전역 테이블, V81)와
   * {@code pg_namespace}(카탈로그). 둘 다 런타임 롤로 읽을 수 있으므로 슈퍼유저가 필요 없다.
   * 권한이 실제로 필요한 쓰기는 전부 아래 두 프로비저너가 각자의 소유자 커넥션으로 한다.
   */
  private final DSLContext dsl;

  private final TenantPipelineRoleProvisioner roleProvisioner;

  private final TenantSchemaProvisioner schemaProvisioner;

  public TenantPipelineRoleBootstrap(
      DSLContext dsl,
      TenantPipelineRoleProvisioner roleProvisioner,
      TenantSchemaProvisioner schemaProvisioner) {
    this.dsl = dsl;
    this.roleProvisioner = roleProvisioner;
    this.schemaProvisioner = schemaProvisioner;
  }

  /**
   * <b>이 메서드는 어떤 경우에도 예외를 던지지 않는다.</b> {@code ApplicationReadyEvent} 리스너의
   * 예외는 스프링 부트의 {@code handleRunFailure} 로 올라가 <b>기동을 중단시킨다</b> — 즉 테넌트
   * 목록 조회 한 번이 일시적으로 실패하는 것만으로 롤아웃이 죽는다. 그래서 바깥에도 한 겹을 둔다:
   * 안쪽 {@code try} 는 "테넌트 하나가 실패해도 나머지는 계속"을, 바깥쪽은 "치유가 통째로 실패해도
   * 앱은 뜬다"를 각각 지킨다. 치유되지 않은 테넌트는 다음 기동에 다시 시도된다(멱등).
   */
  @EventListener(ApplicationReadyEvent.class)
  void healExistingTenants() {
    try {
      healActiveTenants();
    } catch (RuntimeException e) {
      log.error("테넌트 파이프라인 롤 기동 치유를 시작하지 못했다 — 기동은 계속한다", e);
    }
  }

  private void healActiveTenants() {
    if (!roleProvisioner.isAutoProvisionEnabled()) {
      // test 프로필. 여기서 일찍 끊는 이유는 두 가지다 — 공유 test DB 의 수백 개 테넌트에 LOGIN
      // 롤을 만들지 않기 위해서고(플래그 근거는 프로비저너 Javadoc), 그 수만큼의 카탈로그 조회가
      // 모든 스프링 컨텍스트 기동에 얹히는 것을 피하기 위해서다.
      log.debug("테넌트 파이프라인 롤 자동 프로비저닝이 꺼져 있다 — 기동 치유를 건너뛴다");
      return;
    }

    List<Long> tenantIds =
        dsl.fetch("select id from tenant where status = 'ACTIVE' order by id").stream()
            .map(r -> r.get(0, Long.class))
            .toList();

    int healed = 0;
    for (long tenantId : tenantIds) {
      try {
        healTenant(tenantId);
        healed++;
      } catch (RuntimeException e) {
        log.error("테넌트 파이프라인 롤 기동 치유 실패 — 건너뛰고 계속한다 (tenant={})", tenantId, e);
      }
    }
    log.info("테넌트 파이프라인 롤 기동 치유 완료: {}/{}", healed, tenantIds.size());
  }

  /**
   * 테넌트 하나를 치유한다 — 롤을 만들고, 스키마가 이미 있으면 권한까지 다시 건다.
   *
   * <p><b>루프와 분리해 둔 이유는 테스트다.</b> 루프는 위 플래그로 꺼져 있어(공유 test DB 보호)
   * 통합 테스트가 부를 수 없고, 그렇다고 플래그를 켜면 테스트 DB 의 테넌트 수백 개에 롤이 생긴다.
   * 그래서 <b>루프가 아니라 한 테넌트분의 치유</b>를 스크래치 테넌트로 검증한다
   * ({@code TenantPipelineRoleBootstrapTest}).
   *
   * <p>플래그와 무관하게 동작한다 — 특정 테넌트 하나를 지목해 고치는 것은 "자동으로 전부 훑는다"와
   * 별개의 행위이고, 운영자가 수동 복구를 걸 때도 이 단위가 맞다.
   */
  public void healTenant(long tenantId) {
    // 이미 있는 롤에는 손대지 않는다. ALTER ROLE ... PASSWORD 는 매번 SCRAM 해시 재계산 +
    // pg_authid 행 재작성이고, 그 동기화는 이미 RolePasswordSyncCallback 이 같은 기동에서
    // (마이그레이션 단계에, 즉 이것보다 먼저) 수행한다 — 여기서 또 쓰면 테넌트마다 같은 쓰기를
    // 두 번 하는 셈이다. 이 경로가 책임지는 것은 "없는 롤을 만드는 것" 하나다.
    //
    // 대가: 롤은 있는데 GRANT CONNECT 나 search_path 가 빠진 부분 상태는 여기서 고쳐지지 않는다.
    // 그 상태는 운영자가 런북 §1-3 을 손으로 반쯤 실행했을 때만 생기고, 그때는 ensureRole 을
    // 직접 부르면 된다(멱등).
    if (!roleProvisioner.roleExists(tenantId)) {
      roleProvisioner.ensureRole(tenantId);
    }
    healSchemaGrants(tenantId);
  }

  /**
   * 스키마가 <b>이미 있을 때만</b> 프로비저너를 부른다.
   *
   * <p>없는 스키마를 여기서 만들면 지연 생성 설계를 뒤집는 셈이 된다 — 공유 test DB 에 테넌트가
   * 수백 개 쌓여 있어 선제 생성은 스키마 무한 누적이 된다({@link TenantSchemaProvisioner} Javadoc).
   * 아직 데이터셋이 없는 테넌트는 첫 {@code createTable} 이 알아서 만들고, 그때는 롤이 이미 있으므로
   * GRANT 도 같은 트랜잭션에서 걸린다.
   */
  private void healSchemaGrants(long tenantId) {
    if (!TenantSchemaProvisioner.schemaExists(dsl, DataSchema.forTenant(tenantId))) {
      return;
    }
    // 멱등이고, 이미 권한이 완비된 테넌트는 프로비저너 진입부에서 단락된다 — 그래서 테넌트 1
    // (data, prod 실측 86 테이블)에서 GRANT ... ON ALL TABLES 락 폭풍이 매 기동마다 돌지 않는다.
    TenantContext.runScoped(tenantId, schemaProvisioner::ensureCurrentTenantSchema);
  }
}
