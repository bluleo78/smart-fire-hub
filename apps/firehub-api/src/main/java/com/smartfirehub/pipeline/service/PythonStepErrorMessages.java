package com.smartfirehub.pipeline.service;

import com.smartfirehub.dataset.service.DataExceptionTranslator;
import java.sql.SQLException;

/**
 * PYTHON 스텝의 적재 실패·타임아웃·예기치 못한 오류를 사용자에게 보일 한국어 문구(WD-211).
 *
 * <p><b>왜 필요한가.</b> 예전에는 {@code Script succeeded but data insert failed: jOOQ; bad SQL grammar
 * [INSERT INTO <데이터 스키마>.<테이블> ...]} 처럼 영어 원문과 내부 SQL·스키마가 그대로 스텝 오류에 남았고, 실행기 경로는 예기치 못한 예외의
 * traceback 전체를 사용자 문구로 썼다. 여기서는 무엇이 문제인지(어느 키·몇 번째 행)와 고칠 방법만 한국어로 남기고, 원문은 로그에만 남긴다.
 *
 * <p><b>두 경로가 같은 문구다.</b> 실행기 켠 경로(apps/firehub-executor {@code app/services/python_executor.py} 의
 * {@code UNKNOWN_COLUMN_MESSAGE}·{@code ROW_NOT_OBJECT_MESSAGE}·{@code
 * ROW_MISSING_KEY_MESSAGE}·{@code LOAD_FAILED_MESSAGE}·{@code TIMEOUT_MESSAGE}·{@code
 * UNEXPECTED_ERROR_MESSAGE})와 글자 단위로 같아야 한다 — 같은 스크립트가 두 경로에서 같은 오류를 보이게 하려는 것이다. 실행기 경로는 러너가
 * executor 오류 앞에 {@link #PREFIX} 를 붙이고, 로컬 경로는 러너가 예외를 감싸지 않으므로 여기 함수들이 접두까지 붙인다. 한쪽 문구를 바꾸면 다른 쪽도
 * 함께 바꾼다. 데이터 오류(SQLState 22) 사유 문장은 executor 가 {@code pg_error} 필드만 보내고 이 API 가 {@link
 * DataExceptionTranslator#describeDataError} 로 만든다(번역 규칙이 두 벌로 갈리지 않게).
 *
 * <p>사용자 스크립트 자체의 오류(비정상 종료 시 stderr traceback)와 WD-210 의 JSON 아님 문구({@code
 * LocalPythonOutputLoader#NOT_JSON_MESSAGE})는 이 클래스 범위가 아니다 — 앞의 것은 사용자가 고칠 정보라 그대로 보인다.
 */
public final class PythonStepErrorMessages {

  /** 러너가 Python 스텝 실패 문구 앞에 붙이는 접두. 실행기 경로는 러너가, 로컬 경로는 아래 함수가 붙인다. */
  public static final String PREFIX = "Python 실행 실패: ";

  /** 첫 행의 키가 출력 데이터셋 컬럼(시스템 컬럼 제외)에 없을 때. 인자: 키. */
  static final String UNKNOWN_COLUMN_MESSAGE =
      "출력 JSON 의 '%s' 가 출력 데이터셋 컬럼에 없습니다. 데이터셋 컬럼 이름과 같은 키로 출력해 주세요.";

  /** N번째(1부터) 행이 JSON 객체가 아닐 때. 값은 싣지 않는다 — 두 언어의 값 표기가 달라 문구가 갈라지고, 사용자 데이터가 섞인다. 인자: 행 번호. */
  static final String ROW_NOT_OBJECT_MESSAGE =
      "출력 JSON 의 %d번째 행이 객체가 아닙니다. 모든 행을 컬럼 이름을 키로 하는 JSON 객체로 출력해 주세요.";

  /** N번째(1부터) 행에 첫 행의 키가 없을 때 — 적재 컬럼은 첫 행의 키로 정해진다. 인자: 행 번호, 키. */
  static final String ROW_MISSING_KEY_MESSAGE =
      "출력 JSON 의 %d번째 행에 첫 행의 키 '%s' 가 없습니다. 모든 행에 첫 행과 같은 키를 넣어 주세요.";

  /** DB 데이터 오류(SQLState 22)로 적재가 실패했을 때. 인자: {@link DataExceptionTranslator} 사유 문장. */
  static final String LOAD_DATA_ERROR_MESSAGE =
      "출력 결과를 데이터셋에 적재하지 못했습니다: %s. 출력 JSON 의 값이 데이터셋 컬럼의 타입·형식·길이에 맞는지 확인해 주세요.";

