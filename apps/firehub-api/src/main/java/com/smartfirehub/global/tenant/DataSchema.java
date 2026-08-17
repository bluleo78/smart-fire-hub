package com.smartfirehub.global.tenant;

/**
 * 동적 사용자 테이블이 사는 <b>물리 스키마명을 조립하는 유일한 지점</b>.
 *
 * <p>지금까지 {@code data} 라는 스키마명은 API 전역에 문자열 리터럴로 흩어져 있었다(P3-a 착수
 * 시점 실측 5파일 74곳). 스키마가 하나뿐인 동안에는 그래도 동작하지만, 테넌트별로 스키마를
 * 나누는 순간 그 74곳이 전부 개별 결함이 된다. 이 클래스는 그 74곳을 하나로 모아 <b>바꿀 곳을
 * 한 군데로</b> 만든다.
 *
 * <p><b>호출부는 스키마명을 문자열로 주고받지 않는다.</b> 스키마명을 파라미터로 받는 메서드를
 * 만드는 순간 "누가 그 값을 정했는가" 가 다시 흩어지고, 이 클래스는 우회 가능한 장식이 된다.
 * 이 규약은 {@code DataSchemaResolutionTest} 의 규약 가드가 소스 스캔으로 강제한다.
 */
public final class DataSchema {

  /**
   * 오늘의 물리 스키마명. <b>이 상수를 참조하는 곳은 {@link #current()} 하나뿐이어야 한다</b> —
   * P3-b 에서 바꿀 지점을 한 줄로 유지하기 위해서다.
   */
  private static final String PHYSICAL_SCHEMA = "data";

  private DataSchema() {}

  /**
   * 현재 테넌트의 데이터 스키마 식별자를 돌려준다(인용 없음).
   *
   * <p><b>왜 테넌트 id 를 실제로 요구하면서 그 값을 쓰지 않는가 — 이게 이 클래스의 핵심 설계다.</b>
   * 오늘 물리 스키마는 {@code data} 하나뿐이므로 테넌트 id 는 반환값에 영향을 주지 않는다. 겉보기엔
   * 불필요한 의식(ceremony)이다. 그러나 P3-b 가 스키마를 {@code data_t{tenantId}} 로 개명하는 순간
   * 이 메서드는 테넌트 id 없이는 답을 만들 수 없게 된다. 그때 가서 요구하기 시작하면, 컨텍스트가
   * 비어 있던 모든 경로(배경 잡·permitAll 트리거·스케줄러)가 <b>한꺼번에</b> 처음으로 터진다 —
   * 그것도 테스트가 아니라 dev 에서. 지금 요구해 두면 그 경로들이 이번 밴드의 테스트 스위트에서
   * 하나씩 드러난다. 즉 "쓰지 않는 인자" 가 아니라 <b>P3-b 의 실패를 앞당겨 받는 장치</b>다.
   *
   * <p>P3-b 에서 바뀌는 것: 아래 반환문이 {@code PHYSICAL_SCHEMA + "_t" + tenantId} 가 된다.
   * 호출부는 한 곳도 바뀌지 않는다.
   *
   * @throws MissingTenantScopeException 테넌트 컨텍스트가 없을 때. 조용히 기본 스키마로 떨어지지
   *     않는다 — 그 폴백이 곧 크로스 테넌트 접근이 된다.
   */
  public static String current() {
    TenantContext.require("data 스키마 식별자 해석");
    return PHYSICAL_SCHEMA;
  }

  /**
   * 테이블명을 현재 테넌트의 데이터 스키마로 한정한다 — {@code data."tbl"} 형태.
   *
   * <p>테이블명은 <b>항상</b> 큰따옴표로 감싼다. 동적 테이블명은 사용자가 만든 데이터셋에서 오므로
   * PostgreSQL 예약어와 겹칠 수 있고, 인용하지 않으면 문법 오류가 된다.
   *
   * <p>이름에 든 {@code "} 는 {@code ""} 로 이중화한다. 이중화하지 않으면 식별자가 조기에 닫히고
   * 뒤따르는 문자가 SQL 문법으로 해석돼, 이름 검증을 통과한 경로에서도 인젝션이 성립한다.
   * (호출부는 대개 {@code [a-z][a-z0-9_]*} 로 이미 걸러 두지만, 조립 지점이 스스로 안전해야
   * 검증을 잊은 새 호출부가 생겨도 무해하다.)
   */
  public static String qualify(String tableName) {
    return current() + ".\"" + tableName.replace("\"", "\"\"") + "\"";
  }
}
