package com.smartfirehub.securitylevel.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.securitylevel.repository.DatasetAccessGrantRepository.GrantRow;
import com.smartfirehub.securitylevel.repository.DatasetAccessGrantRepository.GrantSubject;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * DatasetAccessGrantRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-711).
 *
 * <p>dataset_access_grant 는 테넌트 정책(RLS) 아래 있어 호출은 늘 테넌트 컨텍스트({@code inA}/{@code inB})로 감싼다. 같은
 * (데이터셋, 사용자) 또는 (데이터셋, 역할) 항목은 한 번만 들어간다 — PG 는 유일 제약, Tibero 는 CASE 식 유일 색인이 막는다(Review Focus 4).
 */
public abstract class DatasetAccessGrantRepositoryContractTest extends TenantPairContractBase {

  protected abstract DatasetAccessGrantRepository repository();

  /** 데이터셋을 만든 사람 겸 항목을 추가한 사람(granted_by) — 시험마다 하나. */
  private long creator;

  @BeforeEach
  void createCreator() {
    creator = fx.createUser(prefix + "-creator", null);
    fx.setUserName(creator, prefix + "-creator-name");
  }

  private long dataset(String suffix) {
    return fx.createDataset(tenantA, creator, "t_" + prefix + "_" + suffix, null);
  }

  private long user(String suffix) {
    long u = fx.createUser(prefix + "-" + suffix, null);
    fx.setUserName(u, prefix + "-" + suffix + "-name");
    return u;
  }

  private long role(String suffix) {
    return fx.createRole(tenantA, prefix + "-" + suffix, null);
  }

  /** 테넌트 A 의 등급 하나(rank 3 이상 — 기본 등급이 rank 2 를 쓴다). */
  private long level(String suffix, int rank, boolean allowlist) {
    long id = fx.createSecurityLevel(tenantA, rank, prefix + "-" + suffix);
    fx.setSecurityLevelFlags(id, allowlist, false);
    return id;
  }

  // ------------------------------------------------------------------ findSubjects

  @Test
  @DisplayName("findSubjects — 데이터셋별 사용자·역할 대상을 모으고 항목 없는 데이터셋·다른 테넌트 항목은 빠진다")
  void findSubjects() {
    long d1 = dataset("d1");
    long d2 = dataset("d2");
    long d3 = dataset("none");
    long dOther = fx.createDataset(tenantB, creator, "t_" + prefix + "_other", null);
    long u = user("u");
    long r = role("r");
    fx.grantDatasetAccess(tenantA, d1, null, u);
    fx.grantDatasetAccess(tenantA, d1, r, null);
    fx.grantDatasetAccess(tenantA, d2, r, null);
    fx.grantDatasetAccess(tenantB, dOther, null, creator);

    Map<Long, Set<GrantSubject>> out =
        inA(() -> repository().findSubjects(List.of(d1, d2, d3, dOther)));

    assertThat(out).containsOnlyKeys(d1, d2);
    assertThat(out.get(d1)).containsExactlyInAnyOrder(GrantSubject.user(u), GrantSubject.role(r));
    assertThat(out.get(d2)).containsExactly(GrantSubject.role(r));
    assertThat(inA(() -> repository().findSubjects(List.of()))).isEmpty();
  }

  @Test
  @DisplayName("findSubjects — 데이터셋 id 1001 개(Tibero IN 1000 개 상한)도 조각을 넘나들며 돌려준다")
  void findSubjects_1001Ids() {
    long first = dataset("first");
    long late = dataset("late");
    long u = user("u");
    fx.grantDatasetAccess(tenantA, first, null, u);
    fx.grantDatasetAccess(tenantA, late, null, u);
    List<Long> ids = new ArrayList<>();
    ids.add(first);
    for (long i = 1; ids.size() < 700; i++) {
      ids.add(-i);
    }
    ids.add(late);
    for (long i = 1000; ids.size() < 1001; i++) {
      ids.add(-i);
    }

    assertThat(inA(() -> repository().findSubjects(ids))).containsOnlyKeys(first, late);
  }

  // ------------------------------------------------------------------ 삽입·삭제

