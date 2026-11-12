package com.smartfirehub.securitylevel.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.access.LevelPolicy.AiPolicy;
import com.smartfirehub.securitylevel.access.LevelPolicy.ExportPolicy;
import com.smartfirehub.securitylevel.access.LevelPolicy.SharePolicy;
import com.smartfirehub.securitylevel.dto.SecurityLevelRequest;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository.CandidateRole;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository.CandidateUser;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository.RoleClearanceRow;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository.RoleLevel;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SecurityLevelRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-711).
 *
 * <p>security_level·dataset·role·user_role·dataset_access_grant 는 테넌트 정책(RLS) 아래 있다 — 리포지토리 호출은 늘
 * 테넌트 컨텍스트 ({@code inA}/{@code inB})로 감싼다. 테넌트는 시험마다 새로 만들며, 테넌트 생성 때 기본 등급 하나(rank 2, is_default)가
 * 심겨 있으므로 시험이 만드는 등급은 rank 3 이상을 쓴다. membership·사용자는 정책 없는 전역 테이블이라 후보·멤버 판정은 명시 조건(tenant_id)으로만
 * 걸러진다(Review Focus 1).
 */
public abstract class SecurityLevelRepositoryContractTest extends TenantPairContractBase {

  /** 등급을 만들고 고친 사람 — created_by/updated_by 에 FK 가 없어 임의 값이면 된다. */
  private static final long ACTOR = 1L;

  protected abstract SecurityLevelRepository repository();

  /** 정책·플래그를 모두 지정한 등급 요청. */
  private static SecurityLevelRequest req(
      String name,
      boolean allowlist,
      boolean adminBypass,
      ExportPolicy export,
      AiPolicy ai,
      SharePolicy share,
      boolean audit) {
    return new SecurityLevelRequest(name, allowlist, adminBypass, export, ai, share, audit, null);
  }

  /** 제한 없는 기본 정책 등급 요청. */
  private static SecurityLevelRequest plain(String name) {
    return req(name, false, false, ExportPolicy.ALLOW, AiPolicy.ALL, SharePolicy.ALLOW, false);
  }

  /** 테넌트 A 에 등급 하나를 넣고 id 를 돌려준다. */
  private long insertA(SecurityLevelRequest r, int rank) {
    return inA(() -> repository().insert(r, rank, ACTOR));
  }

  private LevelPolicy defaultA() {
    return inA(() -> repository().findDefault()).orElseThrow();
  }

  @Test
  @DisplayName(
      "insert 는 created_at·updated_at 을 DB 시계(저장 기준 벽시계)로 쓴다 — Tibero 는 세션 시간대 DEFAULT 가 아닌 UTC 벽시계")
  void insert_writesDbNowTimestamps() {
    long id = insertA(plain(prefix + "-ts"), 3);

    LocalDateTime now = fx.dbWallClockNow();
    for (LocalDateTime at : fx.securityLevelCreatedAndUpdatedAt(id)) {
      assertThat(Duration.between(at, now).abs()).isLessThan(Duration.ofMinutes(1));
    }
  }

  @Test
  @DisplayName(
      "insert 는 이름을 trim 해 저장하고 findById·findAll(rank 순)·rankById·findTop·findDefault 가 같은 값을 읽는다")
  void insert_and_reads() {
    LevelPolicy def = defaultA();
    long l3 =
        insertA(
            req(
                "  " + prefix + "-민감  ",
                false,
                false,
                ExportPolicy.PERMISSION,
                AiPolicy.SELF_HOSTED_ONLY,
                SharePolicy.ALLOW,
                true),
            3);
    long l4 =
        insertA(
            req(
                prefix + "-기밀",
                true,
                true,
                ExportPolicy.DENY,
                AiPolicy.DENY,
                SharePolicy.DENY,
                false),
            4);

    LevelPolicy p3 =
        new LevelPolicy(
            l3,
            prefix + "-민감",
            3,
            false,
            false,
            false,
            ExportPolicy.PERMISSION,
            AiPolicy.SELF_HOSTED_ONLY,
            SharePolicy.ALLOW,
            true);
    LevelPolicy p4 =
        new LevelPolicy(
            l4,
            prefix + "-기밀",
            4,
            false,
            true,
            true,
            ExportPolicy.DENY,
            AiPolicy.DENY,
            SharePolicy.DENY,
            false);

    assertThat(def.rank()).isEqualTo(2);
    assertThat(def.isDefault()).isTrue();
    assertThat(inA(() -> repository().findById(l3))).contains(p3);
    assertThat(inA(() -> repository().findById(-1L))).isEmpty();
    assertThat(inA(() -> repository().findAll())).containsExactly(def, p3, p4);
    assertThat(inA(() -> repository().rankById())).isEqualTo(Map.of(def.id(), 2, l3, 3, l4, 4));
    assertThat(inA(() -> repository().findTop())).isEqualTo(p4);
    assertThat(inA(() -> repository().findDefault())).contains(def);
  }

