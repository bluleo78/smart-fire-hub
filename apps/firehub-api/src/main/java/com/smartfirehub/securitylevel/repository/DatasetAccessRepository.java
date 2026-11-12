package com.smartfirehub.securitylevel.repository;

import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 데이터셋 접근 판정에 필요한 사실(dataset·security_level·dataset_access_grant)과 사용자 자격 원자료(user_role·role·user)를
 * 읽는 리포지토리. 판정 자체는 하지 않는다.
 *
 * <p>구현은 {@code JooqDatasetAccessRepository}(jOOQ/PostgreSQL, WD-485)·{@code
 * MyBatisDatasetAccessRepository}(MyBatis — Tibero 플랫폼 DB, WD-711) 이다.
 */
public interface DatasetAccessRepository {

  /**
   * 가시성을 바인드 값 인라인 SQL 문자열 술어로 렌더한다 — <b>문자열 SQL 을 조립하는 리포지토리 구현</b>(시맨틱·키워드 검색의 Jooq 구현이 자기 별칭으로
   * 부른다) 전용이다 — 스키마 조회는 WD-711(스펙 D3)부터 {@link DatasetVisibility} 값을 받는 {@code
   * DatasetRepository#findSchemaMetas} 로 옮겨 이 문자열을 쓰지 않는다. 그 소비자(벡터·트라이그램)는 Tibero 에서 꺼져 있어 MyBatis
   * 구현은 꺼진 기능 예외(VECTOR_SEARCH)를 던진다. 서비스가 이 문자열을 만들어 다른 플랫폼 리포지토리 인터페이스로 넘기지 않는다(WD-485 T10 —
   * 인터페이스는 {@link DatasetVisibility} 를 받는다). 값이 모두 long/boolean 이라 인라인 렌더링이 안전하다. 술어는 {@code
   * JooqDatasetVisibility#condition} 그대로라 jOOQ 목록 경로와 같은 규칙이다.
   *
   * <p>쿼리를 실행하지 않는 렌더 전용이다. 클래스 단위 readOnly 트랜잭션을 상속하면 트랜잭션 없는 호출부에서 불필요한 트랜잭션·GUC 주입이 새로 생기므로
   * {@code SUPPORTS} 로 둔다(있으면 합류, 없으면 만들지 않음).
   *
   * @param datasetAlias 호출부 SQL 에서 dataset 테이블의 별칭(예: "d")
   */
  String visibleSql(DatasetVisibility v, String datasetAlias);

  /** 데이터셋 1개에 대한 판정 사실. */
  record AccessFacts(long datasetId, String tableName, LevelPolicy level, boolean onAllowlist) {}

  /** 자격 계산 원자료. maxRank 가 null 이면 역할이 없다. */
  record ClearanceRow(Integer maxRank, Set<Long> roleIds, boolean tenantAdmin) {}

  Map<Long, AccessFacts> findFactsByDatasetIds(Collection<Long> ids, Clearance c);

  /** SQL 경로용 — data 스키마 테이블 이름(소문자 폴딩 결과)으로 데이터셋을 찾는다. */
  Map<String, AccessFacts> findFactsByTableNames(Collection<String> tableNames, Clearance c);

  /**
   * 주어진 역할들의 허용 항목 중, 허용 목록 필요 등급 데이터셋이면서 호출자 {@code c} 가 열람 시점에 <b>볼 수 없는</b> 데이터셋을 가진 역할
   * id(WD-140 역할 부여 범위 검사용).
   *
   * <p>"볼 수 있는가" 는 열람 판정({@link com.smartfirehub.securitylevel.access.DatasetAccessPolicy#decide})의
   * VIEW 와 같은 식이다 — 등급 rank ≤ 호출자 rank 이고, (허용 목록에 있음({@code
   * JooqDatasetAccessRepository#onAllowlistCondition}) 또는 admin_bypass 등급 ∧ 시스템 ADMIN). 이것의 부정(rank
   * 초과 또는 허용 목록 미충족)이면 그 역할을 돌려준다. 허용 목록 필요가 아닌 등급의 허용 항목은 열람 때 아예 참조되지 않으므로 제외한다 — 그런 데이터셋의 열람은 허용
   * 항목이 아니라 등급만으로 정해지고, 등급은 가드가 역할 등급 상한으로 따로 본다.
   *
   * <p>한 번의 조회로 끝난다(데이터셋마다 묻지 않는다). dataset.security_level_id 는 NOT NULL + FK 라 내부 조인이 행을 흘리지 않는다.
   */
  Set<Long> findRoleIdsWithAllowlistNotViewable(Collection<Long> roleIds, Clearance c);

  /**
   * 자격 원자료. ACTIVE 멤버십 + 활성 계정의 역할만 센다(권한 조회 {@code findPermissionCodesByUserId} 와 같은 조인 — 정지 멤버는
   * 자격 0). membership 은 전역 테이블이라 tenant_id 를 명시한다.
   */
  ClearanceRow findClearanceRow(long userId, long tenantId);

  /**
   * 여러 사용자의 유효 열람 rank(보유 역할 등급의 최댓값)를 한 번에 읽는다(WD-292 — 허용 목록 후보·항목에 대상 등급을 싣기 위해).
   *
   * <p>왜 따로 계산하지 않고 이 메서드인가: {@link #findClearanceRow} 와 같은 조인({@code #clearanceRoleRows})을 써야 화면에
   * 보이는 등급이 실제 판정({@code ClearanceResolver.resolve})과 어긋나지 않는다. 사용자마다 resolve 를 부르면 권한 조회까지 N 번 돈다.
   *
   * @return userId → 최대 rank. 역할이 없는(정지·비활성 포함) 사용자는 키가 없다 — 어떤 등급도 못 보는 자격(NO_RANK)
   */
  Map<Long, Integer> findMaxRanksByUser(Collection<Long> userIds, long tenantId);
}
