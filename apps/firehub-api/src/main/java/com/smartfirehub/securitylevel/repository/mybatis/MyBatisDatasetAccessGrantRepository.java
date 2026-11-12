package com.smartfirehub.securitylevel.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformRetries;
import com.smartfirehub.securitylevel.repository.DatasetAccessGrantRepository;
import com.smartfirehub.securitylevel.repository.mybatis.DatasetAccessGrantRows.GrantInsert;
import com.smartfirehub.securitylevel.repository.mybatis.DatasetAccessGrantRows.SubjectRow;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * DatasetAccessGrantRepository 의 MyBatis 구현(WD-711) — {@code JooqDatasetAccessGrantRepository} 와 같은
 * 의미.
 *
 * <p>트랜잭션 속성은 jOOQ 구현 그대로 클래스 단위 plain {@code @Transactional} 이다 — 트랜잭션이 없으면 테넌트 컨텍스트가 심기지 않아 정책이
 * 0행이 된다.
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisDatasetAccessGrantRepository implements DatasetAccessGrantRepository {

  private final DatasetAccessGrantMapper mapper;

  public MyBatisDatasetAccessGrantRepository(DatasetAccessGrantMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Map<Long, Set<GrantSubject>> findSubjects(Collection<Long> datasetIds) {
    if (datasetIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, Set<GrantSubject>> out = new HashMap<>();
    for (SubjectRow r : mapper.selectSubjects(InChunks.of(datasetIds))) {
      out.computeIfAbsent(r.datasetId(), k -> new HashSet<>())
          .add(new GrantSubject(r.userId(), r.roleId()));
    }
    return out;
  }

  @Override
  public Optional<Long> insertSubject(long datasetId, GrantSubject s, Long grantedBy) {
    return insertIfAbsent(datasetId, s.userId(), s.roleId(), grantedBy);
  }

  @Override
  public int deleteSubject(long datasetId, GrantSubject s) {
    return mapper.deleteSubject(datasetId, s.userId(), s.roleId());
  }

  @Override
  public List<GrantRow> findByDataset(long datasetId) {
    return mapper.selectRowsByDataset(datasetId);
  }

  @Override
  public Optional<GrantRow> findByDatasetAndSubject(long datasetId, Long userId, Long roleId) {
    return mapper.selectRowsByDatasetAndSubject(datasetId, userId, roleId).stream().findFirst();
  }

  @Override
  public Optional<GrantRow> findRowById(long grantId) {
    return Optional.ofNullable(mapper.selectRowsById(grantId));
  }

  @Override
  public Optional<GrantRow> findById(long grantId) {
    return Optional.ofNullable(mapper.selectPlainById(grantId));
  }

  @Override
  public int countByDataset(long datasetId) {
    return mapper.countByDataset(datasetId);
  }

  @Override
  public boolean existsUser(long datasetId, long userId) {
    return mapper.countUser(datasetId, userId) > 0;
  }

  @Override
  public long insertUser(long datasetId, long userId, Long grantedBy) {
    return insert(new GrantInsert(datasetId, userId, null, grantedBy));
  }

  @Override
  public long insertRole(long datasetId, long roleId, Long grantedBy) {
    return insert(new GrantInsert(datasetId, null, roleId, grantedBy));
  }

  /** 항목 1개 추가 공통부 — 생성 키를 홀더에서 읽는다. 중복이면 유일 제약 위반 예외가 그대로 올라간다(jOOQ 판과 같다). */
  private long insert(GrantInsert row) {
    mapper.insert(row);
    return row.getId();
  }

  /**
   * 같은 (데이터셋, 대상) 항목이 없을 때만 넣고 새 id 를 돌려준다. PG 는 ON CONFLICT DO NOTHING, Tibero 는 MERGE 다.
   *
   * <p>Tibero 의 MERGE 는 같은 대상이 동시에 처음 들어오면 한쪽이 중복 키(-10007)로 실패할 수 있다(문장 단위 실패라 트랜잭션은 산다). 한 번 다시
   * 시도하면 이미 들어간 행을 만나 0 행이 되어 PG 의 DO NOTHING 과 같은 결과(빈 값)다. 이 재시도는 Tibero MERGE 전용이며 PG 에서는 이 예외가
   * 나지 않는다.
   *
   * <p>대상 검사는 재시도 전에 한다 — 검사 예외는 중복 키가 아닌 상위 무결성 예외라 헬퍼가 다시 시도하지 않는다.
   */
  @Override
  public Optional<Long> insertIfAbsent(long datasetId, Long userId, Long roleId, Long grantedBy) {
    requireExactlyOneSubject(userId, roleId);
    int inserted =
        PlatformRetries.onceOnDuplicateKey(
            () -> mapper.insertIfAbsent(datasetId, userId, roleId, grantedBy));
    if (inserted == 0) {
      return Optional.empty();
    }
    // RETURNING 이 없어 방금 넣은 행(같은 트랜잭션이라 보인다)의 id 를 대상으로 다시 읽는다 — 대상은 데이터셋 안에서 유일하다.
    return Optional.ofNullable(mapper.selectIdBySubject(datasetId, userId, roleId));
  }

  @Override
  public void delete(long grantId) {
    mapper.deleteById(grantId);
  }

  /**
   * 원본 항목을 대상 데이터셋으로 복사하고 새로 들어간 행 수를 돌려준다 — jOOQ 의 INSERT … SELECT … ON CONFLICT DO NOTHING 과 같은
   * 값. 두 DB 공통 INSERT … SELECT … NOT EXISTS 한 문장이라 항목 수만큼 왕복하지 않는다. Tibero 에서 같은 대상을 동시에 복사하면 한쪽이
   * 중복 키(-10007)로 실패할 수 있어(문장 단위 실패) 한 번 다시 돈다 — 두 번째에는 NOT EXISTS 가 이미 들어간 행을 건너뛴다.
   */
  @Override
  public int copy(long fromDatasetId, long toDatasetId, Long grantedBy) {
    return PlatformRetries.onceOnDuplicateKey(
        () -> mapper.copy(fromDatasetId, toDatasetId, grantedBy));
  }

  /**
   * 대상은 사용자·역할 중 정확히 하나여야 한다(테이블 CHECK ck_dataset_access_grant_subject). PG 는 CHECK 가 무결성 예외를 내지만,
   * Tibero MERGE 는 사용자 분기를 골라 역할을 조용히 버리거나(둘 다) 역할 NULL 로 넣으려 하므로(둘 다 없음) 문장 전에 같은 예외 계열로 막는다.
   */
  private static void requireExactlyOneSubject(Long userId, Long roleId) {
    if ((userId == null) == (roleId == null)) {
      throw new DataIntegrityViolationException(
          "허용 목록 대상은 사용자·역할 중 정확히 하나여야 합니다 (userId=" + userId + ", roleId=" + roleId + ")");
    }
  }

  @Override
  public List<Long> lockDatasetsGrantedToRole(long roleId) {
    return mapper.lockDatasetsGrantedToRole(roleId);
  }

  @Override
  public List<Long> datasetsWhereRoleIsSoleGrantOnAllowlistLevel(long roleId) {
    return mapper.selectDatasetsWhereRoleIsSoleGrantOnAllowlistLevel(roleId);
  }

  @Override
  public List<Long> findDatasetIdsWithoutAllowlist(long levelId) {
    return mapper.selectDatasetIdsWithoutAllowlist(levelId);
  }
}
