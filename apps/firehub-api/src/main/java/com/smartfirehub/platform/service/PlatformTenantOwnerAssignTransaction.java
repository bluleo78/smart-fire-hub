package com.smartfirehub.platform.service;

import com.smartfirehub.global.platformdb.PlatformTenantSession;
import com.smartfirehub.platform.dto.TenantOwnerAssignRequest;
import com.smartfirehub.platform.dto.TenantSummaryResponse;
import com.smartfirehub.user.dto.OwnershipTransferResult;
import com.smartfirehub.user.service.OwnershipTransferService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자 콘솔 소유자 지정의 트랜잭션 본체(WD-1132) — 소유자 변경과 플랫폼 감사를 <b>한 트랜잭션</b>으로 묶는다.
 *
 * <p>왜: 변경이 먼저 커밋되고 감사를 따로 남기면 감사 실패 시 소유자만 바뀌고 기록이 없다("기록 없는 조치"). 한 트랜잭션이면 감사가 실패하면 변경도 함께 롤백된다.
 *
 * <p>문제는 감사 행의 tenant_id 다. 플랫폼 감사 화면은 tenant_id NULL 행만 보는데, 호출자가 잡은 테넌트 범위로 트랜잭션이 열리면 GUC 가 대상
 * 테넌트라 감사 행이 그 테넌트 것이 된다(RLS WITH CHECK 가 NULL 행을 거부). 그래서 소유자 변경(역할 테이블 RLS 때문에 테넌트 범위 필요)을 끝낸 뒤
 * {@link PlatformTenantSession#clearCurrentTransaction} 으로 같은 트랜잭션의 테넌트 값만 비우고 감사를 쓴다. 감사는 마지막에 쓰므로
 * 비운 뒤 테넌트 범위 문장이 없다.
 *
 * <p>호출자가 {@code TenantContext.runScoped(tenantId, ...)} 범위 안에서 불러야 한다({@link
 * PlatformTenantMemberAddTransaction} 과 같은 이유 — 별도 빈이라야 {@code @Transactional} 프록시가 적용된다).
 */
@Component
@RequiredArgsConstructor
public class PlatformTenantOwnerAssignTransaction {

  private final OwnershipTransferService ownershipTransferService;
  private final PlatformAuditRecorder auditRecorder;
  private final PlatformTenantSession tenantSession;

  /** 소유자를 바꾸고 같은 트랜잭션에서 OWNER_TRANSFER 와 사용자별 ROLE_ASSIGN 감사를 남긴다. */
  @Transactional
  public void execute(
      long tenantId,
      TenantSummaryResponse tenant,
      TenantOwnerAssignRequest request,
      long operatorId) {
    OwnershipTransferResult result =
        ownershipTransferService.transfer(tenantId, request.userId(), request.confirmName());

    // 테넌트 범위 작업은 끝났다 — 이제부터는 테넌트 없는(NULL) 플랫폼 감사 행을 쓴다.
    // 주의: 이 줄과 감사 기록 사이에는 테넌트 범위 문장(RLS 테이블 읽기·쓰기)을 더하지 말 것 — 테넌트 값이 비어 0행을 읽거나 RLS 가 거부한다. 감사 뒤에는
    // 테넌트를 다시 심는다.
    tenantSession.clearCurrentTransaction();

    Map<String, Object> details = PlatformAuditRecorder.tenantAuditDetails(tenantId, tenant);
    details.put("fromUserIds", result.fromUserIds());
    details.put("toUserId", result.toUserId());
    details.put("grantedAdmin", result.grantedAdmin());
    auditRecorder.record(
        operatorId,
        "OWNER_TRANSFER",
        "tenant",
        String.valueOf(tenantId),
        "운영자가 테넌트 소유자를 지정",
        details);
    // 소유권 이전으로 새로 받은 ADMIN 도 역할 부여다 — 멤버 추가처럼 사용자별 ROLE_ASSIGN 으로 남긴다(WD-117).
    for (Long grantedUserId : result.grantedAdminUserIds()) {
      Map<String, Object> roleDetails = PlatformAuditRecorder.tenantAuditDetails(tenantId, tenant);
      roleDetails.put("userId", grantedUserId);
      roleDetails.put("added", List.of("ADMIN"));
      auditRecorder.record(
          operatorId,
          "ROLE_ASSIGN",
          "tenant",
          String.valueOf(tenantId),
          "소유권 이전으로 ADMIN 역할 부여",
          roleDetails);
    }
    // 감사를 다 쓴 뒤 테넌트 값을 되돌린다 — 커밋 직전(BEFORE_COMMIT) 리스너 등 이 트랜잭션의 나머지가 대상 테넌트를 보게 한다(WD-1132).
    // 감사 행은 이미 NULL 테넌트로 써졌으므로 영향이 없다.
    tenantSession.applyToCurrentTransaction(tenantId);
  }
}
