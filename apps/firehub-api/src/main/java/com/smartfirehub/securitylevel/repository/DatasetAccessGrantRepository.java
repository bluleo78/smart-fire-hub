package com.smartfirehub.securitylevel.repository;

import static com.smartfirehub.jooq.Tables.DATASET;
import static com.smartfirehub.jooq.Tables.DATASET_ACCESS_GRANT;
import static com.smartfirehub.jooq.Tables.ROLE;
import static com.smartfirehub.jooq.Tables.SECURITY_LEVEL;
import static com.smartfirehub.jooq.Tables.USER;
import static org.jooq.impl.DSL.selectCount;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.stereotype.Repository;

/** 데이터셋 허용 목록(dataset_access_grant) CRUD. 테넌트 범위는 RLS — 클래스 레벨 트랜잭션으로 GUC 를 보장한다. */
@Repository
@RequiredArgsConstructor
@org.springframework.transaction.annotation.Transactional
public class DatasetAccessGrantRepository {

  private final DSLContext dsl;

  /** 화면용 행 — 대상 이름과 추가한 사람 이름까지 조인해 돌려준다. */
  public record GrantRow(
      long id,
      long datasetId,
      Long userId,
      Long roleId,
      String subjectName,
      Long grantedBy,
      String grantedByName,
      LocalDateTime grantedAt) {

    /** 대상 종류 — 사용자 항목이면 USER, 아니면 ROLE(화면·감사 메타 표기). */
    public String type() {
      return userId != null ? "USER" : "ROLE";
    }

    /** 대상 id — 사용자 항목이면 user_id, 아니면 role_id. */
    public Long subjectId() {
      return userId != null ? userId : roleId;
    }
  }

  public List<GrantRow> findByDataset(long datasetId) {
    return findRows(DATASET_ACCESS_GRANT.DATASET_ID.eq(datasetId));
  }

  /** 이 데이터셋에서 지정 대상(사용자 또는 역할 — 둘 중 하나만 non-null)의 항목(이름 조인 포함). 같은 대상의 항목이 여럿이면 목록 순서상 첫 항목. */
  public Optional<GrantRow> findByDatasetAndSubject(long datasetId, Long userId, Long roleId) {
    var g = DATASET_ACCESS_GRANT;
    var subject = userId != null ? g.USER_ID.eq(userId) : g.ROLE_ID.eq(roleId);
    return findRows(g.DATASET_ID.eq(datasetId).and(subject)).stream().findFirst();
  }

  /** 항목 1개(이름 조인 포함 — 화면 응답용). {@link #findById} 는 이름 없이 원 행만 읽는다. */
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

  public int countByDataset(long datasetId) {
    return dsl.fetchCount(DATASET_ACCESS_GRANT, DATASET_ACCESS_GRANT.DATASET_ID.eq(datasetId));
  }

  public boolean existsUser(long datasetId, long userId) {
    return dsl.fetchExists(
        DATASET_ACCESS_GRANT,
        DATASET_ACCESS_GRANT.DATASET_ID.eq(datasetId).and(DATASET_ACCESS_GRANT.USER_ID.eq(userId)));
  }

  public long insertUser(long datasetId, long userId, Long grantedBy) {
    return insert(datasetId, DATASET_ACCESS_GRANT.USER_ID, userId, grantedBy);
  }

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

  /**
   * 허용 항목을 "없을 때만" 추가한다(후속 F5 — 같은 대상 동시 추가 경합). 같은 (데이터셋, 대상) 항목이 이미 있으면(동시 요청이 먼저 커밋한 경우 포함) 아무것도
   * 넣지 않고 빈 값을 돌려준다.
   *
   * <p>왜 {@code ON CONFLICT DO NOTHING} 인가: 사전 조회 → INSERT 사이에 같은 대상이 동시에 들어오면 INSERT 가 유니크 위반으로
   * 끝나는데, 그 순간 PostgreSQL 트랜잭션이 중단돼 예외를 잡고 다시 조회할 수도 없다(코드 없는 일반 409 로 끝난다). 충돌을 문장 안에서 흡수하면 트랜잭션이
   * 살아 있어 승자의 행을 다시 읽어 줄 수 있다. 시더·러너 시드가 쓰는 {@link #insertUser}/{@link #insertRole} 은 반환 계약(long)이
   * 달라 그대로 둔다.
   *
   * @param userId 사용자 대상(역할 대상이면 null)
   * @param roleId 역할 대상(사용자 대상이면 null)
   * @return 새로 넣은 항목 id — 이미 있었으면 빈 값
   */
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

  public void delete(long grantId) {
    dsl.deleteFrom(DATASET_ACCESS_GRANT).where(DATASET_ACCESS_GRANT.ID.eq(grantId)).execute();
  }

  /** clone 용 — 원본 항목을 그대로 복사한다(대상·주체 동일, 추가한 사람만 복제자). */
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

  /**
   * 이 역할이 허용 항목으로 걸린 데이터셋 행을 id 오름차순으로 {@code FOR UPDATE} 잠근다(후속 F4 — 역할 삭제 경합).
   *
   * <p>왜: 역할 삭제의 "유일한 허용 항목" 검사가 잠금 없이 돌면, 같은 데이터셋의 다른 항목 제거({@code
   * DatasetSecurityService.removeGrant})와 동시에 진행될 때 서로의 미커밋 삭제를 못 보고 둘 다 통과해 데이터셋이 고아가 된다. 항목 제거는
   * 이미 같은 데이터셋 행을 {@code FOR UPDATE} 로 잠그므로, 역할 삭제도 같은 행을 먼저 잠가 둘을 직렬화한다. 항목 제거는 행 1개만 잠그고 여기는 id
   * 오름차순으로 잠그므로 잠금 순서가 엇갈려 교착할 일이 없다(역할 삭제끼리도 같은 순서). 등급과 무관하게 잠근다 — 등급 변경({@code changeLevel})도 같은
   * 행을 UPDATE 하므로, 허용 목록 등급으로 올리는 중인 데이터셋도 새 등급으로 검사된다. 조인 없이 dataset 행만 잠근다.
   *
   * <p>검사({@link #datasetsWhereRoleIsSoleGrantOnAllowlistLevel})는 반드시 이 잠금이 돌아온 <b>뒤의 별도 문장</b>으로
   * 한다 — READ COMMITTED 는 문장 시작 시점 스냅샷을 보므로, 잠금 대기 뒤 새 문장이어야 상대의 커밋된 제거가 보인다.
   *
   * @return 잠근 데이터셋 id(오름차순)
   */
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

  /** 이 역할이 "유일한 허용 항목"인 allowlist_required 데이터셋 — 역할 삭제가 고아 데이터셋을 만들지 않게 하는 검사용(판단 사항 14). */
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
}
