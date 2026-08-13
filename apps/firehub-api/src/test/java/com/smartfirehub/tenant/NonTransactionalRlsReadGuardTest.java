package com.smartfirehub.tenant;

import static com.smartfirehub.support.TenantRlsTestSupport.TENANT_CANARY;
import static com.smartfirehub.support.TenantRlsTestSupport.nextTenantId;
import static com.smartfirehub.support.TenantRlsTestSupport.runInTenantTransaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * GUC 가 트랜잭션-로컬이라는 사실이 만드는 함정을 문서화하고 고정하는 가드.
 *
 * <p><b>함정</b>: {@code app.tenant_id} 는 트랜잭션 안에서만 존재한다. 따라서 {@code @Transactional}
 * 없는 서비스 메서드가 RLS 테이블을 읽으면 GUC 가 비어 있어 정책 비교가 NULL 이 되고, 예외가 아니라
 * <b>빈 결과</b>가 돌아온다. 증상은 "데이터가 없다" 또는 거짓 404 이고, 로그에는 아무 흔적이 없다.
 *
 * <p><b>왜 평소 테스트로는 안 잡히는가</b>: 테스트 프로파일이 풀 커넥션에 세션 레벨 GUC 를 심어두면
 * (예: {@code connection-init-sql}) 트랜잭션이 없어도 값이 남아 결함이 가려진다. 이 테스트는 데이터를
 * <b>다른 테넌트</b>로 커밋해 두고 앰비언트 트랜잭션 없이 읽어, 그 마스킹을 무력화한다.
 *
 * <p><b>P2 진행 시</b>: 도메인 테이블에 RLS 를 걸 때마다, 그 테이블을 읽는 모든 비-트랜잭션 메서드를
 * 찾아 {@code @Transactional(readOnly = true)} 를 붙여야 한다. 특히 인증 경로(권한 로딩)는 서블릿
 * 필터에서 호출되므로 앰비언트 트랜잭션이 없다 — 놓치면 authority 0개로 전면 403 이 된다.
 */
class NonTransactionalRlsReadGuardTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate transactionTemplate;

  /** 실행마다 고유한 테넌트 id — 공유 테스트 DB 에 누적된 다른 실행분과 섞이지 않게 한다. */
  private final long probeTenantId = nextTenantId();

  @AfterEach
  void cleanUpProbeRows() {
    // 자기 테넌트 컨텍스트에서만 지운다. 정책이 FOR ALL 이므로 DELETE 도 RLS 스코프이며,
    // 다른 테넌트(다른 세션)의 행은 구조적으로 지울 수 없다.
    runInTenantTransaction(transactionTemplate, probeTenantId, () -> dsl.deleteFrom(TENANT_CANARY).execute());
  }

  @Test
  @DisplayName("트랜잭션 없이 RLS 테이블을 읽으면 컨텍스트가 있어도 0행이다")
  void nonTransactionalReadSeesNothing() {
    // 고유 테넌트로 행 하나를 커밋한다. tenant_id 는 컬럼 DEFAULT 가 GUC 에서 채운다.
    runInTenantTransaction(
        transactionTemplate,
        probeTenantId,
        () -> dsl.insertInto(TENANT_CANARY).set(field(name("val"), String.class), "committed-in-tx").execute());

    // 아래 두 읽기는 컨텍스트를 트랜잭션 경계 없이 그대로 유지해야 하는 검증의 핵심이라, 공용
    // 헬퍼(항상 트랜잭션을 여는)로 감싸지 않고 직접 TenantContext 를 설정/해제한다.
    TenantContext.set(probeTenantId);
    try {
      // 컨텍스트는 그대로 두고, 트랜잭션 없이 읽는다 → GUC 가 없어 0행.
      int withoutTransaction = dsl.fetchCount(TENANT_CANARY);

      // 같은 컨텍스트로 트랜잭션 안에서 읽으면 보인다. 고유 테넌트라 정확히 1행이다.
      int withTransaction = transactionTemplate.execute(status -> dsl.fetchCount(TENANT_CANARY));

      assertThat(withoutTransaction)
          .as("트랜잭션 없이 읽으면 GUC 가 없어 RLS 가 전부 차단한다 — 이게 함정이다")
          .isZero();
      assertThat(withTransaction).as("트랜잭션 안에서는 자기 테넌트 행이 보인다").isEqualTo(1);
    } finally {
      TenantContext.clear();
    }
  }
}
