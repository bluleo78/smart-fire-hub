package com.smartfirehub.pipeline.service;

import com.smartfirehub.dataset.service.DataExceptionTranslator;
import com.smartfirehub.pipeline.service.executor.ExecutorClient.PgError;
import java.util.List;
import java.util.regex.Pattern;
import org.postgresql.util.ServerErrorMessage;

/**
 * 파이프라인 스텝 SQL 이 실패했을 때 실행 기록에 저장할 오류 문구를 만든다(WD-111) — "한국어 안내(고칠 방법) + 원인 원문" 형태.
 *
 * <p><b>왜 필요한가.</b> 예전에는 예외 메시지를 그대로 저장해 {@code SQL execution failed: SQL [INSERT INTO <스키마>."out"
 * …]; ERROR: duplicate key …} 처럼 러너가 만든 내부 SQL 문(사용자가 쓰지 않은 INSERT·MERGE·probe 래핑)과 영어 원문만 보였고,
 * 사용자는 무엇을 고칠지 알 수 없었다. 여기서는 PostgreSQL SQLState 로 흔한 오류를 분류해 고칠 방법을 앞에 두고, 원문은 서버 구조화 오류의 주
 * 메시지·DETAIL·HINT 만 남긴다 — jOOQ 의 {@code SQL [...]} 메아리와 내부 SQL 기준의 {@code Position} 은 버린다.
 *
 * <p><b>원문 노출 규칙은 바꾸지 않는다.</b> 저장본에는 지금처럼 원문이 담기고, 조회 시점에 실행 주체·관리자가 아니면 문구 전체를 가린다(WD-27, {@code
 * PipelineService#withholdRawErrorsFromViewer}). 이 클래스는 저장 문구의 모양만 정한다.
 *
 * <p>SQLState 22xxx(데이터 오류) 사유 문장은 데이터셋 쓰기·임포트와 같은 {@link
 * DataExceptionTranslator#describeDataError} 를 재사용하고, PG 원인 사슬 탐색도 {@link
 * DataExceptionTranslator#findServerError} 를 그대로 쓴다(번역 규칙이 두 벌로 갈리지 않게).
 */
public final class PipelineSqlErrorMessages {

  /** SQL 실행(INSERT/MERGE 래핑 적재·사용자 DML) 실패 문구 접두. 실행기 켠 경로와 끈 경로가 같은 접두를 쓴다. */
  public static final String EXECUTION_PREFIX = "SQL 실행 실패";

  /**
   * 결과 컬럼 분석(probe) 실패 문구 접두. ai-agent pipeline-builder 규칙(#682)이 이 접두와 뒤의 {@code permission denied
   * for schema} 원문으로 권한 문제를 알아본다 — 접두를 바꾸지 말 것.
   */
  public static final String PROBE_PREFIX = "SQL 컬럼 타입 분석 실패";

  /**
   * 선행 스텝 실패·건너뜀으로 실행하지 않은 스텝(SKIPPED)에 저장하는 안내. 오류가 아니라 실행하지 않은 이유다.
   *
   * <p>웹 {@code ExecutionStepPanel.tsx} 의 {@code SkippedStepNotice} 가 같은 문장을 고정 문구로 보여 준다(옛 기록·가림
   * 문구와 무관하게) — 문구를 바꾸면 함께 고칠 것.
   */
  public static final String SKIPPED_MESSAGE = "선행 스텝이 실패하거나 건너뛰어져 이 스텝은 실행하지 않았습니다.";

  /** 안내와 원문 사이 구분 — 웹은 저장 문구를 그대로 보여 주므로 줄을 나눠 원문이 안내와 섞여 읽히지 않게 한다. */
  private static final String CAUSE_LABEL = "\n원인: ";

  /** 분류하지 못한 SQLState 의 안내. */
  private static final String GENERIC_GUIDE = "데이터베이스가 SQL 실행을 거부했습니다. 아래 원인을 확인해 SQL 을 고쳐 주세요.";

  /**
   * psycopg2 가 오류 문자열에 붙이는 위치 표시 줄({@code LINE 1: INSERT INTO …} 과 그 아래 캐럿 줄). 위치는 러너가 감싼 내부 SQL
   * 기준이고 그 줄에 내부 SQL 이 그대로 실린다.
   */
  private static final Pattern POSITION_LINES =
      Pattern.compile("(?m)^LINE \\d+:.*(?:\\R[ \\t]*\\^[ \\t]*)?(?:\\R|$)");

