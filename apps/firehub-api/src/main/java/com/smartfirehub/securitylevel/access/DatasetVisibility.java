package com.smartfirehub.securitylevel.access;

import com.smartfirehub.securitylevel.ai.AiCall;
import java.util.Objects;

/**
 * 목록 조회의 데이터셋 가시성 — "이 조회자에게 어떤 데이터셋이 보이는가"를 도메인 값으로 담는다(WD-485).
 *
 * <p>왜 값 타입인가: 예전에는 서비스가 jOOQ {@code Condition} 을 만들어 리포지토리에 넘겼다. 그러면 서비스가 jOOQ 에 묶이고, 리포지토리 구현을 다른
 * DB 로 갈아 끼울 수 없다. 이 값은 판정 입력(자격·AI 문맥)만 들고, SQL 로 옮기는 일은 리포지토리 구현(PG 는 {@code
 * JooqDatasetVisibility})이 맡는다.
 *
 * <p>AI 문맥은 <b>생성 시점에 캡처</b>한다 — 서비스가 예전에 조건을 만들던 바로 그 자리에서 {@link DatasetAccessGuard#visibility()}
 * 로 만들어 넘기므로, 나중에 다른 AI 범위 안에서 렌더해도 SQL 이 바뀌지 않는다(예전 Condition 과 같은 의미).
 *
 * <p>제한 없음은 명시 표지({@code unrestricted})로만 나타낸다 — 자격이 null 인 것을 제한 없음으로 읽으면, 자격 계산이 실수로 null 을 내도
 * 조용히 모든 데이터셋이 보이는(fail-open) 길이 생긴다. 그래서 표지가 없는 값은 자격 null 을 거부한다.
 *
 * @param clearance 조회자 자격. 제한 없음({@link #UNRESTRICTED})일 때만 null
 * @param aiCall 캡처한 AI 문맥. {@code null} 이면 비AI 문맥(VIEW 규칙만)
 * @param unrestricted 제한 없음 표지 — 저장 쿼리 감사 비교·차트 생성·러너 내부 경로처럼 데이터셋 이름을 응답에 싣지 않아 예전에 {@code
 *     trueCondition} 을 넘기던 곳. 참이면 자격·AI 문맥이 없어야 한다
 */
public record DatasetVisibility(Clearance clearance, AiCall aiCall, boolean unrestricted) {

  /** 제한 없음 — 모든 데이터셋이 보인다. 예전 {@code DSL.trueCondition()} 과 같은 SQL 로 렌더된다. */
  public static final DatasetVisibility UNRESTRICTED = new DatasetVisibility(null, null, true);

  /** 표지가 없으면 자격이 반드시 있어야 하고(fail-closed), 제한 없음이면 자격·AI 문맥을 함께 들지 않는다(의미가 섞이지 않게). */
  public DatasetVisibility {
    if (unrestricted) {
      if (clearance != null || aiCall != null) {
        throw new IllegalArgumentException("제한 없는 가시성은 자격·AI 문맥을 갖지 않는다");
      }
    } else {
      Objects.requireNonNull(clearance, "clearance");
    }
  }

  /** 자격 기반 가시성 — 자격은 null 일 수 없다. */
  public DatasetVisibility(Clearance clearance, AiCall aiCall) {
    this(clearance, aiCall, false);
  }
}
