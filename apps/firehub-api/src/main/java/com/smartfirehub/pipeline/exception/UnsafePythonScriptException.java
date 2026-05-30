package com.smartfirehub.pipeline.exception;

/**
 * 파이프라인 PYTHON 스텝이 안전 정책을 위반했을 때 던진다. 저장 시·실행 시 양쪽에서 사용한다. (#270)
 *
 * <p>차단 대상은 ETL 에 정당하게 쓰일 일이 없는 shell/동적 코드 실행 원시함수({@code subprocess}, {@code os.system}, {@code
 * eval}, {@code exec}, {@code __import__} 등)에 한정한다. DB 접근(psycopg2/DB_URL)·네트워크(urllib)·데이터
 * 처리(pandas/numpy)는 문서화된 정당 패턴이므로 차단하지 않는다.
 *
 * <p>주의: 이 검증은 보안 경계가 아니라 <b>defense-in-depth + escalation 탐지</b>다. 실제 격리는 executor 의 nsjail 샌드박스 +
 * {@code pipeline_executor} 역할(data 스키마 한정) + env 격리(스크립트 env 에 토큰 미주입)가 담당한다.
 *
 * <p>HTTP 매핑: 400 Bad Request — {@code GlobalExceptionHandler} 참고.
 */
public class UnsafePythonScriptException extends RuntimeException {

  public UnsafePythonScriptException(String message) {
    super(message);
  }
}
