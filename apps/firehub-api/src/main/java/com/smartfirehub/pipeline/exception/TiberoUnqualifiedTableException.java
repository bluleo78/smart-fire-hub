package com.smartfirehub.pipeline.exception;

/**
 * Tibero 테넌트의 SQL 이 스키마 없는(미한정) 테이블 이름을 참조했을 때 던진다. {@link UnsafeSqlException} 의 하위 클래스라 기존
 * catch·핸들러(400, {@code GlobalExceptionHandler#handleUnsafeSql})는 그대로 적용된다.
 *
 * <p>Tibero 테넌트의 미한정 테이블 거부 전용이다. PG 검증기의 「스키마 없음」 거부는 일부러 일반 {@link UnsafeSqlException} 으로 둔다(PG
 * 지표·대화형 SQL 은 미한정 이름을 정당하게 쓸 수 있다).
 *
 * <p>왜 따로 있나: 이상탐지 지표 SQL 의 저장 시점 선검사({@code MetricSqlAccessChecker})는 "파싱 불가 SQL" 을 삼켜 폴러에 맡기는데,
 * 미한정 이름은 파싱 불가가 아니라 사용자가 고칠 수 있는 문법 요구다. 삼키면 지표가 조용히 돌지 않으므로 {@code MetricSqlAccessChecker} 가 이 하위
 * 타입으로 저장 시점 400 을 구분한다 — 따라서 중간에서 감싸거나 일반 {@link UnsafeSqlException} 으로 다시 던지면 안 된다.
 */
public class TiberoUnqualifiedTableException extends UnsafeSqlException {

  public TiberoUnqualifiedTableException(String message) {
    super(message);
  }
}
