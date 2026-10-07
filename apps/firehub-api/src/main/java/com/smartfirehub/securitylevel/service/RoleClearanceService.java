package com.smartfirehub.securitylevel.service;

import static com.smartfirehub.jooq.Tables.DATASET;
import static com.smartfirehub.jooq.Tables.ROLE;
import static com.smartfirehub.jooq.Tables.USER_ROLE;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.jooq.tables.records.RoleRecord;
import com.smartfirehub.role.exception.RoleNotFoundException;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.dto.ClearancePreviewResponse;
import com.smartfirehub.securitylevel.dto.RoleClearanceResponse;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 역할의 최대 열람 등급(스펙 §2.3). 자격은 권한 체크박스와 성격이 달라 별도 API·별도 저장(목업 s3 설계 근거). */
@Service
@RequiredArgsConstructor
public class RoleClearanceService {

  private final DSLContext dsl;
  private final SecurityLevelRepository levelRepository;
  private final DatasetAccessGuard guard;
  private final SecurityAuditRecorder audit;

  /** 역할 편집 화면용 현재 자격 조회. */
  @Transactional(readOnly = true)
  public RoleClearanceResponse get(long roleId) {
    var role = requireRole(roleId);
    long users = dsl.fetchCount(USER_ROLE, USER_ROLE.ROLE_ID.eq(roleId));
    return new RoleClearanceResponse(
        roleId, role.getMaxSecurityLevelId(), users, isSystemAdmin(role));
  }

  /**
   * 저장 전 영향 미리보기 — 호출자가 이 역할을 보유한 경우 저장 후 잃게 되는 데이터셋 수, 저장 후 최상위 열람 역할 수, 영향 사용자 수. 개수만 반환해 목록으로
   * 존재가 드러나지 않게 한다.
   */
  @Transactional(readOnly = true)
  public ClearancePreviewResponse preview(long roleId, long levelId, Clearance caller) {
    requireRole(roleId);
    Map<Long, Integer> rankById = rankById();
    Integer newRoleRank = rankById.get(levelId);
    if (newRoleRank == null) {
      throw levelNotFound();
    }
    long lost = 0;
    if (caller.roleIds().contains(roleId)) {
      // 호출자의 새 rank = 다른 보유 역할 rank 와 새 rank 의 최댓값. 변경 전/후 가시 데이터셋 수 차이가 손실이다.
      int newRank = callerRankAfter(caller, roleId, newRoleRank, rankById);
      long before = visibleCount(caller);
      long after = visibleCount(caller.withRank(newRank));
      lost = Math.max(0, before - after);
    }
    return new ClearancePreviewResponse(
        lost,
        topLevelRoleCountAfter(roleId, levelId, rankById),
        dsl.fetchCount(USER_ROLE, USER_ROLE.ROLE_ID.eq(roleId)));
  }

