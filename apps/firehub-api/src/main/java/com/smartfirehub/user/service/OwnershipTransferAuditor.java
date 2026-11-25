package com.smartfirehub.user.service;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.role.dto.RoleResponse;
import com.smartfirehub.role.repository.RoleRepository;
import com.smartfirehub.user.dto.OwnershipTransferResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 워크스페이스 범위 감사 기록 공용 도우미(WD-1158) — 소유권 이전의 OWNER_TRANSFER 와 사용자별 ROLE_ASSIGN 행을 만든다.
 *
 * <p>왜 따로 두나: 웹 경로({@code UserService.transferOwnership})와 운영자 콘솔 경로({@code
 * PlatformTenantOwnerAssignTransaction})가 같은 모양의 행을 써야 워크스페이스 관리자의 감사 화면에서 구분 없이 읽힌다. 두 곳에 복제하면 한쪽만
 * 고쳐져 어긋나기 쉬워 한 곳에 둔다. 행의 tenant_id 는 DB 기본값(GUC)이므로 호출 트랜잭션의 테넌트 범위가 살아 있을 때 불러야 한다.
 */
@Component
@RequiredArgsConstructor
public class OwnershipTransferAuditor {

  private final AuditLogService auditLogService;
  private final RoleRepository roleRepository;

  /**
   * 소유권 이전 감사 — OWNER_TRANSFER 1건과, ADMIN 을 새로 받은 사용자마다 ROLE_ASSIGN 1건.
   *
   * <p>ROLE_ASSIGN 의 부여 직전 상태는 지금 역할에서 ADMIN 만 뺀 것이다(이전은 ADMIN 추가 외에 역할을 바꾸지 않는다).
   *
   * @param extraMetadata 모든 행 metadata 에 덧붙일 항목(콘솔 경로의 {@code via: console}). 없으면 null.
   */
  public void logTransfer(
      long actorId,
      String actorName,
      OwnershipTransferResult result,
      Map<String, Object> extraMetadata) {
    Map<String, Object> transferMeta = new LinkedHashMap<>();
    transferMeta.put("fromUserIds", result.fromUserIds());
    transferMeta.put("toUserId", result.toUserId());
    transferMeta.put("grantedAdmin", result.grantedAdmin());
    if (extraMetadata != null) {
      transferMeta.putAll(extraMetadata);
    }
    log(actorId, actorName, "OWNER_TRANSFER", result.toUserId(), "워크스페이스 소유권 이전", transferMeta);
    // 소유권 이전으로 새로 받은 ADMIN 도 역할 부여다 — 다른 ADMIN 부여 경로처럼 ROLE_ASSIGN 으로 남겨 「누가 ADMIN 을 줬나」를
    // 추적한다(WD-117).
    for (Long grantedUserId : result.grantedAdminUserIds()) {
      List<RoleResponse> after = roleRepository.findByUserId(grantedUserId);
      List<RoleResponse> before = after.stream().filter(r -> !"ADMIN".equals(r.name())).toList();
      logRoleAssign(actorId, actorName, grantedUserId, before, after, extraMetadata);
    }
  }

  /**
   * 역할 교체 감사(ROLE_ASSIGN). 역할 id 집합으로 차이를 내고, metadata 에는 사람이 읽을 이름을 싣는다. 목록은 findByUserId 의 역할 id
   * 오름차순 그대로라 결정적이다. 차이가 없으면 아무것도 남기지 않는다.
   */
  public void logRoleAssign(
      long actorId,
      String actorName,
      long userId,
      List<RoleResponse> before,
      List<RoleResponse> after,
      Map<String, Object> extraMetadata) {
    Set<Long> beforeIds = before.stream().map(RoleResponse::id).collect(Collectors.toSet());
    Set<Long> afterIds = after.stream().map(RoleResponse::id).collect(Collectors.toSet());
    if (beforeIds.equals(afterIds)) {
      return; // 실제 변경 없음 — 감사 생략
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("before", before.stream().map(RoleResponse::name).toList());
    metadata.put("after", after.stream().map(RoleResponse::name).toList());
    metadata.put(
        "added",
        after.stream().filter(r -> !beforeIds.contains(r.id())).map(RoleResponse::name).toList());
    metadata.put(
        "removed",
        before.stream().filter(r -> !afterIds.contains(r.id())).map(RoleResponse::name).toList());
    if (extraMetadata != null) {
      metadata.putAll(extraMetadata);
    }
    log(actorId, actorName, "ROLE_ASSIGN", userId, "역할 변경", metadata);
  }

  /** 대상이 사용자인 SUCCESS 감사 한 행을 쓴다. */
  private void log(
      long actorId,
      String actorName,
      String action,
      long targetUserId,
      String description,
      Map<String, Object> metadata) {
    auditLogService.log(
        actorId,
        actorName,
        action,
        "user",
        String.valueOf(targetUserId),
        description,
        null,
        null,
        "SUCCESS",
        null,
        metadata);
  }
}
