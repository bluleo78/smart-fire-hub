package com.smartfirehub.global.tenant;

import java.util.Map;

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
 *
 * <p><b>P3-b2: 물리 스키마가 테넌트별로 나뉜다.</b> 테넌트 1(멀티 테넌시 도입 이전부터 존재하던
 * 유일한 워크스페이스)은 기존 {@code data} 스키마를 그대로 쓰고, 그 외 테넌트는 {@code
 * data_t{tenantId}} 를 받는다. 리네임은 없다 — 기존 데이터는 있던 자리에 그대로 있다.
 */
public final class DataSchema {

  /**
   * 기본(레거시) 테넌트의 물리 스키마 매핑.
   *
   * <p>테넌트 1 은 멀티 테넌시 도입 이전부터 존재하던 유일한 워크스페이스이고, 그 데이터는
   * 처음부터 {@code data} 스키마에 있다. 이 매핑을 설정(application-*.yml)으로 빼지 않는 이유:
   * 프로필마다 값이 갈라지면 prod 가 실제로 쓰는 매핑이 테스트에서 한 번도 실행되지 않는다.
   * "테넌트 1 = data" 는 환경 변수가 아니라 되돌릴 수 없는 역사적 사실이다.
   *
   * <p>이 맵 덕분에 스키마 <b>리네임이 필요 없다</b> — 기존 데이터는 있던 자리에 그대로 있고,
   * 신규 테넌트만 새 스키마를 받는다.
   */
  private static final Map<Long, String> LEGACY_SCHEMA_BY_TENANT = Map.of(1L, "data");

  /** 신규 테넌트 스키마 접두사. 롤 이름 규약({@code pipeline_executor_t{id}})과 같은 형태다. */
  private static final String TENANT_SCHEMA_PREFIX = "data_t";

  private DataSchema() {}

  /**
   * 현재 테넌트의 데이터 스키마 식별자를 돌려준다(인용 없음).
   *
   * <p>테넌트 1 은 {@link #LEGACY_SCHEMA_BY_TENANT} 에 있는 값(={@code data})을, 그 외 테넌트는
   * {@link #TENANT_SCHEMA_PREFIX} + tenantId 로 파생된 값을 돌려준다. 특수 분기가 아니라 조회다.
   *
   * @throws MissingTenantScopeException 테넌트 컨텍스트가 없을 때. 조용히 기본 스키마로 떨어지지
   *     않는다 — 그 폴백이 곧 크로스 테넌트 접근이 된다.
   */
  public static String current() {
    long tenantId = TenantContext.require("data 스키마 식별자 해석");
    return LEGACY_SCHEMA_BY_TENANT.getOrDefault(tenantId, TENANT_SCHEMA_PREFIX + tenantId);
  }

  /**
   * 식별자를 현재 테넌트의 데이터 스키마로 한정한다 — {@code data."tbl"} 형태.
   *
   * <p><b>테이블명 전용이 아니다.</b> 호출부는 인덱스명·시퀀스명도 이 메서드로 한정한다(스키마에
   * 속하는 식별자는 모두 같은 조립 규칙이다). 그래서 파라미터 이름도 {@code identifier} 다 —
   * {@code tableName} 이라고 적어 두면 인덱스·시퀀스를 한정할 때 "이 메서드는 내 자리가 아니다"
   * 라고 오해해 스키마명을 손으로 이어 붙이는 우회가 생긴다(그 우회가 P3-b 에서 개별 결함이 된다).
   *
   * <p>식별자는 <b>항상</b> 큰따옴표로 감싼다. 동적 테이블명은 사용자가 만든 데이터셋에서 오므로
   * PostgreSQL 예약어와 겹칠 수 있고, 인용하지 않으면 문법 오류가 된다.
   *
   * <p>이름에 든 {@code "} 는 {@code ""} 로 이중화한다. 이중화하지 않으면 식별자가 조기에 닫히고
   * 뒤따르는 문자가 SQL 문법으로 해석돼, 이름 검증을 통과한 경로에서도 인젝션이 성립한다.
   * (호출부는 대개 {@code [a-z][a-z0-9_]*} 로 이미 걸러 두지만, 조립 지점이 스스로 안전해야
   * 검증을 잊은 새 호출부가 생겨도 무해하다.)
   *
   * @param identifier 스키마로 한정할 식별자(테이블·인덱스·시퀀스명). 인용은 이 메서드가 한다.
   */
  public static String qualify(String identifier) {
    return current() + ".\"" + identifier.replace("\"", "\"\"") + "\"";
  }
}