  @Test
  @DisplayName("update 는 이름(trim)·플래그·정책을 바꾸고 rank·기본 여부는 그대로 둔다")
  void update_changesFields() {
    long id = insertA(plain(prefix + "-u"), 3);

    runA(
        () ->
            repository()
                .update(
                    id,
                    req(
                        " " + prefix + "-u2 ",
                        true,
                        true,
                        ExportPolicy.DENY,
                        AiPolicy.SELF_HOSTED_ONLY,
                        SharePolicy.DENY,
                        true),
                    ACTOR));

    assertThat(inA(() -> repository().findById(id)))
        .contains(
            new LevelPolicy(
                id,
                prefix + "-u2",
                3,
                false,
                true,
                true,
                ExportPolicy.DENY,
                AiPolicy.SELF_HOSTED_ONLY,
                SharePolicy.DENY,
                true));
  }

  @Test
  @DisplayName("existsByName — trim 한 이름과 정확히 같은지 보고 excludeId 는 빼며 다른 테넌트 이름은 보지 않는다")
  void existsByName() {
    long id = insertA(plain(prefix + "-n"), 3);

    assertThat(inA(() -> repository().existsByName("  " + prefix + "-n ", null))).isTrue();
    assertThat(inA(() -> repository().existsByName(prefix + "-n", id))).isFalse();
    assertThat(inA(() -> repository().existsByName(prefix + "-n", -1L))).isTrue();
    assertThat(inA(() -> repository().existsByName(prefix + "-N", null))).isFalse();
    assertThat(inB(() -> repository().existsByName(prefix + "-n", null))).isFalse();
  }

  @Test
  @DisplayName("setDefault 는 기존 기본을 끄고 새 등급을 켜 1 을 돌려준다 — 없는 등급이면 0(기본이 사라짐)")
  void setDefault() {
    LevelPolicy def = defaultA();
    long id = insertA(plain(prefix + "-d"), 3);

    assertThat(inA(() -> repository().setDefault(id))).isEqualTo(1);
    assertThat(inA(() -> repository().findDefault()).map(LevelPolicy::id)).contains(id);
    assertThat(inA(() -> repository().findById(def.id())).orElseThrow().isDefault()).isFalse();

    // 다른 테넌트 컨텍스트에서는 A 의 등급을 켜지 못한다.
    assertThat(inB(() -> repository().setDefault(def.id()))).isZero();
    assertThat(inA(() -> repository().findDefault()).map(LevelPolicy::id)).contains(id);

    assertThat(inA(() -> repository().setDefault(-1L))).isZero();
    assertThat(inA(() -> repository().findDefault())).isEmpty();
  }

  @Test
  @DisplayName("delete 는 등급을 지우고 다른 테넌트 컨텍스트의 delete·update 는 A 의 등급에 닿지 않는다")
  void delete_and_otherTenant() {
    long keep = insertA(plain(prefix + "-k"), 3);
    long gone = insertA(plain(prefix + "-g"), 4);

    runA(() -> repository().delete(gone));
    assertThat(inA(() -> repository().findById(gone))).isEmpty();

    inB(
        () -> {
          repository().delete(keep);
          repository().update(keep, plain(prefix + "-hijack"), ACTOR);
          return null;
        });
    assertThat(inA(() -> repository().findById(keep)).orElseThrow().name())
        .isEqualTo(prefix + "-k");
    assertThat(inB(() -> repository().findById(keep))).isEmpty();
    assertThat(inB(() -> repository().findAll())).extracting(LevelPolicy::id).doesNotContain(keep);
  }