  /** 그 밖의 적재 실패. 원문(내부 SQL 포함)은 로그에만 남긴다. */
  static final String LOAD_FAILED_MESSAGE = "출력 결과를 데이터셋에 적재하지 못했습니다. 출력 JSON 의 키와 값 형식을 확인해 주세요.";

  /** 실행 시간 제한 초과. 인자: 제한(초) — 경로마다 자기 제한 값을 넣는다(로컬 300초, 실행기는 설정·요청 값). */
  static final String TIMEOUT_MESSAGE = "실행 시간 제한(%d초)을 넘어 스크립트를 중단했습니다.";

  /** 스크립트 오류가 아닌 실행기 쪽 예기치 못한 예외. traceback 은 로그(와 실행기 output)에만 남긴다. */
  static final String UNEXPECTED_ERROR_MESSAGE =
      "스크립트 실행 중 예기치 못한 오류가 발생했습니다. 잠시 후 다시 실행하고, 계속되면 관리자에게 문의해 주세요.";

  private PythonStepErrorMessages() {}

  static String unknownColumn(String key) {
    return PREFIX + String.format(UNKNOWN_COLUMN_MESSAGE, key);
  }

  /**
   * @param rowNumber 1부터 세는 행 번호
   */
  static String rowNotObject(int rowNumber) {
    return PREFIX + String.format(ROW_NOT_OBJECT_MESSAGE, rowNumber);
  }

  /**
   * @param rowNumber 1부터 세는 행 번호
   */
  static String rowMissingKey(int rowNumber, String key) {
    return PREFIX + String.format(ROW_MISSING_KEY_MESSAGE, rowNumber, key);
  }

  /**
   * 로컬 경로 적재 예외의 사용자 문구. 원인 사슬에 DB 데이터 오류(22xxx)가 있으면 그 사유를, 아니면 일반 문구를 쓴다 — 예외 메시지(jOOQ 의 SQL
   * 메아리)는 쓰지 않는다.
   */
  static String loadFailed(Throwable e) {
    String reason = DataExceptionTranslator.describe(e);
    if (reason == null) {
      // Tibero JDBC 는 SQLState 22 대신 벤더 코드를 준다. 파이프라인 SQL 오류와 같은 대응표를 거쳐 사유만 노출한다. 사슬 걷기·깊이 상한은
      // DataExceptionTranslator 와 공용이다.
      SQLException tiberoDataError =
          DataExceptionTranslator.findSqlException(
              e, x -> PipelineSqlErrorMessages.tiberoSqlState(x.getErrorCode()).startsWith("22"));
      if (tiberoDataError != null) {
        reason =
            DataExceptionTranslator.describeDataError(
                PipelineSqlErrorMessages.tiberoSqlState(tiberoDataError.getErrorCode()),
                null,
                null);
      }
    }
    return reason != null ? loadDataError(reason) : PREFIX + LOAD_FAILED_MESSAGE;
  }

  /** 데이터 오류 사유 문장으로 적재 실패 문구를 만든다(로컬 경로·실행기 {@code pg_error} 공용). */
  static String loadDataError(String reason) {
    return PREFIX + String.format(LOAD_DATA_ERROR_MESSAGE, reason);
  }

  static String timeout(long seconds) {
    return PREFIX + String.format(TIMEOUT_MESSAGE, seconds);
  }

  static String unexpectedError() {
    return PREFIX + UNEXPECTED_ERROR_MESSAGE;
  }

  /**
   * 실행기 Python 실행 실패 응답의 사용자 문구. 데이터 오류({@code pg_error} 의 SQLState 22)면 그 사유로 만들고, 아니면 executor 오류
   * 문자열(이미 한국어로 분류된 문구이거나 사용자 스크립트 stderr)에 접두만 붙인다. {@code pg_error} 를 보내지 않는 구버전 실행기는 오류 문자열
   * 그대로다.
   */
  static String fromExecutor(String error, String pgSqlState, String pgColumn, String pgMessage) {
    if (pgSqlState != null && pgSqlState.startsWith("22")) {
      return loadDataError(
          DataExceptionTranslator.describeDataError(pgSqlState, pgColumn, pgMessage));
    }
    return PREFIX + error;
  }
}