  @Test
  @DisplayName(
      "insertSubject — 사용자·역할 대상을 넣고 새 id 를 돌려주며 이미 있으면 빈 값이고 deleteSubject 가 지운 행 수를 돌려준다")
  void insertSubject_and_deleteSubject() {
    long d = dataset("d");
    long u = user("u");
    long r = role("r");

    Optional<Long> userId = inA(() -> repository().insertSubject(d, GrantSubject.user(u), creator));
    Optional<Long> roleId = inA(() -> repository().insertSubject(d, GrantSubject.role(r), null));
    assertThat(userId).isPresent();
    assertThat(roleId).isPresent();
    assertThat(userId.get()).isNotEqualTo(roleId.get());
    assertThat(inA(() -> repository().insertSubject(d, GrantSubject.user(u), creator))).isEmpty();
    assertThat(inA(() -> repository().insertSubject(d, GrantSubject.role(r), null))).isEmpty();
    assertThat(inA(() -> repository().countByDataset(d))).isEqualTo(2);

    assertThat(inA(() -> repository().deleteSubject(d, GrantSubject.user(u)))).isEqualTo(1);
    assertThat(inA(() -> repository().deleteSubject(d, GrantSubject.user(u)))).isZero();
    assertThat(inA(() -> repository().deleteSubject(d, GrantSubject.role(r)))).isEqualTo(1);
    assertThat(inA(() -> repository().countByDataset(d))).isZero();
  }

  @Test
  @DisplayName("insertIfAbsent — 같은 사용자·역할 대상을 두 번 넣어도 예외 없이 한 행이고 두 번째는 빈 값(Review Focus 4)")
  void insertIfAbsent_twiceKeepsOneRow() {
    long d = dataset("d");
    long d2 = dataset("d2");
    long u = user("u");
    long r = role("r");

    Optional<Long> first = inA(() -> repository().insertIfAbsent(d, u, null, creator));
    Optional<Long> second = inA(() -> repository().insertIfAbsent(d, u, null, creator));
    Optional<Long> roleFirst = inA(() -> repository().insertIfAbsent(d, null, r, null));
    Optional<Long> roleSecond = inA(() -> repository().insertIfAbsent(d, null, r, creator));

    assertThat(first).isPresent();
    assertThat(second).isEmpty();
    assertThat(roleFirst).isPresent();
    assertThat(roleSecond).isEmpty();
    assertThat(inA(() -> repository().countByDataset(d))).isEqualTo(2);
    // 같은 사용자라도 다른 데이터셋에는 따로 들어간다.
    assertThat(inA(() -> repository().insertIfAbsent(d2, u, null, creator))).isPresent();
    GrantRow row = inA(() -> repository().findById(first.get())).orElseThrow();
    assertThat(row.datasetId()).isEqualTo(d);
    assertThat(row.userId()).isEqualTo(u);
    assertThat(row.roleId()).isNull();
    assertThat(row.grantedBy()).isEqualTo(creator);
    // 역할 항목의 추가한 사람 null 이 그대로 남는다(첫 삽입 값).
    assertThat(inA(() -> repository().findById(roleFirst.get())).orElseThrow().grantedBy())
        .isNull();
  }