  private PipelineSqlErrorMessages() {}

  /**
   * 예외의 원인 사슬에서 PG 서버 구조화 오류를 찾아 "접두: 안내\n원인: 원문" 을 만든다. 서버 오류가 없으면(연결 실패 등) 근본 원인 메시지만 붙인다 — jOOQ
   * 바깥 예외 메시지에는 실행한 SQL 이 통째로 들어 있어 쓰지 않는다.
   *
   * @param prefix {@link #EXECUTION_PREFIX} 또는 {@link #PROBE_PREFIX}
   */
  public static String describe(String prefix, Throwable e) {
    PgError pg = pgErrorOf(e);
    if (pg != null) {
      return describe(prefix, pg);
    }
    return prefix + ": " + rootCauseMessage(e);
  }

  /**
   * 예외의 원인 사슬에서 PG 서버 구조화 오류를 꺼내 실행기 응답과 같은 모양({@link PgError})으로 만든다. 없으면 null — 실행기를 끈 경로와 켠 경로가
   * 같은 판정(안내 분류·MERGE 번역)을 거치게 한다.
   */
  public static PgError pgErrorOf(Throwable e) {
    ServerErrorMessage sem = DataExceptionTranslator.findServerError(e);
    if (sem == null || sem.getSQLState() == null) {
      return null;
    }
    return new PgError(
        sem.getSQLState(), sem.getMessage(), sem.getDetail(), sem.getHint(), sem.getColumn());
  }

  /**
   * MERGE 스텝 실행 오류를 출력 PK 를 짚는 한국어 안내로 바꾼다. MERGE 문맥이 아닌 오류면 null — 호출부가 일반 문구({@link
   * #describe}·{@link #describeExecutorError})를 쓴다. 일반 안내의 21000 분기({@link #guide})와 MERGE 문맥 안내가 이
   * 클래스 한곳에서 정해진다.
   *
   * <p>두 가지 PostgreSQL 오류를 처리한다(Fix round 1, must 2 / must 4):
   *
   * <ul>
   *   <li>{@link MergeSqlBuilder#DUPLICATE_KEY_PG_MESSAGE}(SQLState 21000) — SELECT 가 같은 PK 를 두 번
   *       이상 낼 때.
   *   <li>{@link MergeSqlBuilder#NO_UNIQUE_CONSTRAINT_PG_MESSAGE}(SQLState 42P10) — PK 유니크 인덱스(이름은
   *       {@code ux_<table>_pk} 이거나 REPLACE·긴 이름이면 다를 수 있다)가 (동시 생성 실패 등으로) INVALID 상태라
   *       메타데이터(is_primary_key=true)와 실제 제약이 어긋날 때.
   * </ul>
   *
   * <p><b>판정 순서.</b> 구조화 오류({@code pg})가 있으면 SQLState 와 <b>주 메시지</b>로 판정한다. SQLState 만으로는 안 된다 —
   * 21000 에는 "더 많은 행을 돌려준 스칼라 하위 쿼리"가, 42P10 에는 다른 열 참조 오류가 함께 들어 있어 MERGE 키 안내로 오역된다. 구조화 오류가 없을
   * 때(PG 오류를 감싸지 않은 예외·구조화 필드를 보내지 않는 구버전 실행기)만 오류 문자열 부분 일치로 폴백한다.
   *
   * @param pg 구조화 오류(없으면 null)
   * @param rawMessage 실행기 오류 문자열 또는 로컬 경로 예외 메시지(폴백 판정용)
   * @param pkColumns 출력 데이터셋 PK 컬럼(안내 문구용)
   */
  public static String describeMergeError(PgError pg, String rawMessage, List<String> pkColumns) {
    boolean duplicateKey;
    boolean noUniqueConstraint;
    if (pg != null && pg.sqlState() != null) {
      String primary = nullToEmpty(pg.message());
      duplicateKey =
          "21000".equals(pg.sqlState())
              && primary.contains(MergeSqlBuilder.DUPLICATE_KEY_PG_MESSAGE);
      noUniqueConstraint =
          "42P10".equals(pg.sqlState())
              && primary.contains(MergeSqlBuilder.NO_UNIQUE_CONSTRAINT_PG_MESSAGE);
    } else {
      String raw = nullToEmpty(rawMessage);
      duplicateKey = raw.contains(MergeSqlBuilder.DUPLICATE_KEY_PG_MESSAGE);
      noUniqueConstraint = raw.contains(MergeSqlBuilder.NO_UNIQUE_CONSTRAINT_PG_MESSAGE);
    }
    if (duplicateKey) {
      return "SQL 결과에 같은 키("
          + String.join(", ", pkColumns)
          + ")가 두 번 이상 나옵니다. "
          + "키별로 한 행만 나오도록 SQL을 수정하세요.";
    }
    if (noUniqueConstraint) {
      return "출력 데이터셋의 PK("
          + String.join(", ", pkColumns)
          + ") 유니크 인덱스가 유효하지 않습니다. 데이터셋 설정에서 PK 를 다시 지정한 뒤 다시 실행하세요.";
    }
    return null;
  }

