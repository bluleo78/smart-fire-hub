package com.smartfirehub.securitylevel.service;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.dto.AllowlistImpactResponse;
import com.smartfirehub.securitylevel.dto.DeleteSecurityLevelRequest;
import com.smartfirehub.securitylevel.dto.MyClearanceResponse;
import com.smartfirehub.securitylevel.dto.ReorderPreviewResponse;
import com.smartfirehub.securitylevel.dto.SecurityLevelRequest;
import com.smartfirehub.securitylevel.dto.SecurityLevelResponse;
import com.smartfirehub.securitylevel.dto.SecurityLevelUsage;
import com.smartfirehub.securitylevel.event.SecurityLevelsChangedEvent;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.postgresql.util.PSQLException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 등급 정의 관리(스펙 §2.1, §4.7). 접근 범위를 바꾸는 작업이므로 모든 변경을 감사한다. */
@Service
@RequiredArgsConstructor
public class SecurityLevelService {

  /** 하향 사유 최소 길이(스펙 §4.7). DatasetSecurityService 도 같은 값을 쓴다. */
  public static final int MIN_DOWNGRADE_REASON = 10;

  /**
   * 테넌트 등급 개수 상한(스펙 §4.1, WD-29) — PYTHON 읽기 슬롯 롤 수와 같다. 슬롯 k 가 "rank 오름차순 k 번째 등급까지"를 읽으므로 등급이 이보다
   * 많으면 그 위치를 표현할 롤이 없어 그 등급 자격자의 PYTHON 이 실행될 수 없다.
   */
  public static final int MAX_LEVELS = TenantPipelineRole.PYTHON_READ_SLOTS;

  /** 등급 이름 유니크 제약 이름(V133) — 경합으로 생긴 위반을 이 제약일 때만 이름 중복으로 번역한다. */
  private static final String NAME_UNIQUE_CONSTRAINT = "uq_security_level_name";

  private final SecurityLevelRepository repository;
  private final ClearanceResolver clearanceResolver;
  private final SecurityAuditRecorder audit;
  private final AllowlistSeeder allowlistSeeder;

  /** 등급 정의 변경 이벤트 발행기(공통 결정 R2: 발행은 이 서비스에서만, 변경마다 정확히 1번). */
  private final ApplicationEventPublisher events;

  @Transactional(readOnly = true)
  public List<SecurityLevelResponse> list() {
    return repository.findAll().stream().map(SecurityLevelResponse::of).toList();
  }

  /** 등급별 데이터셋·역할 개수만 돌려준다(이름 비노출). */
  @Transactional(readOnly = true)
  public List<SecurityLevelUsage> usage() {
    Map<Long, Long> ds = repository.countDatasetsByLevel();
    Map<Long, Long> roles = repository.countRolesByLevel();
    return repository.findAll().stream()
        .map(
            l ->
                new SecurityLevelUsage(
                    l.id(), ds.getOrDefault(l.id(), 0L), roles.getOrDefault(l.id(), 0L)))
        .toList();
  }

  /** 현재 사용자의 열람 자격(역할이 없으면 rank/levelId 모두 null). */
  @Transactional(readOnly = true)
  public MyClearanceResponse myClearance() {
    Clearance c = clearanceResolver.current();
    if (c.rank() == Clearance.NO_RANK) {
      return new MyClearanceResponse(null, null);
    }
    Long levelId =
        repository.findAll().stream()
            .filter(l -> l.rank() == c.rank())
            .map(LevelPolicy::id)
            .findFirst()
            .orElse(null);
    return new MyClearanceResponse(c.rank(), levelId);
  }

