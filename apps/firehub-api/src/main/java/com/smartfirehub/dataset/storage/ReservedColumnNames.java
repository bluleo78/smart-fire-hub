package com.smartfirehub.dataset.storage;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 새 컬럼에 쓸 수 없는 예약 컬럼명의 단일 출처 — 백엔드(방언)별 목록과 판정·안내 문구를 한 곳에 둔다(WD-635).
 *
 * <p>예전에는 같은 목록이 데이터셋 저장소(PG·Tibero), 파이프라인 스텝 방언, Tibero DDL 빌더, SQL 결과 컬럼 별칭에 따로 적혀 있어 한쪽만 바뀌면
 * 「저장 검증은 통과했는데 TEMP 생성이 거부」처럼 어긋날 수 있었다. 이제 모든 호출처가 이 클래스의 목록을 받아 쓴다.
 *
 * <p>목록은 모두 소문자이고 안내 순서를 지킨다. 비교는 {@link Locale#ROOT} 로 소문자화한다 — 기본 로캘(예: 터키어)에 따라 {@code "ID"} 가
 * {@code "ıd"} 로 바뀌어 판정이 갈리지 않게 하기 위해서다.
 *
 * <p>주의: 이 목록은 「데이터셋 물리 테이블에 이미 있거나 DB 가 막는 컬럼 이름」이다. SQL 키워드 예약어(Tibero 의 {@code LEVEL}·{@code
 * ROWID} 등 — {@code TiberoDialect.RESERVED_WORDS})나 SQL 검증기의 의사 상수와는 다른 개념이라 합치지 않는다.
 */
public final class ReservedColumnNames {

  /** 행 변경 시각 시스템 컬럼 이름 — 예약 목록의 출처인 이 클래스에 리터럴을 두어 의존이 DataTableStore → 이 클래스 한 방향이 되게 한다. */
  public static final String UPDATED_AT_COLUMN = "_updated_at";

  /**
   * 앱 시스템 컬럼(순서 고정) — 모든 백엔드의 데이터셋 물리 테이블이 갖는 컬럼이다. 결과 숨김·적재 키 제거 등 「물리적으로 있는 시스템 컬럼」 용도로도 이 목록을
   * 쓴다({@link DataTableStore#SYSTEM_COLUMN_NAMES} 가 이 목록을 가리킨다).
   */
  public static final List<String> APP_SYSTEM =
      List.of("id", "import_id", "created_at", UPDATED_AT_COLUMN);

  /**
   * PostgreSQL 이 모든 테이블에 숨겨 두는 시스템 컬럼 이름(WD-564). 사용자 컬럼으로 만들면 DDL 이 "conflicts with a system
   * column name" 으로 실패하므로 PG 백엔드에서만 예약한다 — 물리 테이블의 일반 컬럼이 아니라 {@link #APP_SYSTEM} 에는 넣지 않는다.
   */
  public static final List<String> PG_SYSTEM =
      List.of("xmin", "xmax", "cmin", "cmax", "ctid", "tableoid");

  /** PostgreSQL 백엔드의 예약 컬럼명(안내 순서) — 앱 시스템 컬럼 다음에 PG 시스템 컬럼. */
  public static final List<String> POSTGRESQL =
      Stream.concat(APP_SYSTEM.stream(), PG_SYSTEM.stream()).toList();

  /**
   * Tibero 백엔드의 예약 컬럼명 — 앱 시스템 컬럼뿐이다. PG 시스템 컬럼명 제약이 없어 그런 이름의 기존 Tibero 컬럼이 합법적으로 있을 수 있고, 복제·TEMP
   * 재생성에서 거부되면 안 된다(WD-564).
   */
  public static final List<String> TIBERO = APP_SYSTEM;

  /**
   * 백엔드별 예약 목록 전부. <b>새 백엔드의 예약 목록을 만들면 반드시 여기에 더한다</b> — {@link #ANY_BACKEND} 가 이 목록에서 나오므로, 빠뜨리면
   * 그 백엔드 예약어의 대소문자 변형이 「예약어」 대신 형식 안내를 받는다. ({@link DataStoreBackend} 의 MYSQL 처럼 아직 저장소 구현이 없는
   * 백엔드는 목록이 없다.)
   */
  public static final List<List<String>> ALL_BACKENDS = List.of(POSTGRESQL, TIBERO);

  /**
   * 어느 백엔드에서든 예약인 이름의 합집합({@link #ALL_BACKENDS} 를 펼쳐 중복 제거) — 형식 검사에 걸린 이름이 「예약어의 대소문자·형식 변형」인지 테넌트
   * 조회 없이 가려내는 데 쓴다({@code DataTableService#checkNewColumnName}). 여기 없는 이름은 저장소를 묻지 않고 형식 오류로 끝낸다.
   */
  public static final List<String> ANY_BACKEND =
      ALL_BACKENDS.stream().flatMap(List::stream).distinct().toList();

  private ReservedColumnNames() {}

  /**
   * 이름이 주어진 예약 목록에 드는지(대소문자 무시, {@link Locale#ROOT}). 모든 예약어 판정이 이 메서드 하나를 거쳐 로캘·대소문자 규칙이 갈리지 않는다.
   */
  public static boolean contains(List<String> reserved, String columnName) {
    return columnName != null && reserved.contains(columnName.toLowerCase(Locale.ROOT));
  }

  /**
   * 예약 컬럼명 안내 문구의 단일 형식 — 데이터셋 생성·추가·이름 변경, 백엔드별 저장소, 파이프라인 스텝 저장 검증이 같은 문장을 쓴다.
   *
   * @param reserved 안내에 실을 예약어 목록(순서 그대로 보여 준다)
   */
  public static String message(String columnName, List<String> reserved) {
    return "컬럼명 '" + columnName + "'은 시스템 예약어입니다. (예약어: " + String.join(", ", reserved) + ")";
  }
}