  @Test
  @DisplayName("데이터셋 등급 조회 — findDatasetLevelId·감사·허용 목록 필터·distinct 등급(빈 입력은 빈 결과, 없는 데이터셋은 예외)")
  void datasetLevelLookups() {
    LevelPolicy def = defaultA();
    long audited =
        insertA(
            req(
                prefix + "-a",
                false,
                false,
                ExportPolicy.ALLOW,
                AiPolicy.ALL,
                SharePolicy.ALLOW,
                true),
            3);
    long allow =
        insertA(
            req(
                prefix + "-w",
                true,
                false,
                ExportPolicy.ALLOW,
                AiPolicy.ALL,
                SharePolicy.ALLOW,
                false),
            4);
    long d1 = fx.createDataset(tenantA, prefix + " d1");
    long d2 = fx.createDataset(tenantA, prefix + " d2");
    long d3 = fx.createDataset(tenantA, prefix + " d3");
    runA(() -> repository().setDatasetLevel(d2, audited));
    runA(() -> repository().setDatasetLevel(d3, allow));

    assertThat(inA(() -> repository().findDatasetLevelId(d1))).isEqualTo(def.id());
    assertThat(inA(() -> repository().findDatasetLevelId(d2))).isEqualTo(audited);
    assertThatThrownBy(() -> inA(() -> repository().findDatasetLevelId(-1L)))
        .isInstanceOf(RuntimeException.class);

    List<Long> all = List.of(d1, d2, d3);
    assertThat(inA(() -> repository().findAuditedDatasetIds(all))).containsExactly(d2);
    assertThat(inA(() -> repository().findAllowlistRequiredDatasetIds(all))).containsExactly(d3);
    assertThat(inA(() -> repository().findAuditedDatasetIds(List.of()))).isEmpty();
    assertThat(inA(() -> repository().findAllowlistRequiredDatasetIds(List.of()))).isEmpty();
    assertThat(inA(() -> repository().findDistinctLevelIds(List.of(d1, d2, d3, d1))))
        .containsExactlyInAnyOrder(def.id(), audited, allow);
    assertThat(inA(() -> repository().findDistinctLevelIds(List.of()))).isEmpty();
    // 다른 테넌트 컨텍스트에서는 A 의 데이터셋이 보이지 않는다.
    assertThat(inB(() -> repository().findAuditedDatasetIds(all))).isEmpty();
    assertThat(inB(() -> repository().findDistinctLevelIds(all))).isEmpty();
  }

  @Test
  @DisplayName("1001 개 데이터셋 id 목록도 감사·허용 목록·distinct 등급 조회가 터지지 않는다(Tibero IN 1000 상한)")
  void largeIdLists() {
    long audited =
        insertA(
            req(
                prefix + "-a",
                true,
                false,
                ExportPolicy.ALLOW,
                AiPolicy.ALL,
                SharePolicy.ALLOW,
                true),
            3);
    long d = fx.createDataset(tenantA, prefix + " big");
    runA(() -> repository().setDatasetLevel(d, audited));
    List<Long> ids = new ArrayList<>();
    for (long i = 0; i < 1000; i++) {
      ids.add(-1000 - i);
    }
    ids.add(d);

    assertThat(inA(() -> repository().findAuditedDatasetIds(ids))).containsExactly(d);
    assertThat(inA(() -> repository().findAllowlistRequiredDatasetIds(ids))).containsExactly(d);
    assertThat(inA(() -> repository().findDistinctLevelIds(ids))).containsExactly(audited);
    assertThat(inA(() -> repository().findRoleLevelIds(ids))).isEmpty();
  }

