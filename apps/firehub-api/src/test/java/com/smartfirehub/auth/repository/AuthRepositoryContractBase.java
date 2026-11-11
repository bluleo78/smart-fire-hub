package com.smartfirehub.auth.repository;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 인증 리포지토리(로그인 시도·IP 실패·리프레시 토큰) 계약 테스트의 공통 뼈대(WD-706).
 *
 * <p>세 테이블은 전역 테이블이고 로그인·리프레시는 테넌트가 정해지기 전에 일어난다(리뷰 포인트 1). 그래서 모든 시험은 {@code TenantContext} 를 비운 채
 * 돈다 — {@code IntegrationTestBase} 가 심는 기본 테넌트를 {@link #withoutTenant} 가 지운다. 행을 직접 만지는 픽스처(만료
 * 당기기·집계·사용자 만들기)는 아래 seam 으로 모은다. 기본 구현은 PG(jOOQ 평문 SQL)이고 Tibero 하위 클래스가 소유자 계정으로 같은 의미를 다시 구현한다.
 */
abstract class AuthRepositoryContractBase extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  /** 로그인·리프레시는 테넌트 전이다 — 기본 테넌트를 지우고 시작해 테넌트 컨텍스트에 기대는 구현을 잡아낸다. */
  @BeforeEach
  void withoutTenant() {
    TenantContext.clear();
  }

  /** 픽스처 갱신·삭제(자동 커밋). */
  protected void fixtureUpdate(String sql, Object... args) {
    dsl.execute(sql, args);
  }

  /** 첫 열이 숫자인 한 행을 읽는다(집계·id). */
  protected long fixtureLong(String sql, Object... args) {
    return dsl.fetchOne(sql, args).get(0, Long.class);
  }

  /** DB 시계의 "지금" 을 나타내는 SQL 식 — 만료 시각을 DB 시계 기준으로 당기고 미는 데 쓴다. */
  protected String dbNow() {
    return "now()";
  }

  /** 사용자 한 명을 만들고 id 를 돌려준다(refresh_token.user_id FK). */
  protected long createUser(String username) {
    dsl.execute(
        "insert into \"user\" (username, password, name) values (?, 'x', ?)", username, username);
    return fixtureLong("select id from \"user\" where username = ?", username);
  }

  /** 사용자를 지운다(리프레시 토큰은 FK 로 따라 지워진다). */
  protected void deleteUser(long userId) {
    dsl.execute("delete from \"user\" where id = ?", userId);
  }

  /** 호출자 트랜잭션을 연다 — 트랜잭션 범위 잠금 시험용. */
  protected void inTx(Runnable action) {
    fixtureTransactionTemplate.executeWithoutResult(status -> action.run());
  }
}