  /** 규칙: 시스템 ADMIN 고정 / 본인 자격 초과 금지(판단 사항 12 — 현재 자격·새 자격 모두, CR7) / 최상위 열람 역할 ≥ 1(스펙 §2.3 불변식). */
  @Transactional
  public void set(long roleId, long levelId, Clearance caller) {
    var role = requireRole(roleId);
    if (isSystemAdmin(role)) {
      throw new CodedApiException(
          HttpStatus.BAD_REQUEST,
          "SYSTEM_ADMIN_CLEARANCE_FIXED",
          "시스템 ADMIN 역할의 열람 등급은 최상위로 고정됩니다.");
    }
    LevelPolicy to = levelRepository.findById(levelId).orElseThrow(this::levelNotFound);
    // 현재 자격이 호출자 자격보다 높은 역할은 건드릴 수 없다(코드리뷰 CR7 — Task 11 판단 번복). 막지 않으면 낮은 자격의 role:write
    // 보유자가 상위 역할을 끌어내려 그 역할 보유자들(자기보다 높은 열람자)의 가시성을 마음대로 줄일 수 있다. max_security_level_id 는
    // NOT NULL(V133)이지만 손상 상태면 fail-closed 로 같은 거부를 한다.
    int currentRank =
        role.getMaxSecurityLevelId() == null
            ? Integer.MAX_VALUE
            : levelRepository
                .findById(role.getMaxSecurityLevelId())
                .map(LevelPolicy::rank)
                .orElse(Integer.MAX_VALUE);
    // role:write 보유자가 자기 역할을 최상위로 올려 우회하지 못하게 한다.
    if (currentRank > caller.rank() || to.rank() > caller.rank()) {
      throw new CodedApiException(
          HttpStatus.FORBIDDEN,
          "CLEARANCE_ABOVE_OWN",
          "본인 열람 등급보다 높은 등급의 역할은 변경할 수 없고, 그보다 높은 등급을 역할에 지정할 수도 없습니다.");
    }
    if (topLevelRoleCountAfter(roleId, levelId, rankById()) == 0) {
      throw new CodedApiException(
          HttpStatus.CONFLICT, "TOP_LEVEL_ROLE_REQUIRED", "최상위 등급을 열람할 수 있는 역할이 최소 1개 필요합니다.");
    }
    Long from = role.getMaxSecurityLevelId();
    dsl.update(ROLE).set(ROLE.MAX_SECURITY_LEVEL_ID, levelId).where(ROLE.ID.eq(roleId)).execute();
    audit.record(
        caller.userId(),
        "ROLE_CLEARANCE_CHANGE",
        "role",
        String.valueOf(roleId),
        role.getName(),
        Map.of("fromLevelId", from, "toLevelId", levelId));
  }

  private CodedApiException levelNotFound() {
    return new CodedApiException(
        HttpStatus.BAD_REQUEST, "SECURITY_LEVEL_NOT_FOUND", "보안 등급을 찾을 수 없습니다.");
  }

  /** 주어진 자격으로 볼 수 있는 데이터셋 수. */
  private long visibleCount(Clearance c) {
    return dsl.fetchCount(
        DATASET, guard.visibleCondition(c, DATASET.ID, DATASET.SECURITY_LEVEL_ID));
  }

  /** 이 역할의 자격만 바꿨을 때 호출자의 새 rank = (나머지 보유 역할들의 rank, 새 rank) 의 최댓값. */
  private int callerRankAfter(
      Clearance caller, long roleId, int newRoleRank, Map<Long, Integer> rankById) {
    int max = newRoleRank;
    for (var r :
        dsl.select(ROLE.ID, ROLE.MAX_SECURITY_LEVEL_ID)
            .from(ROLE)
            .where(ROLE.ID.in(caller.roleIds()))
            .fetch()) {
      if (!r.get(ROLE.ID).equals(roleId)) {
        max = Math.max(max, rankById.get(r.get(ROLE.MAX_SECURITY_LEVEL_ID)));
      }
    }
    return max;
  }

  /** 이 역할을 levelId 로 바꾼 뒤 최상위 등급을 열람할 수 있는 역할 수. */
  private long topLevelRoleCountAfter(long roleId, long levelId, Map<Long, Integer> rankById) {
    int topRank = levelRepository.findTop().rank();
    long count = 0;
    for (var r : dsl.select(ROLE.ID, ROLE.MAX_SECURITY_LEVEL_ID).from(ROLE).fetch()) {
      long lv = r.get(ROLE.ID).equals(roleId) ? levelId : r.get(ROLE.MAX_SECURITY_LEVEL_ID);
      if (rankById.get(lv) == topRank) {
        count++;
      }
    }
    return count;
  }

  private Map<Long, Integer> rankById() {
    return levelRepository.findAll().stream()
        .collect(Collectors.toMap(LevelPolicy::id, LevelPolicy::rank));
  }

  private RoleRecord requireRole(long roleId) {
    var r = dsl.selectFrom(ROLE).where(ROLE.ID.eq(roleId)).fetchOne();
    if (r == null) {
      throw new RoleNotFoundException("Role not found: " + roleId);
    }
    return r;
  }

  private static boolean isSystemAdmin(RoleRecord r) {
    return "ADMIN".equals(r.getName()) && Boolean.TRUE.equals(r.getIsSystem());
  }
}