  @Test
  @DisplayName("등급별 집계 — 데이터셋 수·역할 수·역할 허용 데이터셋 수(사용자 항목 제외), move 는 옮긴 행 수를 돌려준다")
  void countsAndMoves() {
    LevelPolicy def = defaultA();
    long l3 = insertA(plain(prefix + "-3"), 3);
    long l4 = insertA(plain(prefix + "-4"), 4);
    long d1 = fx.createDataset(tenantA, prefix + " d1");
    long d2 = fx.createDataset(tenantA, prefix + " d2");
    long d3 = fx.createDataset(tenantA, prefix + " d3");
    runA(() -> repository().setDatasetLevel(d2, l3));
    runA(() -> repository().setDatasetLevel(d3, l3));
    long r1 = fx.createRole(tenantA, prefix + "-r1", null);
    long r2 = fx.createRole(tenantA, prefix + "-r2", null);
    runA(() -> repository().updateRoleMaxLevel(r2, l3));
    long user = fx.createUser(prefix + "-gu", null);
    fx.grantDatasetAccess(tenantA, d2, r1, null);
    fx.grantDatasetAccess(tenantA, d3, r1, null);
    fx.grantDatasetAccess(tenantA, d1, r2, null);
    fx.grantDatasetAccess(tenantA, d2, null, user);

    assertThat(inA(() -> repository().countDatasetsByLevel()))
        .isEqualTo(Map.of(def.id(), 1L, l3, 2L));
    assertThat(inA(() -> repository().countRolesByLevel())).isEqualTo(Map.of(def.id(), 1L, l3, 1L));
    assertThat(inA(() -> repository().countRoleGrantedDatasetsByLevelAndRole()))
        .isEqualTo(Map.of(l3, Map.of(r1, 2L), def.id(), Map.of(r2, 1L)));
    assertThat(inB(() -> repository().countDatasetsByLevel())).isEmpty();
    assertThat(inB(() -> repository().countRoleGrantedDatasetsByLevelAndRole())).isEmpty();

    assertThat(inB(() -> repository().moveDatasets(l3, l4))).isZero();
    assertThat(inA(() -> repository().moveDatasets(l3, l4))).isEqualTo(2);
    assertThat(inA(() -> repository().moveRoles(l3, l4))).isEqualTo(1);
    assertThat(inA(() -> repository().countDatasetsByLevel()))
        .isEqualTo(Map.of(def.id(), 1L, l4, 2L));
    assertThat(inA(() -> repository().countRolesByLevel())).isEqualTo(Map.of(def.id(), 1L, l4, 1L));
  }

  @Test
  @DisplayName("역할 조회·변경 — 자격 행·멤버 수·최대 등급 변경·역할 등급 목록·id 매핑·존재·후보 역할(이름 순), 다른 테넌트 역할은 안 보인다")
  void roleQueries() {
    LevelPolicy def = defaultA();
    long l3 = insertA(plain(prefix + "-3"), 3);
    long rb = fx.createRole(tenantA, prefix + "-b", null);
    long ra = fx.createRole(tenantA, prefix + "-a", null);
    long other = fx.createRole(tenantB, prefix + "-o", null);
    long u1 = fx.createUser(prefix + "-m1", null);
    long u2 = fx.createUser(prefix + "-m2", null);
    fx.createMembership(u1, tenantA, "ACTIVE");
    fx.createMembership(u2, tenantA, "ACTIVE");
    fx.grantRole(tenantA, u1, ra);
    fx.grantRole(tenantA, u2, ra);

    runA(() -> repository().updateRoleMaxLevel(ra, l3));

    assertThat(inA(() -> repository().findRoleClearanceRow(ra)))
        .contains(new RoleClearanceRow(prefix + "-a", false, l3));
    assertThat(inA(() -> repository().findRoleClearanceRow(-1L))).isEmpty();
    assertThat(inA(() -> repository().findRoleClearanceRow(other))).isEmpty();
    assertThat(inA(() -> repository().countRoleMembers(ra))).isEqualTo(2L);
    assertThat(inA(() -> repository().countRoleMembers(rb))).isZero();
    assertThat(inA(() -> repository().roleExists(ra))).isTrue();
    assertThat(inA(() -> repository().roleExists(other))).isFalse();
    assertThat(inA(() -> repository().roleExists(-1L))).isFalse();
    assertThat(inA(() -> repository().findRoleLevelIds(List.of(ra, rb, other))))
        .isEqualTo(Map.of(ra, l3, rb, def.id()));
    assertThat(inA(() -> repository().findRoleLevelIds(List.of()))).isEmpty();
    assertThat(inA(() -> repository().findRoleLevels()))
        .containsExactlyInAnyOrder(
            new RoleLevel(ra, prefix + "-a", l3, false),
            new RoleLevel(rb, prefix + "-b", def.id(), false));
    assertThat(inA(() -> repository().findCandidateRoles()))
        .containsExactly(
            new CandidateRole(ra, prefix + "-a", l3),
            new CandidateRole(rb, prefix + "-b", def.id()));

    // 다른 테넌트 컨텍스트에서는 A 의 역할 등급을 바꾸지 못한다.
    runA(() -> repository().updateRoleMaxLevel(rb, def.id()));
    inB(
        () -> {
          repository().updateRoleMaxLevel(rb, l3);
          return null;
        });
    assertThat(inA(() -> repository().findRoleClearanceRow(rb)).orElseThrow().maxSecurityLevelId())
        .isEqualTo(def.id());
  }