  /** 새 등급은 최상위로 추가한다(순서는 이후 ↑↓ 로 조정). ADMIN 은 새 최상위로 동기화. */
  @Transactional
  public SecurityLevelResponse create(SecurityLevelRequest req, long actor) {
    // 상한 검사 — 이름 중복보다 먼저(어차피 만들 수 없는 요청). 동시 생성 경합으로 11번째가 생길 수는 있으나, 그 위치의
    // 자격자는 prepareForRun 이 슬롯 범위 밖으로 fail-closed 거부한다(과권한 아님).
    if (repository.findAll().size() >= MAX_LEVELS) {
      throw new CodedApiException(
          HttpStatus.BAD_REQUEST,
          "SECURITY_LEVEL_LIMIT_EXCEEDED",
          "보안 등급은 최대 " + MAX_LEVELS + "개까지 만들 수 있습니다.");
    }
    rejectDuplicateName(req.name(), null);
    int nextRank = repository.findTop().rank() + 1;
    long id = withNameDuplicateAs409(() -> repository.insert(req, nextRank, actor));
    // 판단 사항 13: 새 최상위가 생겼으므로 시스템 ADMIN 을 다시 최상위에 맞춘다.
    repository.syncSystemAdminToTop();
    audit.record(
        actor,
        "SECURITY_LEVEL_CREATE",
        "security_level",
        String.valueOf(id),
        req.name(),
        Map.of("rank", nextRank));
    publishLevelsChanged(SecurityLevelsChangedEvent.Kind.CREATED, id);
    return SecurityLevelResponse.of(repository.findById(id).orElseThrow());
  }

  @Transactional
  public SecurityLevelResponse update(long id, SecurityLevelRequest req, long actor) {
    LevelPolicy before = require(id);
    rejectDuplicateName(req.name(), id);
    withNameDuplicateAs409(
        () -> {
          repository.update(id, req, actor);
          return null;
        });
    // allowlist_required 를 새로 켜고 시드를 요청했으면, 빈 허용 목록 데이터셋을 현재 열람 가능 역할로 채운다(Task 9).
    int seeded = 0;
    if (!before.allowlistRequired()
        && req.allowlistRequired()
        && Boolean.TRUE.equals(req.seedAllowlistFromViewers())) {
      seeded = allowlistSeeder.seedEmptyAllowlistsWithViewerRoles(id, actor);
    }
    Map<String, Object> meta = new HashMap<>();
    meta.put("before", SecurityLevelResponse.of(before));
    meta.put("after", req);
    meta.put("seededGrants", seeded);
    audit.record(
        actor, "SECURITY_LEVEL_UPDATE", "security_level", String.valueOf(id), req.name(), meta);
    // 이름만 바꿔도 낸다 — 정책(allowlist_required·ai_policy 등) 변경 여부를 여기서 가리지 않고 구독자가 테넌트 전체를 다시 맞춘다.
    publishLevelsChanged(SecurityLevelsChangedEvent.Kind.UPDATED, id);
    return SecurityLevelResponse.of(require(id));
  }

  /** 허용 목록 켜기 영향 — 이 등급 데이터셋 중 허용 목록이 빈 것의 수(시드 정의와 동일). */
  @Transactional(readOnly = true)
  public AllowlistImpactResponse allowlistImpact(long levelId) {
    require(levelId);
    return new AllowlistImpactResponse(allowlistSeeder.datasetsWithoutAllowlist(levelId).size());
  }

  /** 기본 등급 교체 — 테넌트당 기본은 정확히 1개(기존 기본을 끄고 새로 켠다). */
  @Transactional
  public void setDefault(long id, long actor) {
    LevelPolicy previous = repository.findDefault().orElse(null);
    require(id);
    repository.setDefault(id);
    Map<String, Object> meta = new HashMap<>();
    meta.put("previousDefaultLevelId", previous == null ? null : previous.id());
    audit.record(
        actor, "SECURITY_LEVEL_DEFAULT_CHANGE", "security_level", String.valueOf(id), null, meta);
  }

