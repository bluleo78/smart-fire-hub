package com.smartfirehub.platform.service;

import com.smartfirehub.global.tenant.TenantPipelineRoleProvisioner;
import com.smartfirehub.global.tenant.TenantProvisioningService;
import com.smartfirehub.platform.dto.CreateTenantRequest;
import com.smartfirehub.platform.dto.TenantMemberResponse;
import com.smartfirehub.platform.dto.TenantSummaryResponse;
import com.smartfirehub.platform.exception.TenantNotFoundException;
import com.smartfirehub.platform.repository.PlatformTenantRepository;
import com.smartfirehub.user.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 테넌트 생명주기(생성·조회·정지·활성화·멤버 조회).
 *
 * <p>이 서비스는 전역 테이블({@code tenant}/{@code membership})과 {@code provision_tenant_defaults}
 * 만 만진다. 도메인 데이터는 조회하지 않는다 — 설계서 §4 가 크로스테넌트 도메인 조회를 제공하지
 * 않기로 결정했다.
 */
@Service
@RequiredArgsConstructor
public class PlatformTenantService {

  private final PlatformTenantRepository tenantRepository;
  private final TenantProvisioningService provisioningService;
  private final UserRepository userRepository;
  private final TenantPipelineRoleProvisioner pipelineRoleProvisioner;

  /**
   * 신규 테넌트를 만들고 초기 Owner 와 기본 시드를 채운다.
   *
   * <p><b>한 트랜잭션인 이유</b>: 행만 생기고 시드가 빠지면 멤버십이 있어도 권한이 0개라 그 테넌트의
   * 모든 API 가 403 인 좀비 테넌트가 된다. 고칠 방법이 운영자 SQL 뿐이므로 원자적이어야 한다.
   *
   * <p>{@code provision_tenant_defaults} 는 {@code SECURITY DEFINER} 라 호출자의 테넌트 컨텍스트와
   * 무관하게 동작한다 — 운영자 토큰은 컨텍스트가 비어 있으므로(GUC 미설정) 이것이 RLS 테이블에 쓸 수
   * 있는 유일한 경로다. 여기서 GUC 를 직접 심지 않는다.
   *
   * <p><b>데이터 스키마({@code data_t{id}})는 만들지 않는다.</b> {@code TenantSchemaProvisioner} 는
   * 의도적으로 <b>지연 생성</b>이다 — 공유 test DB 에 테넌트가 수백 개 누적돼 있어 테넌트마다 선제
   * 생성하면 스키마가 무한히 쌓인다. 실제로 데이터 테이블을 만드는 테넌트만 스키마를 갖는다.
   *
   * <p><b>파이프라인 실행 DB 롤은 여기서 만든다(#680).</b> 스키마와 달리 롤은 지연 생성할 수 없다 —
   * 스키마를 만드는 시점에 롤이 없으면 {@code TenantSchemaProvisioner} 가 executor GRANT 를 통째로
   * 건너뛰고, 그 사실은 <b>그 테넌트가 파이프라인을 처음 돌릴 때까지</b> 아무 데도 드러나지 않는다
   * (2026-09-17 운영 장애). 그래서 롤이 먼저다.
   */
  @Transactional
  public TenantSummaryResponse create(CreateTenantRequest request) {
    // 사용자 존재 확인은 기존 UserRepository 를 쓴다 — 같은 전역 테이블에 조회를 하나 더 만들면
    // 나중에 사용자 조회 규칙이 바뀔 때 이쪽만 남는다.
    if (userRepository.findById(request.ownerUserId()).isEmpty()) {
      // Owner 없는 테넌트는 아무도 들어갈 수 없다 — 만들기 전에 막는다.
      throw new IllegalArgumentException("존재하지 않는 사용자입니다: " + request.ownerUserId());
    }
    if (tenantRepository.slugExists(request.slug())) {
      // UNIQUE 위반을 500 으로 흘리지 않고 400 으로 알려준다.
      throw new IllegalArgumentException("이미 사용 중인 slug 입니다: " + request.slug());
    }

    long tenantId = tenantRepository.insertTenant(request.slug(), request.name());
    // 순서 의존: provisionDefaults 는 역할을 정의한 뒤 OWNER 멤버십을 읽어 소유자에게 ADMIN 을
    // 배정한다(V121). 멤버십 삽입이 먼저여야 소유자가 권한 0개로 태어나지 않는다.
    tenantRepository.insertOwnerMembership(tenantId, request.ownerUserId());
    provisioningService.provisionDefaults(tenantId);
    // DB 롤 생성은 소유자 커넥션이라 이 트랜잭션에 묶이지 않는다 — 그래서 **일부러 마지막**이다.
    //
    // 실패 두 가지를 구분할 것:
    //  (a) 롤 생성 자체가 실패 → 그 자신의 트랜잭션이 롤백돼 **롤은 남지 않고**, 예외가 전파돼
    //      위의 tenant/membership 삽입도 롤백된다. 아무것도 남지 않으므로 같은 slug 로 재시도하면 된다.
    //  (b) 롤 생성은 커밋됐는데 그 **뒤**가 실패(아래 findById, 또는 커밋 자체) → 존재하지 않을
    //      테넌트 id 의 고아 롤이 남는다. 무해하고 멱등이지만 남기는 남는다.
    // 이 호출을 마지막에 두는 이유가 바로 (b) 의 창을 최소화하는 것이다 — 앞에 두면 뒤따르는
    // 모든 단계의 실패가 전부 (b) 가 된다. 반대로 순서를 뒤집어 롤을 나중에 "언젠가" 만들게 하면
    // "행은 있고 롤은 없는" 테넌트 2와 똑같은 상태가 커밋과 함께 확정된다 — 그것이 이 장애다.
    pipelineRoleProvisioner.ensureRoleIfAutoProvisionEnabled(tenantId);

    return tenantRepository
        .findById(tenantId)
        .orElseThrow(() -> new IllegalStateException("생성한 테넌트를 다시 읽지 못했다: " + tenantId));
  }