  @Test
  @DisplayName("syncSystemAdminToTop — 시스템 ADMIN 역할만 현재 최상위 등급으로 옮기고 findRoleLevels 가 ADMIN 을 표시한다")
  void syncSystemAdminToTop() {
    fx.provisionDefaults(tenantA);
    long admin = fx.roleIdByName(tenantA, SystemAdminRole.NAME);
    long plainRole = fx.createRole(tenantA, prefix + "-p", null);
    LevelPolicy before = inA(() -> repository().findTop());
    long newTop = insertA(plain(prefix + "-top"), before.rank() + 1);
    Long plainLevel =
        inA(() -> repository().findRoleClearanceRow(plainRole)).orElseThrow().maxSecurityLevelId();

    runA(() -> repository().syncSystemAdminToTop());

    RoleClearanceRow adminRow = inA(() -> repository().findRoleClearanceRow(admin)).orElseThrow();
    assertThat(adminRow.isSystem()).isTrue();
    assertThat(adminRow.maxSecurityLevelId()).isEqualTo(newTop);
    assertThat(
            inA(() -> repository().findRoleClearanceRow(plainRole))
                .orElseThrow()
                .maxSecurityLevelId())
        .isEqualTo(plainLevel);
    List<RoleLevel> levels = inA(() -> repository().findRoleLevels());
    assertThat(levels)
        .filteredOn(RoleLevel::systemAdmin)
        .extracting(RoleLevel::roleId)
        .containsExactly(admin);
    assertThat(levels)
        .filteredOn(r -> r.roleId() == plainRole)
        .singleElement()
        .extracting(RoleLevel::systemAdmin)
        .isEqualTo(false);
  }

  @Test
  @DisplayName("countDatasets — 제한 없음·rank·허용 목록(본인·역할)·역할 없음 규칙대로 센다(다른 테넌트 데이터셋은 안 센다)")
  void countDatasets() {
    long secret =
        insertA(
            req(
                prefix + "-s",
                true,
                false,
                ExportPolicy.ALLOW,
                AiPolicy.ALL,
                SharePolicy.ALLOW,
                false),
            4);
    long d1 = fx.createDataset(tenantA, prefix + " open");
    long d2 = fx.createDataset(tenantA, prefix + " secret");
    runA(() -> repository().setDatasetLevel(d2, secret));
    long viewer = fx.createUser(prefix + "-v", null);
    long role = fx.createRole(tenantA, prefix + "-vr", null);
    fx.grantDatasetAccess(tenantA, d2, null, viewer);
    fx.createDataset(tenantB, prefix + " b");

    assertThat(inA(() -> repository().countDatasets(DatasetVisibility.UNRESTRICTED))).isEqualTo(2L);
    assertThat(inA(() -> repository().countDatasets(vis(viewer, 4, Set.of())))).isEqualTo(2L);
    assertThat(inA(() -> repository().countDatasets(vis(-5L, 4, Set.of())))).isEqualTo(1L);
    assertThat(inA(() -> repository().countDatasets(vis(viewer, 2, Set.of())))).isEqualTo(1L);
    assertThat(inA(() -> repository().countDatasets(vis(viewer, Clearance.NO_RANK, Set.of()))))
        .isZero();
    fx.grantDatasetAccess(tenantA, d2, role, null);
    assertThat(inA(() -> repository().countDatasets(vis(-5L, 4, Set.of(role))))).isEqualTo(2L);
    assertThat(inB(() -> repository().countDatasets(DatasetVisibility.UNRESTRICTED))).isEqualTo(1L);
    assertThat(d1).isNotEqualTo(d2);
  }

  /** 테넌트 A 의 비AI 자격 가시성. */
  private DatasetVisibility vis(long userId, int rank, Set<Long> roleIds) {
    return new DatasetVisibility(
        new Clearance(userId, tenantA, rank, roleIds, false, Set.of()), null);
  }

