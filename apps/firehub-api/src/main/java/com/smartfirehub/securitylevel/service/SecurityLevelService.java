package com.smartfirehub.securitylevel.service;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.dto.DeleteSecurityLevelRequest;
import com.smartfirehub.securitylevel.dto.MyClearanceResponse;
import com.smartfirehub.securitylevel.dto.SecurityLevelRequest;
import com.smartfirehub.securitylevel.dto.SecurityLevelResponse;
import com.smartfirehub.securitylevel.dto.SecurityLevelUsage;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 등급 정의 관리(스펙 §2.1, §4.7). 접근 범위를 바꾸는 작업이므로 모든 변경을 감사한다. */
@Service
@RequiredArgsConstructor
public class SecurityLevelService {

  /** 하향 사유 최소 길이(스펙 §4.7). DatasetSecurityService 도 같은 값을 쓴다. */
  public static final int MIN_DOWNGRADE_REASON = 10;

  private final SecurityLevelRepository repository;
  private final ClearanceResolver clearanceResolver;
  private final SecurityAuditRecorder audit;
  private final AllowlistSeeder allowlistSeeder;

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
    rejectDuplicateName(req.name(), null);
    int nextRank = repository.findTop().rank() + 1;
    long id = repository.insert(req, nextRank, actor);
    // 판단 사항 13: 새 최상위가 생겼으므로 시스템 ADMIN 을 다시 최상위에 맞춘다.
    repository.syncSystemAdminToTop();
    audit.record(
        actor,
        "SECURITY_LEVEL_CREATE",
        "security_level",
        String.valueOf(id),
        req.name(),
        Map.of("rank", nextRank));
    return SecurityLevelResponse.of(repository.findById(id).orElseThrow());
  }

  @Transactional
  public SecurityLevelResponse update(long id, SecurityLevelRequest req, long actor) {
    LevelPolicy before = require(id);
    rejectDuplicateName(req.name(), id);
    repository.update(id, req, actor);
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
    return SecurityLevelResponse.of(require(id));
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
      String reason = req.reason() == null ? "" : req.reason().trim();
      if (downward && inUse && reason.length() < MIN_DOWNGRADE_REASON) {
        throw new CodedApiException(
            HttpStatus.BAD_REQUEST, "DOWNGRADE_REASON_REQUIRED", "하향 이동에는 사유(10자 이상)가 필요합니다.");
      }
      repository.moveDatasets(id, toId);
      repository.moveRoles(id, toId);
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
  }

  /** 같은 테넌트 안 이름 중복을 UNIQUE 위반(500) 대신 409 로 알린다. */
  private void rejectDuplicateName(String name, Long excludeId) {
    if (repository.existsByName(name, excludeId)) {
      throw new CodedApiException(
          HttpStatus.CONFLICT, "SECURITY_LEVEL_NAME_DUPLICATE", "같은 이름의 보안 등급이 이미 있습니다.");
    }
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