  @Transactional(readOnly = true)
  public List<TenantSummaryResponse> list() {
    return tenantRepository.findAll();
  }

  @Transactional(readOnly = true)
  public TenantSummaryResponse detail(long tenantId) {
    return tenantRepository.findById(tenantId).orElseThrow(() -> notFound(tenantId));
  }

  @Transactional(readOnly = true)
  public List<TenantMemberResponse> members(long tenantId) {
    // 없는 테넌트에 빈 목록을 주면 존재 여부가 흐려진다 — 먼저 존재를 확인한다.
    if (tenantRepository.findById(tenantId).isEmpty()) {
      throw notFound(tenantId);
    }
    return tenantRepository.findMembers(tenantId);
  }

  /**
   * 테넌트를 정지한다.
   *
   * <p><b>반영은 즉시가 아니다.</b> 정지는 {@code select-tenant}/{@code refresh} 에서 재검증되므로 이미
   * 발급된 액세스 토큰은 만료(기본 30분)까지 유효하다. 즉시 차단이 필요하면 별도 조치가 필요하며 이
   * 밴드 범위 밖이다 — 런북에 명시한다.
   */
  @Transactional
  public void suspend(long tenantId) {
    if (tenantRepository.updateStatus(tenantId, "SUSPENDED") == 0) {
      throw notFound(tenantId);
    }
  }

  @Transactional
  public void activate(long tenantId) {
    if (tenantRepository.updateStatus(tenantId, "ACTIVE") == 0) {
      throw notFound(tenantId);
    }
  }

  private TenantNotFoundException notFound(long tenantId) {
    return new TenantNotFoundException("테넌트를 찾을 수 없습니다: " + tenantId);
  }
}
