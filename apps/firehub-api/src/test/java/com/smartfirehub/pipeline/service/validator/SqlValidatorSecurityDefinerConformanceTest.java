package com.smartfirehub.pipeline.service.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Set;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code public} 스키마의 {@code SECURITY DEFINER} 함수를 <b>카탈로그에서 전수 발견</b>해, {@link
 * SqlValidator#BLOCKED_FUNCTIONS} 또는 이 클래스의 문서화된 허용목록 중 하나에 반드시 들어 있는지 검사한다. (이슈 #385 Task
 * 4 리뷰 지적)
 *
 * <p><b>왜 필요한가.</b> {@code SqlValidator}의 deny-list 는 정확하고 값싸지만, 손으로 든 목록이라 <b>새
 * {@code SECURITY DEFINER} 함수가 추가돼도 아무것도 빨개지지 않고 조용히 미한정 호출 가능 상태가 된다</b> — 그 함수들은
 * {@code TenantSchemaConformanceTest}가 이미 지적하듯 RLS 를 의도적으로 우회하도록 만들어졌고 {@code PUBLIC EXECUTE}
 * 권한을 갖는다(permitAll 호출부를 위한 것). 이 테스트는 목록을 유지하는 것이 목적이 아니라, 새 definer 가 아무 목록에도
 * 안 들어간 채 들어오는 것을 막는 것이 목적이다({@code TenantSchemaConformanceTest.
 * securityDefinerFunctionsAreKnownAndLeastPrivileged}와 같은 설계 원칙, 발견 대상만 다르다).
 *
 * <p>{@code TenantSchemaConformanceTest}와의 역할 분담: 그쪽은 definer 함수 자체가 "안전하게 설계됐는지"(search_path
 * 고정, PUBLIC EXECUTE 여부, 백킹 테이블 FORCE RLS 미사용)를 검사한다. 이 테스트는 그 함수들이 <b>애널리틱스/데이터셋 애드혹 SQL
 * 경로에서 미한정 호출로 도달 가능한지</b>를 검사한다 — 관심사가 다르므로 중복이 아니다.
 */
class SqlValidatorSecurityDefinerConformanceTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  /**
   * {@code SqlValidator.BLOCKED_FUNCTIONS} 로 막지 않고 허용하는 {@code SECURITY DEFINER} 함수. <b>지금은 비어
   * 있고, 비어 있는 것이 정상이다.</b> PostGIS 함수는 {@code SECURITY DEFINER}가 아니므로 여기 들어올 이유가 없다 — 항목을
   * 추가하려면 "왜 사용자 SQL 경로에서 미한정 실행이 안전한지" 사유를 반드시 남겨야 하고, 그 편집이 리뷰에 걸린다.
   */
  private static final Set<String> ALLOWED_SECURITY_DEFINER_FUNCTIONS = Set.of();

  @Test
  void publicSecurityDefinerFunctionsAreBlockedOrExplicitlyAllowed() {
    List<String> discovered =
        dsl.fetch(
                "select p.proname from pg_proc p"
                    + " join pg_namespace n on n.oid = p.pronamespace"
                    + " where n.nspname = 'public' and p.prosecdef")
            .getValues(0, String.class);

    // 자기검증 — 발견 쿼리가 망가져 빈 집합이 되면 아래 전수 단언이 공허하게 통과한다.
    assertThat(discovered)
        .as("public 스키마에서 SECURITY DEFINER 함수가 하나도 발견되지 않았다 — 발견 쿼리를 의심하라")
        .isNotEmpty();

    List<String> unaccountedFor =
        discovered.stream()
            .filter(
                fn ->
                    !SqlValidator.BLOCKED_FUNCTIONS.contains(fn)
                        && !ALLOWED_SECURITY_DEFINER_FUNCTIONS.contains(fn))
            .toList();

    assertThat(unaccountedFor)
        .as(
            "SECURITY DEFINER 함수가 SqlValidator.BLOCKED_FUNCTIONS 에도, "
                + "ALLOWED_SECURITY_DEFINER_FUNCTIONS 에도 없다 — 이름과 사유를 둘 중 한 곳에 적어라")
        .isEmpty();

    // 역방향 — 허용목록에 남았지만 이미 사라진(더 이상 definer 가 아니거나 삭제된) 항목은 지워라.
    // ALLOWED_SECURITY_DEFINER_FUNCTIONS 가 비어 있는 지금은 자명하게 통과하지만, 항목이 생기면
    // 이 단언이 staleness 가드로 작동한다.
    assertThat(discovered).containsAll(ALLOWED_SECURITY_DEFINER_FUNCTIONS);
  }
}