  @Test
  @DisplayName("isTempDataset·isTempDatasetOfStep — origin_type TEMP 와 만든 스텝을 본다")
  void tempDataset() {
    long pipeline = fx.createPipeline(tenantA, prefix + "-p", true);
    long step = fx.createStep(pipeline, "s1", "SQL", null, null, "REPLACE");
    long step2 = fx.createStep(pipeline, "s2", "SQL", null, null, "REPLACE");
    long temp = fx.createDataset(tenantA, prefix + " temp");
    long src = fx.createDataset(tenantA, prefix + " src");
    fx.setDatasetField(temp, "origin_type", "TEMP");
    fx.setDatasetField(temp, "source_pipeline_step_id", step);

    assertThat(inA(() -> repository().isTempDataset(temp))).isTrue();
    assertThat(inA(() -> repository().isTempDataset(src))).isFalse();
    assertThat(inA(() -> repository().isTempDataset(-1L))).isFalse();
    assertThat(inA(() -> repository().isTempDatasetOfStep(temp, step))).isTrue();
    assertThat(inA(() -> repository().isTempDatasetOfStep(temp, step2))).isFalse();
    assertThat(inA(() -> repository().isTempDatasetOfStep(src, step))).isFalse();
    assertThat(inB(() -> repository().isTempDataset(temp))).isFalse();
  }

  @Test
  @DisplayName("자동 상향 표시 — 파이프라인 출력 등급(상향 시각 기록)·수동 변경(표시 지움)·clear·restore(비어 있을 때만)")
  void autoRaise() {
    long l3 = insertA(plain(prefix + "-3"), 3);
    long l4 = insertA(plain(prefix + "-4"), 4);
    long ds = fx.createDataset(tenantA, prefix + " out");

    assertThat(inA(() -> repository().findAutoRaisedAt(ds))).isNull();
    assertThat(inA(() -> repository().findAutoRaisedAt(-1L))).isNull();

    runA(() -> repository().setPipelineOutputLevel(ds, l3, false));
    assertThat(inA(() -> repository().findDatasetLevelId(ds))).isEqualTo(l3);
    assertThat(inA(() -> repository().findAutoRaisedAt(ds))).isNull();

    runA(() -> repository().setPipelineOutputLevel(ds, l4, true));
    assertThat(inA(() -> repository().findDatasetLevelId(ds))).isEqualTo(l4);
    assertThat(inA(() -> repository().findAutoRaisedAt(ds)))
        .isCloseTo(LocalDateTime.now(), within(2, ChronoUnit.MINUTES));

    runA(() -> repository().setDatasetLevelClearingAutoRaise(ds, l3));
    assertThat(inA(() -> repository().findDatasetLevelId(ds))).isEqualTo(l3);
    assertThat(inA(() -> repository().findAutoRaisedAt(ds))).isNull();

    LocalDateTime prev = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123_456_000);
    runA(() -> repository().restoreAutoRaisedAtIfAbsent(ds, prev));
    assertThat(inA(() -> repository().findAutoRaisedAt(ds))).isEqualTo(prev);

    // 이미 표시가 있으면 이전 값으로 덮지 않는다.
    runA(() -> repository().restoreAutoRaisedAtIfAbsent(ds, prev.minusDays(1)));
    assertThat(inA(() -> repository().findAutoRaisedAt(ds))).isEqualTo(prev);

    // 다른 테넌트 컨텍스트의 clear 는 닿지 않는다.
    inB(
        () -> {
          repository().clearAutoRaisedAt(ds);
          return null;
        });
    assertThat(inA(() -> repository().findAutoRaisedAt(ds))).isEqualTo(prev);