  /**
   * 스펙 §4.7 삭제 규칙: 기본 삭제 불가 / 사용 중(데이터셋·역할)이면 이동 대상 필수 / 하향 이동이면 사유 필수.
   *
   * <p>삭제 후 시스템 ADMIN 을 최상위로 재동기화하므로 "최상위 열람 역할 ≥1" 불변식이 유지된다.
   */
  @Transactional
  public void delete(long id, DeleteSecurityLevelRequest req, long actor) {
    LevelPolicy target = require(id);
    if (target.isDefault()) {
      throw new CodedApiException(
          HttpStatus.CONFLICT,
          "SECURITY_LEVEL_DEFAULT_UNDELETABLE",
          "기본 등급은 삭제할 수 없습니다. 다른 등급을 기본으로 지정한 뒤 삭제하세요.");
    }
    long datasets = repository.countDatasetsByLevel().getOrDefault(id, 0L);
    long roles = repository.countRolesByLevel().getOrDefault(id, 0L);
    boolean inUse = datasets > 0 || roles > 0;
    Long toId = req == null ? null : req.reassignToLevelId();
    if (inUse && (toId == null || toId == id)) {
      throw new CodedApiException(
          HttpStatus.BAD_REQUEST,
          "SECURITY_LEVEL_REASSIGN_REQUIRED",
          "사용 중인 등급은 옮길 등급을 지정해야 삭제할 수 있습니다.");
    }
    if (toId != null && toId != id) {
      LevelPolicy to = require(toId);
      boolean downward = to.rank() < target.rank();
      // 감사 메타에는 원문 사유(req.reason())를 남기므로 정리된 반환값은 쓰지 않는다.
      requireDowngradeReason(downward && inUse, req.reason(), "하향 이동에는 사유(10자 이상)가 필요합니다.");
      repository.moveDatasets(id, toId);
      repository.moveRoles(id, toId);
      // 이동 대상이 허용 목록 필요 등급이면, 옮겨진 데이터셋의 빈 허용 목록이 고아(아무도 못 봄)가 되지 않게 같은 트랜잭션에서 채운다
      // (스펙 §2.4, §4.5). 이미 목록이 있는 데이터셋은 건드리지 않는다. 역할 이동 이후에 호출해 옮겨진 역할도 열람 역할로 포함된다.
      if (to.allowlistRequired()) {
        allowlistSeeder.seedEmptyAllowlistsWithViewerRoles(toId, actor);
      }
    }
    repository.delete(id);
    repository.syncSystemAdminToTop();
    Map<String, Object> meta = new HashMap<>();
    meta.put("deletedName", target.name());
    meta.put("reassignToLevelId", toId);
    meta.put("movedDatasets", datasets);
    meta.put("movedRoles", roles);
    meta.put("reason", req == null ? null : req.reason());
    audit.record(
        actor, "SECURITY_LEVEL_DELETE", "security_level", String.valueOf(id), target.name(), meta);
    // 데이터셋 일괄 이동(moveDatasets)은 데이터셋별 이벤트 대신 이 DELETED 하나로 알린다(계획 결정 14).
    publishLevelsChanged(SecurityLevelsChangedEvent.Kind.DELETED, id);
  }

