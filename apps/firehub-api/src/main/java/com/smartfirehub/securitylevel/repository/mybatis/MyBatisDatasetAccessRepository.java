package com.smartfirehub.securitylevel.repository.mybatis;

import com.smartfirehub.global.platformdb.PlatformDbFeature;
import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.PlatformFeatureOff;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.repository.DatasetAccessRepository;
import com.smartfirehub.securitylevel.repository.SystemAdminRole;
import com.smartfirehub.securitylevel.repository.mybatis.DatasetAccessRows.ClearanceRoleRow;
import com.smartfirehub.securitylevel.repository.mybatis.DatasetAccessRows.FactRow;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * DatasetAccessRepository 의 MyBatis 구현(WD-711) — {@code JooqDatasetAccessRepository} 와 같은 의미.
 *
 * <p>트랜잭션 속성은 jOOQ 구현 그대로다 — 클래스 단위 readOnly(인터셉터·러너·폴러처럼 트랜잭션 없이 부르는 경로가 있어 트랜잭션이 있어야 테넌트 컨텍스트가
 * 심긴다, 없으면 정책이 0행), 렌더 전용 {@code visibleSql} 은 SUPPORTS.
 *
 * <p>{@code visibleSql} 은 PG 렌더 문자열을 돌려주는 메서드라 MyBatis 판은 구현하지 않는다(스펙 D3). 남은 소비자는 jOOQ 벡터·트라이그램
 * 경로(JooqDatasetSearch·JooqDocumentChunk)뿐이고 그 경로는 Tibero 에서 꺼져 있다 — 스키마 조회는 {@link
 * DatasetVisibility} 값을 받는 {@code DatasetRepository.findSchemaMetas} 로 옮겼다.
 */
@PlatformMyBatisRepository
@Transactional(readOnly = true)
public class MyBatisDatasetAccessRepository implements DatasetAccessRepository {

  private final DatasetAccessMapper mapper;

  public MyBatisDatasetAccessRepository(DatasetAccessMapper mapper) {
    this.mapper = mapper;
  }

  /** 문자열 술어 렌더 — 소비자가 꺼진 벡터 검색 경로뿐이라 지원하지 않는다(스펙 D3, ADR 꺼진 기능 규칙). */
  @Override
  @Transactional(propagation = Propagation.SUPPORTS)
  public String visibleSql(DatasetVisibility v, String datasetAlias) {
    throw PlatformFeatureOff.unsupported(PlatformDbFeature.VECTOR_SEARCH);
  }

  @Override
  public Map<Long, AccessFacts> findFactsByDatasetIds(Collection<Long> ids, Clearance c) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    return toFacts(mapper.selectFactsByIds(InChunks.of(ids), c.userId(), roleIds(c)));
  }

  @Override
  public Map<String, AccessFacts> findFactsByTableNames(
      Collection<String> tableNames, Clearance c) {
    if (tableNames.isEmpty()) {
      return Map.of();
    }
    Map<Long, AccessFacts> byId =
        toFacts(mapper.selectFactsByTableNames(InChunks.of(tableNames), c.userId(), roleIds(c)));
    Map<String, AccessFacts> byName = new HashMap<>();
    byId.values().forEach(f -> byName.put(f.tableName(), f));
    return byName;
  }

  @Override
  public Set<Long> findRoleIdsWithAllowlistNotViewable(Collection<Long> roleIds, Clearance c) {
    if (roleIds.isEmpty()) {
      return Set.of();
    }
    return new HashSet<>(
        mapper.selectRoleIdsWithAllowlistNotViewable(
            InChunks.of(roleIds), c.rank(), c.userId(), roleIds(c), c.tenantAdmin()));
  }

  @Override
  public ClearanceRow findClearanceRow(long userId, long tenantId) {
    Integer max = null;
    Set<Long> roleIds = new HashSet<>();
    boolean admin = false;
    for (ClearanceRoleRow r : mapper.selectClearanceRows(userId, tenantId)) {
      roleIds.add(r.roleId());
      int rank = r.rank();
      max = max == null ? rank : Math.max(max, rank);
      admin |= SystemAdminRole.matches(r.roleName(), r.isSystem());
    }
    return new ClearanceRow(max, roleIds, admin);
  }

  @Override
  public Map<Long, Integer> findMaxRanksByUser(Collection<Long> userIds, long tenantId) {
    if (userIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, Integer> out = new HashMap<>();
    for (ClearanceRoleRow r : mapper.selectClearanceRowsByUsers(InChunks.of(userIds), tenantId)) {
      out.merge(r.userId(), r.rank(), Math::max);
    }
    return out;
  }

  /** 자격의 보유 역할 — 매퍼 foreach 용 목록(비면 역할 IN 절을 렌더하지 않는다). */
  private static List<Long> roleIds(Clearance c) {
    return List.copyOf(c.roleIds());
  }

  /** 평평한 사실 행을 데이터셋 id → AccessFacts 로 묶는다. 등급 열은 jOOQ toPolicy 와 같은 순서로 LevelPolicy 를 만든다. */
  private static Map<Long, AccessFacts> toFacts(List<FactRow> rows) {
    Map<Long, AccessFacts> out = new HashMap<>();
    for (FactRow r : rows) {
      LevelPolicy level =
          new LevelPolicy(
              r.levelId(),
              r.levelName(),
              r.rank(),
              Boolean.TRUE.equals(r.isDefault()),
              Boolean.TRUE.equals(r.allowlistRequired()),
              Boolean.TRUE.equals(r.adminBypass()),
              r.exportPolicy(),
              r.aiPolicy(),
              r.sharePolicy(),
              Boolean.TRUE.equals(r.auditAccess()));
      out.put(
          r.datasetId(),
          new AccessFacts(
              r.datasetId(), r.tableName(), level, r.onList() != null && r.onList() == 1));
    }
    return out;
  }
}