  /** 구조화 오류 필드로 "접두: 안내\n원인: 원문" 을 만든다. */
  public static String describe(String prefix, PgError pg) {
    return prefix + ": " + guide(pg) + CAUSE_LABEL + rawText(pg);
  }

  /**
   * 실행기(firehub-executor) SQL 실행 실패 문구. 구조화 오류({@code pg_error})가 있으면 {@link #describe(String,
   * PgError)} 와 같은 문구다. 없으면(검증 거부처럼 PG 오류가 아니거나, 구조화 필드를 보내지 않는 구버전 실행기) 오류 문자열을 그대로 쓰되 내부 SQL 이
   * 실리는 위치 표시 줄만 걷어낸다 — 안내를 추측해 붙이지 않는다(검증 거부 문구는 이미 한국어 안내다).
   */
  public static String describeExecutorError(String prefix, PgError pg, String error) {
    if (pg != null && pg.sqlState() != null) {
      return describe(prefix, pg);
    }
    // pg_error 를 보내지 않는 구버전 executor 대비 — api·executor 동시 배포가 끝난 다음 릴리스에서 위치 표시 줄
    // 제거(stripPositionLines)
    // 폴백은 지울 수 있음. 검증 거부처럼 PG 오류가 아닌 실패는 그 뒤에도 오류 문자열을 그대로 쓴다.
    return prefix + ": " + stripPositionLines(error);
  }

