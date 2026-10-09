package com.smartfirehub.securitylevel.ai;

import static com.smartfirehub.jooq.Tables.DATASET;
import static com.smartfirehub.jooq.Tables.SECURITY_LEVEL;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 임베딩 경로(메타 임베딩·문서 청크·행 검색 색인)의 AI 판정(스펙 §4.3). 사용자가 없으므로 VIEW 없이 <b>등급 ai_policy × 임베딩 공급자
 * 호스팅</b>만 본다 — DatasetAccessPolicy#aiAllowedForLevel 과 같은 규칙(SQL 은
 * DatasetAccessGuard#aiPolicyAllows).
 *
 * <p>배경 잡(@Async·JobRunr·@Scheduled)에서 불리므로 메서드 트랜잭션으로 RLS GUC 를 세운다. 요청 스코프 문맥(AiCallContext)은 쓰지
 * 않는다 — 배경 스레드에는 전파되지 않기 때문이다. 대신 <b>TenantContext 가 없으면 예외</b>로 끝낸다: 문맥 없이 조용히 "불허"로 답하면 RLS 0행
 * 때문에 멀쩡한 데이터셋의 벡터를 지우거나 색인을 키워드 전용으로 갈아엎게 되고, 반대로 판정을 건너뛰면 fail-open 이 된다.
 */
@Service
@RequiredArgsConstructor
public class EmbeddingAiGate {

  private final DSLContext dsl;
  private final AiHostingResolver hostingResolver;

  /** 이 데이터셋의 데이터를 현재 임베딩 공급자로 보내도 되는가. 데이터셋이 없으면 false(fail-closed). 테넌트 문맥이 없으면 예외. */
  @Transactional(readOnly = true)
  public boolean datasetAllowed(long datasetId) {
    long tenantId = TenantContext.require("임베딩 AI 판정");
    return dsl.fetchExists(
        dsl.selectOne()
            .from(DATASET)
            .join(SECURITY_LEVEL)
            .on(SECURITY_LEVEL.ID.eq(DATASET.SECURITY_LEVEL_ID))
            .where(DATASET.ID.eq(datasetId))
            // RLS 와 별개로 테넌트를 명시한다 — 소유자 커넥션에서 불려도 다른 테넌트 데이터셋을 허용으로 보지 않게.
            .and(DATASET.TENANT_ID.eq(tenantId))
            .and(
                DatasetAccessGuard.aiPolicyAllows(
                    SECURITY_LEVEL.AI_POLICY, hostingResolver.embedding())));
  }

  /** 현재 테넌트에서 임베딩 공급자로 보낼 수 없는 데이터셋 id(정리 대상). 테넌트 문맥이 없으면 예외. */
  @Transactional(readOnly = true)
  public List<Long> disallowedDatasetIds() {
    long tenantId = TenantContext.require("임베딩 AI 불허 데이터셋 조회");
    return dsl.select(DATASET.ID)
        .from(DATASET)
        .join(SECURITY_LEVEL)
        .on(SECURITY_LEVEL.ID.eq(DATASET.SECURITY_LEVEL_ID))
        .where(DATASET.TENANT_ID.eq(tenantId))
        .and(
            DSL.not(
                DatasetAccessGuard.aiPolicyAllows(
                    SECURITY_LEVEL.AI_POLICY, hostingResolver.embedding())))
        .orderBy(DATASET.ID)
        .fetch(DATASET.ID);
  }

  /**
   * 문자열 SQL 저장소용 술어(현재 테넌트의 현재 임베딩 호스팅). datasetIdExpr 은 코드 상수(예: "de.dataset_id")만 넘긴다 — 사용자 입력
   * 금지. 호스팅은 호출 시점에 읽으므로 배치마다 다시 부르면 도중의 호스팅 변경이 다음 배치부터 반영된다. 테넌트 문맥이 없으면 예외.
   */
  public String allowedDatasetSql(String datasetIdExpr) {
    TenantContext.require("임베딩 AI 판정 술어");
    return allowedDatasetSql(datasetIdExpr, hostingResolver.embedding());
  }

  /**
   * 호스팅을 정해 렌더한 술어(테스트용 진입점 겸 본체). ai_policy 규칙은 {@link DatasetAccessGuard#aiPolicyAllows} 하나를
   * 인라인 렌더해 재사용한다 — 문자열로 규칙을 다시 쓰면 jOOQ 술어·순수 함수와 세 벌이 되어 한쪽만 바뀔 수 있다. 값은 고정 enum 이름뿐이라 인라인이
   * 안전하다. 별칭(aig_d/aig_sl)은 호출부 SQL 의 별칭(de·c 등)과 겹치지 않게 고정한다. null 호스팅 = 외부.
   */
  String allowedDatasetSql(String datasetIdExpr, ProviderHosting hosting) {
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
