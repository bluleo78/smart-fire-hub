package com.smartfirehub.pipeline.service;

import com.smartfirehub.dataset.storage.DataStoreBackend;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 스텝 증분 처리 플레이스홀더 {@code {{last_run_at}}} 을 감지·치환한다.
 *
 * <p>값은 엔진이 만든 타임스탬프뿐이라 SQL 주입 위험이 없다. null(첫 실행·전체 재생성)은 {@code -infinity} 로 바꿔 전체를 읽게 한다. 마이크로초를
 * 잃으면 경계 행({@code _updated_at} = 책갈피)을 놓치므로 포맷에서 정밀도를 보존한다.
 *
 * <p><b>경계 규약은 {@code >=} 다.</b> 사용자 SQL 은 반드시 {@code _updated_at >= {{last_run_at}}} 으로 쓴다 — 책갈피와
 * 정확히 같은 시각의 행은 "이미 읽었을 수도, 아직 커밋되지 않았을 수도" 있으므로 다시 읽어야 한다. 중복 읽기는 MERGE 의 멱등성이 흡수하지만, 건너뛴 행은 영원히
 * 복구되지 않는다.
 */
public final class LastRunAtPlaceholder {

  /** 공백을 허용하는 플레이스홀더 패턴 — UI/문서 예시가 {@code {{ last_run_at }}} 로 쓰여도 같게 동작해야 한다. */
  private static final Pattern PATTERN = Pattern.compile("\\{\\{\\s*last_run_at\\s*\\}\\}");

  /** 책갈피가 없을 때(첫 실행·전체 재생성 예약) 쓰는 리터럴 — 모든 행이 이 값보다 크므로 전체를 읽는다. */
  private static final String NEGATIVE_INFINITY = "'-infinity'::timestamptz";

  /** Tibero 에서 책갈피가 없을 때(첫 실행·전체 재생성 예약) 쓰는 리터럴 — 어떤 행의 {@code _UPDATED_AT} 보다 이르다(실서버로 비교 확인). */
  private static final String TIBERO_MINIMUM = "TIMESTAMP '0001-01-01 00:00:00.000000'";

  private LastRunAtPlaceholder() {}

  /** SQL 이 증분 플레이스홀더를 쓰는지 판별한다(증분 스텝 여부 판단의 유일한 기준). */
  public static boolean isUsedIn(String sql) {
    return sql != null && PATTERN.matcher(sql).find();
  }

  /**
   * 스텝이 증분 스텝인지 판정한다 — "SQL 스텝이면서 {@code {{last_run_at}}} 을 쓴다".
   *
   * <p>실행기(PipelineAsyncRunner)는 이 조건일 때만 증분 경로를 타고 그 경로에서만 전체 재생성 예약을 소비·해제한다. 그래서 저장 검증·예약
   * 이월(updatePipeline)·예약 API(setFullRebuildPending)가 모두 이 한 판정을 써야 한다(#739). 판정이 어긋나면(예: PYTHON
   * 스크립트 주석 속 {@code {{last_run_at}}}) 화면에 안 보이고 해제도 안 되는 예약이 생겼다가, 나중에 증분 SQL 로 바꾸는 순간 이월되어 확인 없이
   * 출력을 지운다.
   *
   * @param scriptType 스텝 스크립트 타입(SQL/PYTHON/API_CALL 등)
   * @param scriptContent 스크립트 본문(실행기에서는 스텝 참조 치환 후 SQL — 플레이스홀더 유무는 치환과 무관)
   */
  public static boolean isIncrementalStep(String scriptType, String scriptContent) {
    return "SQL".equals(scriptType) && isUsedIn(scriptContent);
  }

  /**
   * 플레이스홀더를 PG 타임스탬프 리터럴로 모두 치환한다 — {@link #substitute(String, OffsetDateTime, DataStoreBackend)} 의
   * PG 위임이다.
   *
   * @param value 책갈피 값. {@code null} 이면 {@code '-infinity'::timestamptz}(전체 읽기).
   */
  public static String substitute(String sql, OffsetDateTime value) {
    return substitute(sql, value, DataStoreBackend.POSTGRESQL);
  }

