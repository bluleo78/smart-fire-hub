package com.smartfirehub.pipeline.service.executor;

import java.time.Duration;
import java.util.List;

/**
 * 두 실행기 클라이언트({@link ExecutorClient}·{@link DbExecutorClient})가 공유하는 {@code /execute/sql} HTTP 대기
 * 규칙(WD-262).
 *
 * <p>왜 한곳에: 두 실행기 모두 선행 문장(출력 잠금·비우기 DELETE)에 별도 합계 상한을 주고 본문에는 본문 기한을 따로 준다. 실행기가 끝나는 최악 시점이 「선행
 * 상한 + 본문 기한」이라는 규칙이 같으므로, 한쪽만 고쳐 대기 규칙이 갈라지지 않게 한 메서드로 둔다.
 */
final class ExecutorWaits {

  private ExecutorWaits() {}

  /**
   * 본문 대기에 선행 문장이 하나라도 있으면 선행 상한을 더한다(null·빈 목록은 본문 대기 그대로).
   *
   * @param base 본문 대기(기본 60초, 또는 요청별 시간 제한 + 여유)
   * @param preCap 선행 문장 합계 상한 — 실행기 쪽 선행 상한과 같은 값
   * @param pre 선행 문장 목록(null 가능)
   */
  static Duration sqlWait(Duration base, Duration preCap, List<String> pre) {
    return pre == null || pre.isEmpty() ? base : base.plus(preCap);
  }
}