  /**
   * 순서 변경 영향 미리보기 — 역할별 "rank 기준" 열람 데이터셋 수의 변화(허용 목록은 순서와 무관하므로 제외). 아무것도 변경하지 않는다.
   *
   * <p>시스템 ADMIN 은 적용 후에도 최상위로 동기화되므로 계산에서도 항상 최상위로 본다(증감 0 → 목록에서 빠진다). 등급이 없는 역할은 어떤 순서에서도 0 건이라
   * 빠진다.
   */
  @Transactional(readOnly = true)
  public ReorderPreviewResponse previewReorder(List<Long> orderedIds) {
    List<LevelPolicy> levels = repository.findAll();
    requireFullPermutation(levels, orderedIds);
    Map<Long, Integer> oldRank = new HashMap<>();
    levels.forEach(l -> oldRank.put(l.id(), l.rank()));
    Map<Long, Integer> newRank = new HashMap<>();
    for (int i = 0; i < orderedIds.size(); i++) {
      newRank.put(orderedIds.get(i), i + 1);
    }
    Map<Long, Long> datasetsByLevel = repository.countDatasetsByLevel();
    Map<Long, Map<Long, Long>> roleGranted = repository.countRoleGrantedDatasetsByLevelAndRole();
    Set<Long> allowlistLevels =
        levels.stream()
            .filter(LevelPolicy::allowlistRequired)
            .map(LevelPolicy::id)
            .collect(Collectors.toSet());
    List<ReorderPreviewResponse.RoleImpact> impacts = new ArrayList<>();
    for (var role : repository.findRoleLevels()) {
      if (role.levelId() == null && !role.systemAdmin()) {
        continue;
      }
      long before =
          visibleCount(
              role.systemAdmin() ? Integer.MAX_VALUE : oldRank.get(role.levelId()),
              role.roleId(),
              oldRank,
              datasetsByLevel,
              allowlistLevels,
              roleGranted);
      long after =
          visibleCount(
              role.systemAdmin() ? Integer.MAX_VALUE : newRank.get(role.levelId()),
              role.roleId(),
              newRank,
              datasetsByLevel,
              allowlistLevels,
              roleGranted);
      if (before != after) {
        impacts.add(
            new ReorderPreviewResponse.RoleImpact(role.roleId(), role.roleName(), after - before));
      }
    }
    return new ReorderPreviewResponse(impacts);
  }

  /** 순서 적용 — rank 일괄 갱신(UNIQUE 지연) 후 시스템 ADMIN 을 새 최상위로 재동기화한다(판단 사항 13). 영향 요약을 감사 메타에 함께 남긴다. */
  @Transactional
  public void applyReorder(List<Long> orderedIds, long actor) {
    // previewReorder 가 순열 검증을 포함한다(잘못된 목록이면 여기서 400).
    ReorderPreviewResponse impact = previewReorder(orderedIds);
    Map<Long, Integer> newRanks = new HashMap<>();
    for (int i = 0; i < orderedIds.size(); i++) {
      newRanks.put(orderedIds.get(i), i + 1);
    }
    repository.updateRanks(newRanks);
    repository.syncSystemAdminToTop();
    audit.record(
        actor,
        "SECURITY_LEVEL_REORDER",
        "security_level",
        null,
        null,
        Map.of("orderedIds", orderedIds, "impact", impact.roles()));
    publishLevelsChanged(SecurityLevelsChangedEvent.Kind.REORDERED, null);
  }

  /** 등급 정의 변경 이벤트를 낸다 — {@code @Transactional} 메서드 안에서만 부른다(AFTER_COMMIT 구독자는 롤백된 변경을 받지 않는다). */
  private void publishLevelsChanged(SecurityLevelsChangedEvent.Kind kind, Long levelId) {
    events.publishEvent(
        new SecurityLevelsChangedEvent(TenantContext.require("등급 정의 변경 이벤트"), kind, levelId));
  }

  /**
   * 역할 1개가 rank 기준으로 볼 수 있는 데이터셋 수. rank 가 roleRank 이하인 등급을 합산하되, 허용 목록 필요 등급은 그 역할의 ROLE 허용 항목이 있는
   * 데이터셋만 센다 (허용 목록 등급은 rank 만으로는 열람 불가 — DatasetAccessPolicy). 사용자 단위 허용 항목은 역할에 귀속시키지 않는다(역할별 증감
   * 의미: "이 역할 자격 보유자 전원이 공통으로 보는 수").
   */
  private static long visibleCount(
      int roleRank,
      long roleId,
      Map<Long, Integer> ranks,
      Map<Long, Long> datasetsByLevel,
      Set<Long> allowlistLevels,
      Map<Long, Map<Long, Long>> roleGranted) {
    long sum = 0;
    for (var e : ranks.entrySet()) {
      if (e.getValue() > roleRank) {
        continue;
      }
      long levelId = e.getKey();
      sum +=
          allowlistLevels.contains(levelId)
              ? roleGranted.getOrDefault(levelId, Map.of()).getOrDefault(roleId, 0L)
              : datasetsByLevel.getOrDefault(levelId, 0L);
    }
    return sum;
  }