  /**
   * 플레이스홀더를 방언별 타임스탬프 리터럴로 모두 치환한다.
   *
   * <p>PG(Tibero 가 아닌 모든 백엔드)는 기존 출력 그대로다.
   *
   * <p><b>Tibero:</b> {@code _UPDATED_AT} 은 시간대 없는 TIMESTAMP 이고 {@code SYSTIMESTAMP} 대입으로 채워지므로
   * <b>Tibero 서버의 벽시계</b>를 담는다(서버 시간대가 UTC 라는 보장은 없다). 책갈피는 실제 시각(instant)이라, 서버 시계 기준으로 바꾼 값과 비교해야
   * 한다. 그래서 리터럴이 아니라 <b>서버에서 변환하는 식</b>을 넣는다: {@code CAST(TIMESTAMP '<UTC 시각> +00:00' AT TIME ZONE
   * TO_CHAR(SYSTIMESTAMP,'TZR') AS TIMESTAMP)} — 서버 <b>지역 시간대</b>로 옮긴 그 순간의 벽시계를 시간대 없이 얻는다. 어떤 서버
   * 시간대·세션 시간대에서도 맞다(실서버에서 세션 Asia/Seoul·UTC·America/New_York 모두 같은 값을 확인). 시간대 있는 값을 열과 직접 비교하면 세션
   * 시간대로 열을 해석해 어긋나므로(세션 서울에서 0행) 반드시 이 변환을 거친다. 마이크로초 아래는 버린다(더 이른 값이라 {@code >=} 경계에서 안전하다).
   *
   * <p><b>서머타임(WD-234):</b> 예전에는 서버의 <b>현재</b> 오프셋({@code TZH:TZM})으로 옮겨, 지난 실행이 전환 전이고 이번 실행이 전환
   * 후면 1시간 어긋났다(봄 전환에서 행을 놓침). 지역 이름({@code TZR})으로 옮기면 책갈피 순간의 오프셋을 쓴다. 오프셋 문자열을 주는 서버에서는 예전과 같다.
   * 남는 한계: 가을 전환 때 열의 벽시계가 1시간 되돌아가는 구간의 일부 행은 여전히 놓칠 수 있다(저장 쪽 문제 — 서머타임 없는 서버는 해당 없음). 열을 TSTZ 로
   * 바꾸는 근본 수정은 색인 있는 TSTZ 열의 Tibero 결함 때문에 하지 않는다(설계 §6.2 변경). {@code AT TIME ZONE} 은 리터럴에만 쓰고 열에는
   * 쓰지 않으며, 열은 시간대 없는 TIMESTAMP 라 그 결함과 무관하다.
   *
   * @param value 책갈피 값. {@code null} 이면 PG 는 {@code '-infinity'::timestamptz}, Tibero 는 {@code
   *     TIMESTAMP '0001-01-01 00:00:00.000000'}(어떤 시간대에서도 어떤 행보다 이르다 — 전체 읽기).
   * @param backend 테넌트 저장소 백엔드. {@code TIBERO} 만 Tibero 리터럴이다.
   */
  public static String substitute(String sql, OffsetDateTime value, DataStoreBackend backend) {
    String literal =
        backend == DataStoreBackend.TIBERO ? tiberoLiteral(value) : postgresLiteral(value);
    return PATTERN.matcher(sql).replaceAll(Matcher.quoteReplacement(literal));
  }

  private static String postgresLiteral(OffsetDateTime value) {
    // ISO_OFFSET_DATE_TIME 은 소수 초를 필요한 자리수만큼만 출력한다(.123456 은 그대로) — 값 손실이 없다.
    return value == null
        ? NEGATIVE_INFINITY
        : "'" + value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "'::timestamptz";
  }

  private static String tiberoLiteral(OffsetDateTime value) {
    if (value == null) {
      return TIBERO_MINIMUM;
    }
    // 열이 서버 벽시계라서, UTC 로 표현한 instant 를 서버 지역 시간대로 옮긴 뒤 시간대를 떼어 비교한다(Ruling 13, WD-234).
    return "CAST(TIMESTAMP '"
        + value.withOffsetSameInstant(ZoneOffset.UTC).format(TiberoTimestamps.UTC_MICROS)
        + " +00:00' AT TIME ZONE "
        + TiberoTimestamps.SERVER_ZONE_SQL
        + " AS TIMESTAMP)";
  }
}