  /**
   * SQLState 로 고칠 방법을 담은 한국어 안내를 고른다. 원문에 이미 있는 이름(컬럼·제약)은 원인 줄이 보여 주므로, 안내는 "무엇을 확인할지"에 집중한다.
   *
   * <p>권한(42501)은 SQL 을 고치라고 하지 않는다 — 실행 계정 권한 문제를 SQL 수정으로 우회하려다 올바른 스키마 한정자를 지운 사고가 있었다(#682,
   * pipeline-builder 규칙).
   */
  static String guide(PgError pg) {
    String state = pg.sqlState();
    String column = pg.column() != null && !pg.column().isBlank() ? pg.column() : null;
    switch (state) {
      case "23505":
        return "같은 키 값이 이미 있습니다. 출력 데이터셋의 기본 키(또는 유니크) 컬럼에 이미 있는 값이나 SQL 결과 안에서 겹치는 값이 들어갔습니다."
            + " 로드 전략(REPLACE·APPEND·MERGE)과 기본 키 설정을 확인하세요.";
      case "23502":
        return (column != null ? "'" + column + "' 컬럼은" : "필수(NOT NULL) 컬럼은")
            + " 비워 둘 수 없는데 빈 값(NULL)이 들어갔습니다. SQL 결과에서 이 컬럼 값이 비지 않게 하거나 데이터셋의 NULL 허용 설정을 확인하세요.";
      case "23503":
        return "연결(참조)된 항목이 없거나 다른 곳에서 참조 중이라 처리할 수 없습니다. 참조하는 값이 실제로 있는지 확인하세요.";
      case "23514":
        return "값이 데이터셋의 제약 조건(CHECK)을 만족하지 않습니다. 값의 범위·형식을 확인하세요.";
      case "21000":
        // MERGE 스텝의 "같은 키 두 번" 은 호출부가 먼저 describeMergeError 로 PK 를 짚어 안내한다 — 여기는 MERGE 가 아닌 스텝의 일반
        // 안내.
        return "한 번에 처리할 행 안에서 같은 키가 두 번 이상 나오거나, 한 값만 나와야 할 하위 쿼리가 여러 행을 돌려줬습니다."
            + " 키별로 한 행만 나오도록 SQL 을 고쳐 주세요.";
      case "42703":
        return "SQL 에 없는 컬럼 이름이 있습니다. 컬럼 이름 철자와 참조한 테이블에 그 컬럼이 있는지 확인하세요.";
      case "42P01":
        return "SQL 에서 참조한 테이블이 없습니다. 테이블 이름과 스키마 한정자를 확인하세요.";
      case "42601":
        return "SQL 문법 오류가 있습니다. 원인에 표시된 구문 근처를 확인하세요.";
      case "42883":
        return "없는 함수이거나 인자 타입이 맞지 않는 함수·연산자를 썼습니다. 함수 이름과 인자 타입(필요하면 ::타입 변환)을 확인하세요.";
      case "42804":
        return "SQL 결과 컬럼의 타입이 출력 데이터셋 컬럼의 타입과 맞지 않습니다. CAST(값 AS 타입) 또는 ::타입 으로 변환하세요.";
      case "42702":
        return "어느 테이블의 컬럼인지 모호한 이름이 있습니다. 테이블 별칭을 붙여(예: a.id) 구분하세요.";
      case "42501":
        return "실행 계정에 이 대상에 대한 권한이 없습니다. SQL 문제가 아니라 권한 설정 문제이므로 관리자에게 확인을 요청하세요.";
      case "57014":
        return "실행 시간 제한을 넘거나 취소되어 SQL 실행이 중단되었습니다. 조건을 좁히거나 처리량을 줄여 다시 실행하세요.";
      case "40P01", "40001":
        return "다른 작업과 충돌해 SQL 실행이 취소되었습니다. 잠시 후 다시 실행하세요.";
      case "22012":
        return "0 으로 나누는 계산이 있습니다. NULLIF(분모, 0) 등으로 0 나눗셈을 피하세요.";
      default:
        break;
    }
    if (state.startsWith("22")) {
      // 데이터 오류 — 데이터셋 쓰기·임포트와 같은 사유 문장에 SQL 쪽에서 고칠 방법을 붙인다.
      return DataExceptionTranslator.describeDataError(state, column, pg.message())
          + ". SQL 결과 값이 출력 컬럼의 타입·형식·길이에 맞는지 확인하세요.";
    }
    return GENERIC_GUIDE;
  }

  /**
   * 원문 — 주 메시지·DETAIL·HINT 만. jOOQ 메아리({@code SQL [...]})·{@code Position}(내부 SQL 기준 위치)·{@code
   * Where}(내부 문맥)는 싣지 않는다.
   */
  static String rawText(PgError pg) {
    StringBuilder sb = new StringBuilder("ERROR: ").append(nullToEmpty(pg.message()));
    if (pg.detail() != null && !pg.detail().isBlank()) {
      sb.append("\nDETAIL: ").append(pg.detail());
    }
    if (pg.hint() != null && !pg.hint().isBlank()) {
      sb.append("\nHINT: ").append(pg.hint());
    }
    return sb.toString();
  }

  /** 오류 문자열에서 {@code LINE n: …} 위치 표시 줄과 캐럿 줄을 걷어내고 앞뒤 공백을 정리한다. */
  static String stripPositionLines(String error) {
    if (error == null) {
      return "";
    }
    return POSITION_LINES.matcher(error).replaceAll("").strip();
  }

  /**
   * 서버 구조화 오류가 없을 때의 근본 원인 메시지 — 원인 사슬의 가장 안쪽 메시지(jOOQ 바깥 메시지에는 실행 SQL 이 통째로 있다). 안쪽 메시지가 없으면 바깥
   * 메시지로 폴백한다.
   */
  private static String rootCauseMessage(Throwable e) {
    String found = null;
    Throwable t = e;
    for (int i = 0; t != null && i < 16; i++, t = t.getCause()) {
      if (t != e && t.getMessage() != null && !t.getMessage().isBlank()) {
        found = t.getMessage();
      }
    }
    if (found != null) {
      return found;
    }
    return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }
}