  @Test
  @DisplayName(
      "insertIfAbsent·insertSubject — 사용자·역할을 둘 다 주거나 둘 다 비우면 두 DB 모두 무결성 예외이고 행이 생기지 않는다(CHECK 와 같은 의미)")
  void insertIfAbsent_rejectsBothOrNeitherSubject() {
    long d = dataset("d");
    long u = user("u");
    long r = role("r");

    assertThatThrownBy(() -> inA(() -> repository().insertIfAbsent(d, u, r, creator)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> inA(() -> repository().insertIfAbsent(d, null, null, creator)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> inA(() -> repository().insertSubject(d, new GrantSubject(u, r), creator)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(inA(() -> repository().countByDataset(d))).isZero();
  }

  @Test
  @DisplayName(
      "granted_at — insertUser·insertRole·insertIfAbsent·copy 가 넣은 시각이 DB 시계(저장 기준 벽시계) 1 분 안이다")
  void grantedAt_isDbNow() {
    long from = dataset("from");
    long to = dataset("to");
    long u = user("u");
    long r = role("r");
    long u2 = user("u2");
    long g1 = inA(() -> repository().insertUser(from, u, creator));
    long g2 = inA(() -> repository().insertRole(from, r, null));
    long g3 = inA(() -> repository().insertIfAbsent(from, u2, null, creator)).orElseThrow();
    assertThat(inA(() -> repository().copy(from, to, creator))).isEqualTo(3);

    List<Long> ids = new ArrayList<>(List.of(g1, g2, g3));
    inA(() -> repository().findByDataset(to)).forEach(row -> ids.add(row.id()));
    assertThat(ids).hasSize(6);
    LocalDateTime now = fx.dbWallClockNow();
    for (long id : ids) {
      LocalDateTime at = inA(() -> repository().findById(id)).orElseThrow().grantedAt();
      // 저장 기준 벽시계(PG 세션 TimeZone, Tibero UTC) — Tibero 가 세션 시간대 DEFAULT 에 기대면 시간대만큼 어긋난다.
      assertThat(Duration.between(at, now).abs()).isLessThan(Duration.ofMinutes(1));
    }
  }

  @Test
  @DisplayName("insertUser·insertRole — 새 id 를 돌려주고 같은 대상을 다시 넣으면 유일 제약 위반 예외")
  void insertUser_insertRole() {
    long d = dataset("d");
    long u = user("u");
    long r = role("r");

    long userGrant = inA(() -> repository().insertUser(d, u, creator));
    long roleGrant = inA(() -> repository().insertRole(d, r, null));

    assertThat(userGrant).isPositive();
    assertThat(roleGrant).isPositive().isNotEqualTo(userGrant);
    assertThat(inA(() -> repository().existsUser(d, u))).isTrue();
    assertThat(inA(() -> repository().existsUser(d, creator))).isFalse();
    assertThatThrownBy(() -> inA(() -> repository().insertUser(d, u, creator)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> inA(() -> repository().insertRole(d, r, creator)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(inA(() -> repository().countByDataset(d))).isEqualTo(2);
  }

  @Test
  @DisplayName("delete — 항목 id 로 지우고 없는 id 는 조용히 지나간다")
  void delete() {
    long d = dataset("d");
    long u = user("u");
    long grant = inA(() -> repository().insertUser(d, u, creator));

    inA(
        () -> {
          repository().delete(grant);
          repository().delete(grant);
          return null;
        });

    assertThat(inA(() -> repository().findById(grant))).isEmpty();
    assertThat(inA(() -> repository().countByDataset(d))).isZero();
  }

  // ------------------------------------------------------------------ 조회

  @Test
  @DisplayName("findByDataset — 대상·추가한 사람 이름을 조인하고 추가 시각·id 오름차순이며 다른 데이터셋·다른 테넌트는 섞이지 않는다")
  void findByDataset() {
    long d = dataset("d");
    long other = dataset("other");
    long u = user("u");
    long r = role("r");
    long g1 = inA(() -> repository().insertUser(d, u, creator));
    long g2 = inA(() -> repository().insertRole(d, r, null));
    inA(() -> repository().insertUser(other, u, creator));

    List<GrantRow> rows = inA(() -> repository().findByDataset(d));

    assertThat(rows).extracting(GrantRow::id).containsExactly(g1, g2);
    GrantRow userRow = rows.get(0);
    assertThat(userRow.type()).isEqualTo("USER");
    assertThat(userRow.subjectId()).isEqualTo(u);
    assertThat(userRow.subjectName()).isEqualTo(prefix + "-u-name");
    assertThat(userRow.grantedBy()).isEqualTo(creator);
    assertThat(userRow.grantedByName()).isEqualTo(prefix + "-creator-name");
    assertThat(userRow.grantedAt()).isNotNull();
    GrantRow roleRow = rows.get(1);
    assertThat(roleRow.type()).isEqualTo("ROLE");
    assertThat(roleRow.subjectId()).isEqualTo(r);
    assertThat(roleRow.subjectName()).isEqualTo(prefix + "-r");
    assertThat(roleRow.grantedBy()).isNull();
    assertThat(roleRow.grantedByName()).isNull();
    // 다른 테넌트 컨텍스트에서는 A 의 항목이 보이지 않는다.
    assertThat(inB(() -> repository().findByDataset(d))).isEmpty();
  }

  @Test
  @DisplayName("findByDatasetAndSubject·findRowById — 대상 종류별로 이름 조인 행 하나를 찾고 없으면 빈 값")
  void findByDatasetAndSubject_findRowById() {
    long d = dataset("d");
    long u = user("u");
    long r = role("r");
    long g1 = inA(() -> repository().insertUser(d, u, creator));
    long g2 = inA(() -> repository().insertRole(d, r, creator));

    GrantRow byUser = inA(() -> repository().findByDatasetAndSubject(d, u, null)).orElseThrow();
    GrantRow byRole = inA(() -> repository().findByDatasetAndSubject(d, null, r)).orElseThrow();

    assertThat(byUser.id()).isEqualTo(g1);
    assertThat(byUser.subjectName()).isEqualTo(prefix + "-u-name");
    assertThat(byRole.id()).isEqualTo(g2);
    assertThat(byRole.subjectName()).isEqualTo(prefix + "-r");
    assertThat(inA(() -> repository().findByDatasetAndSubject(d, creator, null))).isEmpty();
    assertThat(inA(() -> repository().findByDatasetAndSubject(d, null, -1L))).isEmpty();

    GrantRow row = inA(() -> repository().findRowById(g2)).orElseThrow();
    assertThat(row.subjectName()).isEqualTo(prefix + "-r");
    assertThat(row.grantedByName()).isEqualTo(prefix + "-creator-name");
    assertThat(inA(() -> repository().findRowById(-1L))).isEmpty();
    assertThat(inB(() -> repository().findRowById(g2))).isEmpty();
  }

  @Test
  @DisplayName("findById — 이름 조인 없이 원 행만 읽고 없으면 빈 값")
  void findById() {
    long d = dataset("d");
    long u = user("u");
    long g = inA(() -> repository().insertUser(d, u, creator));

    GrantRow row = inA(() -> repository().findById(g)).orElseThrow();

    assertThat(row.id()).isEqualTo(g);
    assertThat(row.datasetId()).isEqualTo(d);
    assertThat(row.userId()).isEqualTo(u);
    assertThat(row.roleId()).isNull();
    assertThat(row.subjectName()).isNull();
    assertThat(row.grantedBy()).isEqualTo(creator);
    assertThat(row.grantedByName()).isNull();
    assertThat(row.grantedAt()).isNotNull();
    assertThat(inA(() -> repository().findById(-1L))).isEmpty();
  }

  // ------------------------------------------------------------------ 복사

  @Test
  @DisplayName("copy — 원본 항목을 대상·주체 그대로 복사하되 추가한 사람은 복제자로 바꾸고 이미 있는 항목은 건너뛴다")
  void copy() {
    long from = dataset("from");
    long to = dataset("to");
    long u = user("u");
    long u2 = user("u2");
    long r = role("r");
    inA(() -> repository().insertUser(from, u, null));
    inA(() -> repository().insertUser(from, u2, null));
    inA(() -> repository().insertRole(from, r, null));
    // 대상에 사용자 u 가 이미 있다 — 건너뛴다.
    inA(() -> repository().insertUser(to, u, null));

    int copied = inA(() -> repository().copy(from, to, creator));

    assertThat(copied).isEqualTo(2);
    List<GrantRow> rows = inA(() -> repository().findByDataset(to));
    assertThat(rows).hasSize(3);
    assertThat(inA(() -> repository().findSubjects(List.of(to))).get(to))
        .containsExactlyInAnyOrder(
            GrantSubject.user(u), GrantSubject.user(u2), GrantSubject.role(r));
    // 기존 u 항목은 그대로(추가한 사람 null), 새로 복사된 항목은 복제자.
    assertThat(
            inA(() -> repository().findByDatasetAndSubject(to, u, null)).orElseThrow().grantedBy())
        .isNull();
    assertThat(
            inA(() -> repository().findByDatasetAndSubject(to, null, r)).orElseThrow().grantedBy())
        .isEqualTo(creator);
    // 한 번 더 복사해도 모두 건너뛴다.
    assertThat(inA(() -> repository().copy(from, to, creator))).isZero();
    assertThat(inA(() -> repository().countByDataset(to))).isEqualTo(3);
    // 원본은 그대로.
    assertThat(inA(() -> repository().countByDataset(from))).isEqualTo(3);
    // 항목 없는 원본은 0.
    assertThat(inA(() -> repository().copy(dataset("empty"), to, creator))).isZero();
    // 복제자 없음(null)도 그대로 복사한다.
    long to2 = dataset("to2");
    assertThat(inA(() -> repository().copy(from, to2, null))).isEqualTo(3);
    assertThat(inA(() -> repository().findByDataset(to2)))
        .extracting(GrantRow::grantedBy)
        .containsOnlyNulls();
  }

  // ------------------------------------------------------------------ 역할 삭제 보호

  @Test
  @DisplayName("lockDatasetsGrantedToRole — 이 역할이 걸린 데이터셋 id 를 오름차순으로 돌려주고 다른 역할의 데이터셋은 뺀다")
  void lockDatasetsGrantedToRole() {
    long r = role("r");
    long other = role("other");
    long d1 = dataset("d1");
    long d2 = dataset("d2");
    long d3 = dataset("d3");
    fx.grantDatasetAccess(tenantA, d2, r, null);
    fx.grantDatasetAccess(tenantA, d1, r, null);
    fx.grantDatasetAccess(tenantA, d3, other, null);

    List<Long> locked = inA(() -> repository().lockDatasetsGrantedToRole(r));

    assertThat(locked).containsExactly(Math.min(d1, d2), Math.max(d1, d2));
    assertThat(inA(() -> repository().lockDatasetsGrantedToRole(role("none")))).isEmpty();
  }

  @Test
  @DisplayName("datasetsWhereRoleIsSoleGrantOnAllowlistLevel — 허용 목록 필요 등급에서 이 역할 하나뿐인 데이터셋만 돌려준다")
  void datasetsWhereRoleIsSoleGrantOnAllowlistLevel() {
    long allow = level("allow", 5, true);
    long plain = level("plain", 6, false);
    long r = role("r");
    long u = user("u");
    long sole = fx.createDataset(tenantA, creator, "t_" + prefix + "_sole", allow);
    long shared = fx.createDataset(tenantA, creator, "t_" + prefix + "_shared", allow);
    long plainDs = fx.createDataset(tenantA, creator, "t_" + prefix + "_plain", plain);
    fx.grantDatasetAccess(tenantA, sole, r, null);
    fx.grantDatasetAccess(tenantA, shared, r, null);
    fx.grantDatasetAccess(tenantA, shared, null, u);
    fx.grantDatasetAccess(tenantA, plainDs, r, null);

    assertThat(inA(() -> repository().datasetsWhereRoleIsSoleGrantOnAllowlistLevel(r)))
        .containsExactly(sole);
    assertThat(inA(() -> repository().datasetsWhereRoleIsSoleGrantOnAllowlistLevel(role("x"))))
        .isEmpty();
  }

  @Test
  @DisplayName("findDatasetIdsWithoutAllowlist — 그 등급에 속하면서 허용 항목이 없는 데이터셋만 돌려준다")
  void findDatasetIdsWithoutAllowlist() {
    long lv = level("lv", 5, true);
    long otherLv = level("olv", 6, true);
    long u = user("u");
    long empty = fx.createDataset(tenantA, creator, "t_" + prefix + "_empty", lv);
    long listed = fx.createDataset(tenantA, creator, "t_" + prefix + "_listed", lv);
    fx.createDataset(tenantA, creator, "t_" + prefix + "_otherlv", otherLv);
    fx.grantDatasetAccess(tenantA, listed, null, u);

    assertThat(inA(() -> repository().findDatasetIdsWithoutAllowlist(lv))).containsExactly(empty);
    assertThat(inB(() -> repository().findDatasetIdsWithoutAllowlist(lv))).isEmpty();
  }
}
