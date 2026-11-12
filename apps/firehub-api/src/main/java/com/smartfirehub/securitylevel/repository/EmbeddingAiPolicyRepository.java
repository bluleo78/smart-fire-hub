package com.smartfirehub.securitylevel.repository;

import com.smartfirehub.securitylevel.access.ProviderHosting;
import java.util.List;

/**
 * 임베딩·AI 호출 허용 등급 정책을 dataset 과 security_level 테이블에서 읽는 리포지토리.
 *
 * <p>구현은 {@code JooqEmbeddingAiPolicyRepository}(jOOQ/PostgreSQL, WD-485)·{@code
 * MyBatisEmbeddingAiPolicyRepository}(MyBatis — Tibero 플랫폼 DB, WD-711) 이다. MyBatis 판의 {@link
 * #allowedDatasetSql} 은 꺼진 기능 예외(VECTOR_SEARCH)다.
 */
public interface EmbeddingAiPolicyRepository {

  /** 데이터셋이 이 테넌트에 있고 등급 ai_policy 가 주어진 호스팅으로의 전송을 허용하는가. 데이터셋이 없으면 false. */
  boolean datasetAllowed(long datasetId, long tenantId, ProviderHosting hosting);

  /** 이 테넌트의 데이터셋이 존재하면서 주어진 호스팅으로 보낼 수 없는가. 없는(삭제된·다른 테넌트) 데이터셋은 false. */
  boolean datasetDisallowed(long datasetId, long tenantId, ProviderHosting hosting);

  /** 이 테넌트에서 주어진 호스팅으로 보낼 수 없는 데이터셋 id(id 오름차순). */
  List<Long> disallowedDatasetIds(long tenantId, ProviderHosting hosting);

  /**
   * 호스팅을 정해 렌더한 문자열 SQL 술어 — 문자열 SQL 을 조립하는 임베딩 리포지토리 구현(카탈로그 "de.dataset_id"·문서 청크
   * "c.dataset_id")이 자기 별칭으로 부른다. 서비스는 이 문자열을 만들지 않고 {@code EmbeddingAllowance} 를 넘긴다(WD-485 T10).
   * ai_policy 규칙은 {@code JooqDatasetVisibility#aiPolicyAllows} 하나를 인라인 렌더해 재사용한다 — 문자열로 규칙을 다시 쓰면
   * jOOQ 술어·순수 함수와 세 벌이 되어 한쪽만 바뀔 수 있다. 값은 고정 enum 이름뿐이라 인라인이 안전하다. 별칭(aig_d/aig_sl)은 호출부 SQL 의
   * 별칭(de·c 등)과 겹치지 않게 고정한다. null 호스팅 = 외부.
   *
   * @param datasetIdExpr 호출부 SQL 의 데이터셋 id 식(코드 상수만 — 사용자 입력 금지)
   */
  String allowedDatasetSql(String datasetIdExpr, ProviderHosting hosting);
}
