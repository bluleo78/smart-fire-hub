package com.smartfirehub.securitylevel.repository.jooq;

import static com.smartfirehub.jooq.Tables.DATASET;
import static com.smartfirehub.jooq.Tables.DATASET_ACCESS_GRANT;
import static com.smartfirehub.jooq.Tables.ROLE;
import static com.smartfirehub.jooq.Tables.SECURITY_LEVEL;
import static com.smartfirehub.jooq.Tables.USER;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectCount;
import static org.jooq.impl.DSL.selectOne;

import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.securitylevel.repository.DatasetAccessGrantRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.stereotype.Repository;

/**
 * DatasetAccessGrantRepository 의 jOOQ(PostgreSQL) 구현 — WD-485.
 *
 * <p>데이터셋 허용 목록(dataset_access_grant) CRUD. 테넌트 범위는 RLS — 클래스 레벨 트랜잭션으로 GUC 를 보장한다.
 */
@Repository
@ConditionalOnPostgresPlatformDb
@RequiredArgsConstructor
@org.springframework.transaction.annotation.Transactional
public class JooqDatasetAccessGrantRepository implements DatasetAccessGrantRepository {

  private final DSLContext dsl;

  @Override
  public Map<Long, Set<GrantSubject>> findSubjects(Collection<Long> datasetIds) {
    if (datasetIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, Set<GrantSubject>> out = new HashMap<>();
    dsl.select(
            DATASET_ACCESS_GRANT.DATASET_ID,
            DATASET_ACCESS_GRANT.USER_ID,
            DATASET_ACCESS_GRANT.ROLE_ID)
        .from(DATASET_ACCESS_GRANT)
        .where(DATASET_ACCESS_GRANT.DATASET_ID.in(datasetIds))
        .forEach(
            r ->
                out.computeIfAbsent(r.value1(), k -> new HashSet<>())
                    .add(new GrantSubject(r.value2(), r.value3())));
    return out;
  }

  @Override
  public Optional<Long> insertSubject(long datasetId, GrantSubject s, Long grantedBy) {
    return insertIfAbsent(datasetId, s.userId(), s.roleId(), grantedBy);
  }

  @Override
  public int deleteSubject(long datasetId, GrantSubject s) {
    var g = DATASET_ACCESS_GRANT;
    var subject = s.userId() != null ? g.USER_ID.eq(s.userId()) : g.ROLE_ID.eq(s.roleId());
    return dsl.deleteFrom(g).where(g.DATASET_ID.eq(datasetId)).and(subject).execute();
  }

  @Override
  public List<GrantRow> findByDataset(long datasetId) {
    return findRows(DATASET_ACCESS_GRANT.DATASET_ID.eq(datasetId));
  }

  @Override
  public Optional<GrantRow> findByDatasetAndSubject(long datasetId, Long userId, Long roleId) {
    var g = DATASET_ACCESS_GRANT;
    var subject = userId != null ? g.USER_ID.eq(userId) : g.ROLE_ID.eq(roleId);
    return findRows(g.DATASET_ID.eq(datasetId).and(subject)).stream().findFirst();
  }

  @Override
  public Optional<GrantRow> findRowById(long grantId) {
    return findRows(DATASET_ACCESS_GRANT.ID.eq(grantId)).stream().findFirst();
  }

  /** 화면용 행 조회 공통부 — 대상 이름과 추가한 사람 이름을 조인한다. */
  private List<GrantRow> findRows(Condition where) {
    var g = DATASET_ACCESS_GRANT;
    var subjectUser = USER.as("subject_user");
    var granter = USER.as("granter");
    return dsl.select(
            g.ID,
            g.DATASET_ID,
            g.USER_ID,
            g.ROLE_ID,
            subjectUser.NAME,
            ROLE.NAME,
            g.GRANTED_BY,
            granter.NAME,
            g.GRANTED_AT)
        .from(g)
        .leftJoin(subjectUser)
        .on(subjectUser.ID.eq(g.USER_ID))
        .leftJoin(ROLE)
        .on(ROLE.ID.eq(g.ROLE_ID))
        .leftJoin(granter)
        .on(granter.ID.eq(g.GRANTED_BY))
        .where(where)
        .orderBy(g.GRANTED_AT.asc(), g.ID.asc())
        .fetch(
            r ->
                new GrantRow(
                    r.get(g.ID),
                    r.get(g.DATASET_ID),
                    r.get(g.USER_ID),
                    r.get(g.ROLE_ID),
                    r.get(g.USER_ID) != null ? r.get(subjectUser.NAME) : r.get(ROLE.NAME),
                    r.get(g.GRANTED_BY),
                    r.get(granter.NAME),
                    r.get(g.GRANTED_AT)));
  }

  @Override
  public Optional<GrantRow> findById(long grantId) {
    return dsl.selectFrom(DATASET_ACCESS_GRANT)
        .where(DATASET_ACCESS_GRANT.ID.eq(grantId))
        .fetchOptional(
            r ->
                new GrantRow(
                    r.getId(),
                    r.getDatasetId(),
                    r.getUserId(),
                    r.getRoleId(),
                    null,
                    r.getGrantedBy(),
                    null,
                    r.getGrantedAt()));
  }

  @Override
  public int countByDataset(long datasetId) {
    return dsl.fetchCount(DATASET_ACCESS_GRANT, DATASET_ACCESS_GRANT.DATASET_ID.eq(datasetId));
  }

  @Override
  public boolean existsUser(long datasetId, long userId) {
    return dsl.fetchExists(
        DATASET_ACCESS_GRANT,
        DATASET_ACCESS_GRANT.DATASET_ID.eq(datasetId).and(DATASET_ACCESS_GRANT.USER_ID.eq(userId)));
  }

  @Override
  public long insertUser(long datasetId, long userId, Long grantedBy) {
    return insert(datasetId, DATASET_ACCESS_GRANT.USER_ID, userId, grantedBy);
  }

  @Override
  public long insertRole(long datasetId, long roleId, Long grantedBy) {
    return insert(datasetId, DATASET_ACCESS_GRANT.ROLE_ID, roleId, grantedBy);
  }

  /** 항목 1개 추가 공통부 — 대상 컬럼(user_id 또는 role_id) 하나만 채운다. */
  private long insert(long datasetId, Field<Long> subjectColumn, long subjectId, Long grantedBy) {
    return dsl.insertInto(DATASET_ACCESS_GRANT)
        .set(DATASET_ACCESS_GRANT.DATASET_ID, datasetId)
        .set(subjectColumn, subjectId)
        .set(DATASET_ACCESS_GRANT.GRANTED_BY, grantedBy)
        .returning(DATASET_ACCESS_GRANT.ID)
        .fetchSingle(DATASET_ACCESS_GRANT.ID);
  }

  @Override
  public Optional<Long> insertIfAbsent(long datasetId, Long userId, Long roleId, Long grantedBy) {
    return dsl.insertInto(DATASET_ACCESS_GRANT)
        .set(DATASET_ACCESS_GRANT.DATASET_ID, datasetId)
        .set(DATASET_ACCESS_GRANT.USER_ID, userId)
        .set(DATASET_ACCESS_GRANT.ROLE_ID, roleId)
        .set(DATASET_ACCESS_GRANT.GRANTED_BY, grantedBy)
        .onConflictDoNothing()
        .returning(DATASET_ACCESS_GRANT.ID)
        .fetchOptional(DATASET_ACCESS_GRANT.ID);
  }

  @Override
  public void delete(long grantId) {
    dsl.deleteFrom(DATASET_ACCESS_GRANT).where(DATASET_ACCESS_GRANT.ID.eq(grantId)).execute();
  }

  @Override
  public int copy(long fromDatasetId, long toDatasetId, Long grantedBy) {
    return dsl.insertInto(
            DATASET_ACCESS_GRANT,
            DATASET_ACCESS_GRANT.DATASET_ID,
            DATASET_ACCESS_GRANT.USER_ID,
            DATASET_ACCESS_GRANT.ROLE_ID,
            DATASET_ACCESS_GRANT.GRANTED_BY)
        .select(
            dsl.select(
                    org.jooq.impl.DSL.val(toDatasetId),
                    DATASET_ACCESS_GRANT.USER_ID,
                    DATASET_ACCESS_GRANT.ROLE_ID,
                    org.jooq.impl.DSL.val(grantedBy))
                .from(DATASET_ACCESS_GRANT)
                .where(DATASET_ACCESS_GRANT.DATASET_ID.eq(fromDatasetId)))
        .onConflictDoNothing()
        .execute();
  }

  @Override
  public List<Long> lockDatasetsGrantedToRole(long roleId) {
    return dsl.select(DATASET.ID)
        .from(DATASET)
        .where(
            DATASET.ID.in(
                dsl.select(DATASET_ACCESS_GRANT.DATASET_ID)
                    .from(DATASET_ACCESS_GRANT)
                    .where(DATASET_ACCESS_GRANT.ROLE_ID.eq(roleId))))
        .orderBy(DATASET.ID.asc())
        .forUpdate()
        .fetch(DATASET.ID);
  }

  @Override
  public List<Long> datasetsWhereRoleIsSoleGrantOnAllowlistLevel(long roleId) {
    var g = DATASET_ACCESS_GRANT;
    var other = DATASET_ACCESS_GRANT.as("other_g");
    return dsl.select(g.DATASET_ID)
        .from(g)
        .join(DATASET)
        .on(DATASET.ID.eq(g.DATASET_ID))
        .join(SECURITY_LEVEL)
        .on(SECURITY_LEVEL.ID.eq(DATASET.SECURITY_LEVEL_ID))
        .where(g.ROLE_ID.eq(roleId))
        .and(SECURITY_LEVEL.ALLOWLIST_REQUIRED.isTrue())
        .and(selectCount().from(other).where(other.DATASET_ID.eq(g.DATASET_ID)).asField().eq(1))
        .fetch(g.DATASET_ID);
  }

  @Override
  public List<Long> findDatasetIdsWithoutAllowlist(long levelId) {
    return dsl.select(DATASET.ID)
        .from(DATASET)
        .where(DATASET.SECURITY_LEVEL_ID.eq(levelId))
        .and(
            notExists(
                selectOne()
                    .from(DATASET_ACCESS_GRANT)
                    .where(DATASET_ACCESS_GRANT.DATASET_ID.eq(DATASET.ID))))
        .fetch(DATASET.ID);
  }
}
