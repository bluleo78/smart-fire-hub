package com.smartfirehub.pipeline.service.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Set;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code public} 스키마의 {@code SECURITY DEFINER} 함수를 <b>카탈로그에서 전수 발견</b>해, {@link
 * SqlValidator#BLOCKED_FUNCTIONS} 또는 이 클래스의 문서화된 허용목록 중 하나에 반드시 들어 있는지 검사하고, <b>그 목록에 있는
 * 함수가 실제로 {@code validate()}에서 거부되는지까지 구동한다</b>. (이슈 #385 Task 4 리뷰 지적, 최종 리뷰 C3 로 강화)
 *
 * <p><b>왜 필요한가.</b> {@code SqlValidator}의 deny-list 는 정확하고 값싸지만, 손으로 든 목록이라 <b>새
 * {@code SECURITY DEFINER} 함수가 추가돼도 아무것도 빨개지지 않고 조용히 미한정 호출 가능 상태가 된다</b> — 그 함수들은
 * {@code TenantSchemaConformanceTest}가 이미 지적하듯 RLS 를 의도적으로 우회하도록 만들어졌고 {@code PUBLIC EXECUTE}
 * 권한을 갖는다(permitAll 호출부를 위한 것). 이 테스트는 목록을 유지하는 것이 목적이 아니라, 새 definer 가 아무 목록에도
 * 안 들어간 채 들어오는 것을 막는 것이 목적이다({@code TenantSchemaConformanceTest.
 * securityDefinerFunctionsAreKnownAndLeastPrivileged}와 같은 설계 원칙, 발견 대상만 다르다).
 *
 * <p><b>C3 — "목록에 이름이 있다"는 "실제로 막힌다"를 증명하지 않는다.</b> 최초 구현은 발견한 이름이
 * {@code BLOCKED_FUNCTIONS.contains(fn)}인지만 확인했는데, 그 시점 {@code BlockedFunctionFinder}가 함수 이름의
 * 따옴표를 벗기지 않는 결함(C2)이 있어 {@code SELECT "resolve_trigger_tenant_by_token_hash"(...)}가 실제로는
 * 통과했음에도 이 테스트는 계속 초록이었다 — 목록 대조만으로는 이 결함을 잡지 못한다. 이제 발견한 각 함수에 대해
 * {@code validate("SELECT " + fn + "(1)")} 와 인용 변형 {@code "fn"(1)}을 **실제로 구동**해 둘 다
 * {@link UnsafeSqlException}을 던지는지 확인한다. JSqlParser 는 인자 개수/타입을 검증하지 않으므로 더미 인자
 * {@code 1}로 충분하다(함수 이름 매칭만 검사 대상).
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

  /**
   * C3 강화 — 발견한 각 차단 대상 definer 함수가 {@code validate()}에서 <b>실제로</b> 거부되는지 구동한다. 목록 대조가
   * 아니라 실행 결과로 증명한다(따옴표 변형 포함 — C2 재발 방지).
   */
  @Test
  void discoveredBlockedSecurityDefinerFunctionsAreActuallyRejectedByValidate() {
    List<String> discovered =
        dsl.fetch(
                "select p.proname from pg_proc p"
                    + " join pg_namespace n on n.oid = p.pronamespace"
                    + " where n.nspname = 'public' and p.prosecdef")
            .getValues(0, String.class);

    assertThat(discovered)
        .as("public 스키마에서 SECURITY DEFINER 함수가 하나도 발견되지 않았다 — 발견 쿼리를 의심하라")
        .isNotEmpty();

    SqlValidator permissive = new SqlValidator("data", true);
    org.assertj.core.api.SoftAssertions softly = new org.assertj.core.api.SoftAssertions();
    for (String fn : discovered) {
      if (ALLOWED_SECURITY_DEFINER_FUNCTIONS.contains(fn)) {
        continue; // 허용목록에 있으면 거부되지 않는 것이 정상 — 지금은 공집합이라 이 분기에 안 들어온다.
      }
      softly
          .assertThatThrownBy(() -> permissive.validate("SELECT " + fn + "(1)"))
          .as("미인용 호출 '%s(1)' 이 거부되지 않았다", fn)
          .isInstanceOf(UnsafeSqlException.class);
      softly
          .assertThatThrownBy(() -> permissive.validate("SELECT \"" + fn + "\"(1)"))
          .as("인용 호출 \"%s\"(1) 이 거부되지 않았다 — C2 와 같은 우회", fn)
          .isInstanceOf(UnsafeSqlException.class);
    }
    softly.assertAll();
  }

  /** 양성 대조 — 위 두 테스트가 위양성으로 통과하는 것이 아님을 확인한다(차단 목록 밖 함수는 정상 통과한다). */
  @Test
  void ordinaryFunctionCall_notBlocked() {
    assertThatCode(() -> new SqlValidator("data", true).validate("SELECT UPPER('x')"))
        .doesNotThrowAnyException();
  }
}