  /** 순서 목록은 현재 테넌트 등급의 완전한 순열이어야 한다(누락·중복·타 테넌트 id 거부). */
  private static void requireFullPermutation(List<LevelPolicy> levels, List<Long> orderedIds) {
    var expected = levels.stream().map(LevelPolicy::id).collect(Collectors.toSet());
    if (orderedIds.size() != expected.size() || !expected.equals(new HashSet<>(orderedIds))) {
      throw new CodedApiException(
          HttpStatus.BAD_REQUEST, "SECURITY_LEVEL_ORDER_INVALID", "모든 보안 등급을 정확히 한 번씩 포함해야 합니다.");
    }
  }

  /**
   * 하향 사유 검증(스펙 §4.7) — 등급 변경·삭제 이동이 같은 규칙(null→빈 문자열, 앞뒤 공백 제거 후 {@link #MIN_DOWNGRADE_REASON}자
   * 이상)을 쓰게 한 곳에 둔다. 메시지는 화면 문맥마다 달라 호출자가 넘긴다.
   *
   * @param downgrade 사유가 필요한 하향인가 — false 면 검사하지 않는다
   * @return 앞뒤 공백을 뗀 사유(없으면 빈 문자열)
   */
  static String requireDowngradeReason(boolean downgrade, String rawReason, String message) {
    String reason = rawReason == null ? "" : rawReason.trim();
    if (downgrade && reason.length() < MIN_DOWNGRADE_REASON) {
      throw new CodedApiException(HttpStatus.BAD_REQUEST, "DOWNGRADE_REASON_REQUIRED", message);
    }
    return reason;
  }

  /** 같은 테넌트 안 이름 중복을 UNIQUE 위반(500) 대신 409 로 알린다. */
  private void rejectDuplicateName(String name, Long excludeId) {
    if (repository.existsByName(name, excludeId)) {
      throw nameDuplicate();
    }
  }

  private static CodedApiException nameDuplicate() {
    return new CodedApiException(
        HttpStatus.CONFLICT, "SECURITY_LEVEL_NAME_DUPLICATE", "같은 이름의 보안 등급이 이미 있습니다.");
  }

  /**
   * 등급 INSERT/이름 UPDATE 를 실행하고, 이름 유니크 위반이면 사전 검사({@link #rejectDuplicateName})와 같은 409 로 번역한다(후속
   * F5).
   *
   * <p>왜: 사전 검사와 쓰기 사이에 같은 이름이 동시에 들어오면 사전 검사는 미커밋 행을 못 봐 통과하고, 쓰기가 유니크 인덱스에서 위반으로 끝난다. 그대로 두면 전역
   * 처리기가 코드 없는 일반 "Data integrity violation" 으로 답해 화면이 이름 중복 안내를 못 한다. 다른 제약 위반(예: 동시 생성의 순위 충돌
   * {@code uq_security_level_rank})은 이름 중복이 아니므로 그대로 던진다. 이 예외로 트랜잭션은 롤백된다(CodedApiException 은 런타임
   * 예외).
   */
  private static <T> T withNameDuplicateAs409(java.util.function.Supplier<T> write) {
    try {
      return write.get();
    } catch (DuplicateKeyException e) {
      if (NAME_UNIQUE_CONSTRAINT.equals(violatedConstraint(e))) {
        throw nameDuplicate();
      }
      throw e;
    }
  }

  /** 예외 원인 사슬에서 PostgreSQL 이 보고한 위반 제약 이름을 찾는다(없으면 null). */
  private static String violatedConstraint(Throwable e) {
    for (Throwable c = e; c != null; c = c.getCause()) {
      if (c instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
        return psql.getServerErrorMessage().getConstraint();
      }
    }
    return null;
  }

  private LevelPolicy require(long id) {
    return repository
        .findById(id)
        .orElseThrow(
            () ->
                new CodedApiException(
                    HttpStatus.NOT_FOUND, "SECURITY_LEVEL_NOT_FOUND", "보안 등급을 찾을 수 없습니다."));
  }
}
