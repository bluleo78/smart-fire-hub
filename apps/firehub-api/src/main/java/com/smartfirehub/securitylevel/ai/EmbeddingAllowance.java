package com.smartfirehub.securitylevel.ai;

import com.smartfirehub.securitylevel.access.ProviderHosting;
import java.util.Objects;

/**
 * 임베딩 경로의 등급 허용 판정 입력 — "지금 어떤 호스팅의 임베딩 공급자로 보내는가"를 도메인 값으로 담는다(WD-485 T10).
 *
 * <p>왜 값 타입인가: 예전에는 서비스가 {@code EmbeddingAiGate#allowedDatasetSql("de.dataset_id")} 로 PG SQL 조각을
 * 만들어 리포지토리에 넘겼다. 그러면 리포지토리 인터페이스가 PG 문자열에 묶이고, 별칭("de.dataset_id"·"c.dataset_id")을 호출자가 알아야 한다. 이
 * 값은 판정 입력(호스팅)만 들고, SQL 로 옮기는 일과 별칭은 리포지토리 구현이 맡는다({@code DatasetVisibility} 와 같은 방식).
 *
 * <p>호스팅은 <b>생성 시점에 캡처</b>한다 — {@link EmbeddingAiGate#allowance()} 가 예전 술어를 만들던 바로 그 자리에서 읽으므로, 렌더
 * 전에 호스팅 설정이 바뀌어도 이미 캡처한 값으로 렌더된다(예전 문자열 술어와 같은 의미).
 *
 * <p>"제한 없음" 값은 일부러 두지 않는다 — 정책 없는 경로는 등급 우회가 된다.
 *
 * @param hosting 캡처한 임베딩 공급자 호스팅. null 금지(호스팅을 모를 때의 해석은 설정 서비스가 이미 외부로 정한다)
 * @see com.smartfirehub.securitylevel.access.DatasetVisibility
 */
public record EmbeddingAllowance(ProviderHosting hosting) {

  /** null 호스팅을 받지 않는다 — 판정 입력이 빠진 값이 조용히 렌더되지 않게(fail-closed). */
  public EmbeddingAllowance {
    Objects.requireNonNull(hosting, "hosting");
  }
}
