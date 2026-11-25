package com.smartfirehub.platform.service;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.platform.dto.PlatformAddMemberRequest;
import com.smartfirehub.platform.dto.TenantOwnerAssignRequest;
import com.smartfirehub.platform.dto.TenantSummaryResponse;
import com.smartfirehub.platform.exception.TenantNotFoundException;
import com.smartfirehub.platform.repository.PlatformTenantRepository;
import com.smartfirehub.user.dto.AddMemberResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 운영자 콘솔에서 지정한 테넌트에 멤버를 추가하고(WD-750), 소유자를 지정(이전)한다({@link #assignOwner}, WD-1132).
 *
 * <p>워크스페이스 관리자가 아니어도(운영자 토큰엔 테넌트가 없다) 테넌트 생성 직후 첫 멤버를 넣거나 관리자가 없는 테넌트를 복구할 수 있게 한다. 핵심 단계는 웹 멤버
 * 추가와 같은 {@code TenantMemberAdder} 를 쓴다.
 *
 * <p><b>이 메서드에 {@code @Transactional} 을 두지 않는다.</b> 테넌트 GUC 는 트랜잭션이 시작될 때만 적용되므로, 범위를 잡기 전에 트랜잭션이
 * 열리면 RLS 테이블({@code role}·{@code user_role})이 테넌트 없이 열려 조회가 0행이 되고 쓰기는 거부된다. 그래서 범위({@code
 * TenantContext.runScopedGet}) → 트랜잭션({@link PlatformTenantMemberAddTransaction}) 순서로 감싼다.
 */
@Service
@RequiredArgsConstructor
public class PlatformTenantMemberService {

  private final PlatformTenantRepository tenantRepository;
  private final PlatformTenantMemberAddTransaction addTransaction;
  private final PlatformAuditRecorder auditRecorder;
  // 소유자 지정(WD-1132) — 변경(웹과 같은 OwnershipTransferService)과 감사를 한 트랜잭션으로 묶는 본체
  private final PlatformTenantOwnerAssignTransaction ownerAssignTransaction;

  /**
   * 테넌트에 멤버를 추가하고 플랫폼 감사를 남긴다.
   *
   * @param tenantId 대상 테넌트
   * @param request 추가 요청(이메일 정규화는 {@code TenantMemberAdder} 가 한다)
   * @param operatorId 감사 행위자인 운영자
   * @return 멤버가 된 계정 id 와 새 계정 생성 여부
   */
  public AddMemberResponse addMember(
      long tenantId, PlatformAddMemberRequest request, long operatorId) {
    TenantSummaryResponse tenant =
        tenantRepository
            .findById(tenantId)
            .orElseThrow(() -> new TenantNotFoundException("테넌트를 찾을 수 없습니다: " + tenantId));
    if ("SUSPENDED".equals(tenant.status())) {
      // 정지된 테넌트의 멤버는 어차피 들어갈 수 없다 — 조용히 추가하면 운영자가 「추가했는데 왜 못 들어가나」를 겪는다. 먼저 재개하게 한다.
      throw new CodedApiException(
          HttpStatus.CONFLICT, "TENANT_SUSPENDED", "정지된 테넌트에는 멤버를 추가할 수 없습니다. 먼저 재개하세요");
    }
    // 이메일 정규화(trim·소문자)는 TenantMemberAdder 가 한 곳에서 한다 — 요청은 그대로 넘긴다.
    // 범위를 먼저 잡고 그 안에서 트랜잭션을 연다 — 역할 조회·부여가 대상 테넌트로만 간다(클래스 Javadoc 참고).
    PlatformTenantMemberAddTransaction.Added added =
        TenantContext.runScopedGet(tenantId, () -> addTransaction.execute(tenantId, request));

    // 감사는 범위 <b>밖</b>에서 남긴다 — 범위 안이면 audit_log.tenant_id 가 대상 테넌트(GUC 기본값)가 되어 플랫폼 감사 화면(tenant
    // 없는 행)에 보이지 않는다. 운영자 조치이므로 다른 운영자 조치처럼 플랫폼 감사로 남긴다.
    Map<String, Object> details = commonAuditDetails(tenantId, tenant, added);
    details.put("created", added.created());
    details.put("roles", added.roleNames());
    String resourceId = String.valueOf(tenantId);
    auditRecorder.record(
        operatorId,
        "MEMBER_ADD",
        "tenant",
        resourceId,
        added.created() ? "새 계정을 만들어 테넌트 멤버로 추가" : "기존 계정을 테넌트 멤버로 추가",
        details);
    // 멤버 추가로 준 역할(ADMIN 포함)도 역할 부여다 — 웹 경로(WD-117)처럼 ROLE_ASSIGN 으로 따로 남겨 누가 ADMIN 을 줬는지 찾게 한다.
    // 역할 목록은 added 하나로만 싣는다(같은 목록을 roles 로 두 번 싣지 않는다).
    Map<String, Object> roleDetails = commonAuditDetails(tenantId, tenant, added);
    roleDetails.put("added", added.roleNames());
    auditRecorder.record(
        operatorId, "ROLE_ASSIGN", "tenant", resourceId, "멤버 추가로 역할 부여", roleDetails);
    return new AddMemberResponse(added.user().id(), added.created());
  }

  /**
   * 테넌트의 소유자를 지정(이전)하고 플랫폼 감사를 남긴다(WD-1132).
   *
   * <p>검증·변경은 웹과 공유하는 {@code OwnershipTransferService} 가 한다(확인 이름 400, 비멤버 404, 비활성·이미 소유자 409).
   * 정지된 테넌트도 막지 않는다 — 소유자와 연락이 안 될 때 운영자가 쓰는 복구 수단이라, 정지 상태에서도 소유자를 바꿀 수 있어야 한다(멤버 추가와 다른 점).
   *
   * <p>멤버 추가와 같은 이유로 범위({@code TenantContext.runScoped}) 안에서 트랜잭션이 열리게 한다 — 역할 테이블(RLS)이 대상 테넌트로만
   * 열린다. 변경과 감사는 같은 트랜잭션이다({@link PlatformTenantOwnerAssignTransaction}) — 감사가 실패하면 소유자도 바뀌지 않는다.
   *
   * @param tenantId 대상 테넌트(없으면 404)
   * @param request 새 소유자와 확인 이름
   * @param operatorId 감사 행위자인 운영자
   */
  public void assignOwner(long tenantId, TenantOwnerAssignRequest request, long operatorId) {
    TenantSummaryResponse tenant =
        tenantRepository
            .findById(tenantId)
            .orElseThrow(() -> new TenantNotFoundException("테넌트를 찾을 수 없습니다: " + tenantId));
    // 소유자 변경과 감사를 한 트랜잭션으로 — 감사 실패 시 변경도 롤백된다(PlatformTenantOwnerAssignTransaction 참고).
    TenantContext.runScoped(
        tenantId, () -> ownerAssignTransaction.execute(tenantId, tenant, request, operatorId));
  }

  /**
   * MEMBER_ADD·ROLE_ASSIGN 감사가 함께 쓰는 details 키(대상 테넌트·계정)를 새 맵으로 만든다. 각 감사는 여기에 자기 고유 키만 더한다 — 한 맵을
   * 복제한 뒤 고치면 어느 키가 어느 감사 것인지 흐려진다.
   */
  private static Map<String, Object> commonAuditDetails(
      long tenantId, TenantSummaryResponse tenant, PlatformTenantMemberAddTransaction.Added added) {
    Map<String, Object> details = PlatformAuditRecorder.tenantAuditDetails(tenantId, tenant);
    details.put("userId", added.user().id());
    details.put("username", added.user().username());
    return details;
  }
}
