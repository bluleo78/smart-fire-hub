package com.smartfirehub.securitylevel.ai;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.securitylevel.repository.EmbeddingAiPolicyRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
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

  private final EmbeddingAiPolicyRepository policyRepository;
  private final AiHostingResolver hostingResolver;

  /** 이 데이터셋의 데이터를 현재 임베딩 공급자로 보내도 되는가. 데이터셋이 없으면 false(fail-closed). 테넌트 문맥이 없으면 예외. */
  @Transactional(readOnly = true)
  public boolean datasetAllowed(long datasetId) {
    long tenantId = TenantContext.require("임베딩 AI 판정");
    return policyRepository.datasetAllowed(datasetId, tenantId, hostingResolver.embedding());
  }

  /**
   * 현재 테넌트의 이 데이터셋이 지금 임베딩 공급자로 보낼 수 없는가(정리 대상 단건 판정 — {@link #disallowedDatasetIds()} 의 단건판).
   * datasetAllowed 의 부정이 아니다: 없는(삭제된·다른 테넌트) 데이터셋은 false — 지울 근거가 없으므로 남의 id 로 삭제를 시도하지 않게 한다. 테넌트
   * 문맥이 없으면 예외.
   */
  @Transactional(readOnly = true)
  public boolean datasetDisallowed(long datasetId) {
    long tenantId = TenantContext.require("임베딩 AI 불허 판정(데이터셋)");
    return policyRepository.datasetDisallowed(datasetId, tenantId, hostingResolver.embedding());
  }

  /** 현재 테넌트에서 임베딩 공급자로 보낼 수 없는 데이터셋 id(정리 대상). 테넌트 문맥이 없으면 예외. */
  @Transactional(readOnly = true)
  public List<Long> disallowedDatasetIds() {
    long tenantId = TenantContext.require("임베딩 AI 불허 데이터셋 조회");
    return policyRepository.disallowedDatasetIds(tenantId, hostingResolver.embedding());
  }

  /**
   * 문자열 SQL 저장소용 술어(현재 테넌트의 현재 임베딩 호스팅). datasetIdExpr 은 코드 상수(예: "de.dataset_id")만 넘긴다 — 사용자 입력
   * 금지. 호스팅은 호출 시점에 읽으므로 배치마다 다시 부르면 도중의 호스팅 변경이 다음 배치부터 반영된다. 테넌트 문맥이 없으면 예외.
   */
  public String allowedDatasetSql(String datasetIdExpr) {
    TenantContext.require("임베딩 AI 판정 술어");
    return allowedDatasetSql(datasetIdExpr, hostingResolver.embedding());
  }

  /** 호스팅을 정해 렌더한 술어(테스트용 진입점) — 렌더 본체는 {@link EmbeddingAiPolicyRepository#allowedDatasetSql}. */
  String allowedDatasetSql(String datasetIdExpr, ProviderHosting hosting) {
    return policyRepository.allowedDatasetSql(datasetIdExpr, hosting);
  }
}