    runA(() -> repository().clearAutoRaisedAt(ds));
    assertThat(inA(() -> repository().findAutoRaisedAt(ds))).isNull();
    // 이전 값이 없으면(null) 비어 있는 채로 둔다.
    runA(() -> repository().restoreAutoRaisedAtIfAbsent(ds, null));
    assertThat(inA(() -> repository().findAutoRaisedAt(ds))).isNull();
  }

  @Test
  @DisplayName("lockDatasetRow·lockDatasetAndFindLevelId — 있는 데이터셋은 잠그고 등급을, 없거나 다른 테넌트면 false·빈 값")
  void lockDataset() {
    LevelPolicy def = defaultA();
    long ds = fx.createDataset(tenantA, prefix + " lock");

    assertThat(inA(() -> repository().lockDatasetRow(ds))).isTrue();
    assertThat(inA(() -> repository().lockDatasetRow(-1L))).isFalse();
    assertThat(inB(() -> repository().lockDatasetRow(ds))).isFalse();
    assertThat(inA(() -> repository().lockDatasetAndFindLevelId(ds))).contains(def.id());
    assertThat(inA(() -> repository().lockDatasetAndFindLevelId(-1L))).isEmpty();
    assertThat(inB(() -> repository().lockDatasetAndFindLevelId(ds))).isEmpty();
  }

  @Test
  @DisplayName("lockTenantLevelDefinitions 는 테넌트 트랜잭션 안에서 예외 없이 잠근다")
  void lockTenantLevelDefinitions() {
    assertThat(
            inA(
                () -> {
                  repository().lockTenantLevelDefinitions(tenantA);
                  return repository().findTop();
                }))
        .isNotNull();
  }

  @Test
  @DisplayName("후보 사용자·isActiveMember — 이 테넌트 ACTIVE 멤버이면서 활성 계정만(다른 테넌트 멤버·정지·비활성 제외, 이름·id 순)")
  void candidateUsers_reviewFocus1() {
    long u1 = fx.createUser(prefix + "-c1", prefix + "1@x.io");
    long u2 = fx.createUser(prefix + "-c2", null);
    long u2b = fx.createUser(prefix + "-c2b", null);
    long inactive = fx.createUser(prefix + "-ci", null);
    long suspended = fx.createUser(prefix + "-cs", null);
    long onlyB = fx.createUser(prefix + "-cb", null);
    fx.setUserName(u1, "가" + prefix);
    fx.setUserName(u2, "나" + prefix);
    fx.setUserName(u2b, "나" + prefix);
    fx.setUserName(inactive, "다" + prefix);
    fx.createMembership(u1, tenantA, "ACTIVE");
    fx.createMembership(u2, tenantA, "ACTIVE");
    fx.createMembership(u2b, tenantA, "ACTIVE");
    fx.createMembership(inactive, tenantA, "ACTIVE");
    fx.setUserActive(inactive, false);
    fx.createMembership(suspended, tenantA, "SUSPENDED");
    fx.createMembership(onlyB, tenantB, "ACTIVE");

    long first = Math.min(u2, u2b);
    long second = Math.max(u2, u2b);
    assertThat(inA(() -> repository().findCandidateUsers(tenantA)))
        .containsExactly(
            new CandidateUser(u1, "가" + prefix, prefix + "1@x.io"),
            new CandidateUser(first, "나" + prefix, null),
            new CandidateUser(second, "나" + prefix, null));
    assertThat(inB(() -> repository().findCandidateUsers(tenantB)))
        .extracting(CandidateUser::id)
        .containsExactly(onlyB);

    assertThat(inA(() -> repository().isActiveMember(u1, tenantA))).isTrue();
    assertThat(inA(() -> repository().isActiveMember(inactive, tenantA))).isFalse();
    assertThat(inA(() -> repository().isActiveMember(suspended, tenantA))).isFalse();
    assertThat(inA(() -> repository().isActiveMember(onlyB, tenantA))).isFalse();
    assertThat(inA(() -> repository().isActiveMember(onlyB, tenantB))).isTrue();
    assertThat(inA(() -> repository().isActiveMember(-1L, tenantA))).isFalse();
  }

  @Test
  @DisplayName("updateRanks — 두 등급의 rank 를 한 번에 맞바꾸고(Review Focus 4) 전체 순열도 적용한다, 빈 맵은 아무것도 안 한다")
  void updateRanks_swap() {
    LevelPolicy def = defaultA();
    long l3 = insertA(plain(prefix + "-3"), 3);
    long l4 = insertA(plain(prefix + "-4"), 4);

    runA(() -> repository().updateRanks(Map.of(l3, 4, l4, 3)));
    assertThat(inA(() -> repository().rankById())).isEqualTo(Map.of(def.id(), 2, l3, 4, l4, 3));

    Map<Long, Integer> perm = new HashMap<>();
    perm.put(l4, 1);
    perm.put(def.id(), 2);
    perm.put(l3, 3);
    runA(() -> repository().updateRanks(perm));
    assertThat(inA(() -> repository().findAll()))
        .extracting(LevelPolicy::id)
        .containsExactly(l4, def.id(), l3);

    runA(() -> repository().updateRanks(Map.of()));
    assertThat(inA(() -> repository().rankById())).isEqualTo(Map.of(l4, 1, def.id(), 2, l3, 3));

    // 다른 테넌트 컨텍스트에서는 A 의 rank 를 바꾸지 못한다.
    inB(
        () -> {
          repository().updateRanks(Map.of(l3, 9));
          return null;
        });
    assertThat(inA(() -> repository().rankById()).get(l3)).isEqualTo(3);
  }
}
