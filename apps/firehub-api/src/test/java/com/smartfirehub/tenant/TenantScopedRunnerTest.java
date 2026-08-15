package com.smartfirehub.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 원 요청이 없는 스케줄러(@Scheduled, @PostConstruct)는 승계할 테넌트가 없다.
 * ACTIVE 테넌트를 순회해 테넌트별로 실행하는 것이 유일한 방법이다.
 */
class TenantScopedRunnerTest extends IntegrationTestBase {

  private static final Table<?> TENANT = table(name("tenant"));
  private static final Field<Long> T_ID = field(name("tenant", "id"), Long.class);
  private static final Field<String> T_SLUG = field(name("tenant", "slug"), String.class);
  private static final Field<String> T_NAME = field(name("tenant", "name"), String.class);
  private static final Field<String> T_STATUS = field(name("tenant", "status"), String.class);

  @Autowired private TenantScopedRunner runner;
  @Autowired private DSLContext dsl;

  // 이 테스트가 직접 만든 테넌트만 정리한다 — 공유 DB 에 다른 세션이 만든 테넌트는 건드리지 않는다.
  private final List<Long> createdTenantIds = new ArrayList<>();

  @AfterEach
  void clearContext() {
    TenantContext.clear();
    for (Long id : createdTenantIds) {
      dsl.deleteFrom(TENANT).where(T_ID.eq(id)).execute();
    }
    createdTenantIds.clear();
  }

  @Test
  void runsWorkOncePerActiveTenantWithContextSet() {
    List<Long> observedArgs = new ArrayList<>();
    List<Long> mismatches = new ArrayList<>();
    runner.forEachActiveTenant(
        tenantId -> {
          observedArgs.add(tenantId);
          // 콜백 인자와 TenantContext.get() 이 실제로 같은 테넌트를 가리키는지 직접 확인한다.
          // observed 를 TenantContext.get() 으로만 모으면 러너가 항상 같은(예: 기본) 테넌트를
          // 세워도 우연히 통과할 수 있다.
          if (!Objects.equals(tenantId, TenantContext.get())) {
            mismatches.add(tenantId);
          }
        });

    // 기본 테넌트(id=1)는 V81 이 ACTIVE 로 시드했으므로 최소 1건은 반드시 있다.
    assertThat(observedArgs).isNotEmpty().contains(1L);
    assertThat(mismatches).isEmpty();
  }

  /**
   * 운영 형태(스케줄러 스레드 = 진입 전 컨텍스트 없음)에서 순회가 끝나면 컨텍스트가 남지 않아야
   * 한다. 남으면 풀 스레드가 재사용될 때 다음 작업이 남의 테넌트로 돈다.
   */
  @Test
  void leavesNoContextWhenEnteredWithout() {
    TenantContext.clear();
    runner.forEachActiveTenant(tenantId -> {});
    assertThat(TenantContext.get()).isNull();
  }

  /**
   * 이미 테넌트가 있는 스레드에서 부르면 <b>진입 전 값이 복원</b>돼야 한다.
   *
   * <p>무조건 {@code clear} 하면 호출자의 컨텍스트를 빼앗아, 그 뒤 문장이 하나라도 추가되는 순간
   * 조용히 0행이 된다 — 형제 헬퍼({@code TenantContext.runScoped}, {@code TenantContextTaskDecorator})
   * 와 같은 의미론임을 여기서 고정한다. 마지막으로 순회된 테넌트가 남지 않는 것도 함께 본다.
   */
  @Test
  void restoresPreviousContextWhenEnteredWithOne() {
    long caller = 1L;
    TenantContext.set(caller);

    List<Long> visited = new ArrayList<>();
    runner.forEachActiveTenant(visited::add);

    assertThat(visited).isNotEmpty();
    assertThat(TenantContext.get()).isEqualTo(caller);
  }

  @Test
  void oneTenantFailureDoesNotStopTheRest() {
    // "나머지가 계속됐다"를 검증하려면 ACTIVE 테넌트가 최소 2개여야 한다 — 공유 DB 상태에 기대지 않고
    // 이 테스트가 직접 2개를 만든다(고유 slug 로 충돌 방지).
    long suffix = TenantRlsTestSupport.nextTenantId();
    Long tenantA = insertActiveTenant("scoped-runner-a-" + suffix);
    Long tenantB = insertActiveTenant("scoped-runner-b-" + suffix);

    List<Long> observed = new ArrayList<>();
    runner.forEachActiveTenant(
        tenantId -> {
          observed.add(tenantId);
          throw new IllegalStateException("의도된 실패");
        });

    // 예외가 러너를 통과해 나가지 않고, 두 테넌트 모두 순회됐어야 한다.
    assertThat(observed).contains(tenantA, tenantB);
    // 실패 경로에서도 순회 중이던 테넌트가 남으면 안 된다(진입 전 값 = 기본 테넌트로 복원).
    assertThat(TenantContext.get()).isEqualTo(DEFAULT_TEST_TENANT_ID);
  }

  /** 테스트 전용 ACTIVE 테넌트를 하나 만들고, cleanup 대상으로 등록한 뒤 발급된 id 를 반환한다. */
  private Long insertActiveTenant(String slug) {
    Long id =
        dsl.insertInto(TENANT)
            .set(T_SLUG, slug)
            .set(T_NAME, "Test Tenant " + slug)
            .set(T_STATUS, "ACTIVE")
            .returning(T_ID)
            .fetchOne()
            .get(T_ID);
    createdTenantIds.add(id);
    return id;
  }
}
