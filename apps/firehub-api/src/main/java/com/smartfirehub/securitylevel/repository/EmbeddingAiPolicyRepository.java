package com.smartfirehub.securitylevel.repository;

import static com.smartfirehub.jooq.Tables.DATASET;
import static com.smartfirehub.jooq.Tables.SECURITY_LEVEL;

import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * 임베딩 경로의 AI 판정(등급 ai_policy × 임베딩 공급자 호스팅)에 필요한 조회·술어 렌더를 맡는다. 판정 규칙 자체는 {@link
 * DatasetAccessGuard#aiPolicyAllows} 하나를 재사용한다.
 *
 * <p>클래스 단위 {@code @Transactional} 을 두지 않는다 — 조회 메서드는 호출 게이트(EmbeddingAiGate)의
 * {@code @Transactional(readOnly = true)} 안에서만 불리고(RLS GUC 는 그 트랜잭션이 세운다), 술어 렌더는 쿼리를 실행하지 않는다. 테넌트
 * 문맥 확인과 호스팅 해석은 게이트가 하고 이 클래스는 값만 받는다.
 */
@Repository
@RequiredArgsConstructor
public class EmbeddingAiPolicyRepository {

  private final DSLContext dsl;

  /** 데이터셋이 이 테넌트에 있고 등급 ai_policy 가 주어진 호스팅으로의 전송을 허용하는가. 데이터셋이 없으면 false. */
  public boolean datasetAllowed(long datasetId, long tenantId, ProviderHosting hosting) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(DATASET)
            .join(SECURITY_LEVEL)
            .on(SECURITY_LEVEL.ID.eq(DATASET.SECURITY_LEVEL_ID))
            .where(DATASET.ID.eq(datasetId))
            // RLS 와 별개로 테넌트를 명시한다 — 소유자 커넥션에서 불려도 다른 테넌트 데이터셋을 허용으로 보지 않게.
            .and(DATASET.TENANT_ID.eq(tenantId))
            .and(DatasetAccessGuard.aiPolicyAllows(SECURITY_LEVEL.AI_POLICY, hosting)));
  }

  /** 이 테넌트의 데이터셋이 존재하면서 주어진 호스팅으로 보낼 수 없는가. 없는(삭제된·다른 테넌트) 데이터셋은 false. */
  public boolean datasetDisallowed(long datasetId, long tenantId, ProviderHosting hosting) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(DATASET)
            .join(SECURITY_LEVEL)
            .on(SECURITY_LEVEL.ID.eq(DATASET.SECURITY_LEVEL_ID))
            .where(DATASET.ID.eq(datasetId))
            // RLS 와 별개로 테넌트를 명시한다 — 다른 테넌트 데이터셋을 불허(삭제 대상)로 보지 않게.
            .and(DATASET.TENANT_ID.eq(tenantId))
            .and(DSL.not(DatasetAccessGuard.aiPolicyAllows(SECURITY_LEVEL.AI_POLICY, hosting))));
  }

  /** 이 테넌트에서 주어진 호스팅으로 보낼 수 없는 데이터셋 id(id 오름차순). */
  public List<Long> disallowedDatasetIds(long tenantId, ProviderHosting hosting) {
    return dsl.select(DATASET.ID)
        .from(DATASET)
        .join(SECURITY_LEVEL)
        .on(SECURITY_LEVEL.ID.eq(DATASET.SECURITY_LEVEL_ID))
        .where(DATASET.TENANT_ID.eq(tenantId))
        .and(DSL.not(DatasetAccessGuard.aiPolicyAllows(SECURITY_LEVEL.AI_POLICY, hosting)))
        .orderBy(DATASET.ID)
        .fetch(DATASET.ID);
  }

  /**
   * 호스팅을 정해 렌더한 문자열 SQL 술어. ai_policy 규칙은 {@link DatasetAccessGuard#aiPolicyAllows} 하나를 인라인 렌더해
   * 재사용한다 — 문자열로 규칙을 다시 쓰면 jOOQ 술어·순수 함수와 세 벌이 되어 한쪽만 바뀔 수 있다. 값은 고정 enum 이름뿐이라 인라인이 안전하다.
   * 별칭(aig_d/aig_sl)은 호출부 SQL 의 별칭(de·c 등)과 겹치지 않게 고정한다. null 호스팅 = 외부.
   *
   * @param datasetIdExpr 호출부 SQL 의 데이터셋 id 식(코드 상수만 — 사용자 입력 금지)
   */
  public String allowedDatasetSql(String datasetIdExpr, ProviderHosting hosting) {
    var d = DATASET.as("aig_d");
    var sl = SECURITY_LEVEL.as("aig_sl");
    return dsl.renderInlined(
        DSL.exists(
            DSL.selectOne()
                .from(d)
                .join(sl)
                .on(sl.ID.eq(d.SECURITY_LEVEL_ID))
                // 호출부 SQL 의 데이터셋 id 식(코드 상수) — 바인드가 아니라 SQL 조각으로 그대로 들어간다.
                .where(d.ID.eq(DSL.field(datasetIdExpr, Long.class)))
                .and(DatasetAccessGuard.aiPolicyAllows(sl.AI_POLICY, hosting))));
  }
}
