package com.smartfirehub.platform.service;

import com.smartfirehub.global.tenant.TenantPipelineRoleProvisioner;
import com.smartfirehub.global.tenant.TenantProvisioningService;
import com.smartfirehub.global.transaction.AfterCommitRunner;
import com.smartfirehub.notification.service.SseEmitterRegistry;
import com.smartfirehub.platform.dto.CreateTenantRequest;
import com.smartfirehub.platform.dto.TenantCreatedResponse;
import com.smartfirehub.platform.dto.TenantMemberResponse;
import com.smartfirehub.platform.dto.TenantSummaryResponse;
import com.smartfirehub.platform.exception.TenantNotFoundException;
import com.smartfirehub.platform.repository.PlatformTenantRepository;
import com.smartfirehub.platform.service.TenantDataStoreSetupService.SetupOutcome;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 테넌트 생명주기(생성·조회·정지·활성화·멤버 조회).
 *
 * <p>이 서비스는 전역 테이블({@code tenant}/{@code membership})과 {@code provision_tenant_defaults} 만 만진다. 도메인
 * 데이터는 조회하지 않는다 — 설계서 §4 가 크로스테넌트 도메인 조회를 제공하지 않기로 결정했다.
 */
@Service
@RequiredArgsConstructor
public class PlatformTenantService {

  private final PlatformTenantRepository tenantRepository;
  private final TenantProvisioningService provisioningService;
  private final UserRepository userRepository;
  private final TenantPipelineRoleProvisioner pipelineRoleProvisioner;
  private final PlatformAuditRecorder auditRecorder;
  private final TenantDataStoreSetupService dataStoreSetupService;
  // 정지 해제 시 그 워크스페이스의 예약(트리거·스마트 작업)을 커밋 뒤 다시 등록한다(WD-174)
  private final TenantScheduleResumer scheduleResumer;
  // 정지 시 그 워크스페이스로 연 알림 연결을 커밋 뒤 전부 닫는다(WD-190)
  private final SseEmitterRegistry sseEmitterRegistry;

  /**
   * 신규 테넌트를 만들고 초기 Owner 와 기본 시드를 채운다.
   *
   * <p><b>한 트랜잭션인 이유</b>: 행만 생기고 시드가 빠지면 멤버십이 있어도 권한이 0개라 그 테넌트의 모든 API 가 403 인 좀비 테넌트가 된다. 고칠 방법이
   * 운영자 SQL 뿐이므로 원자적이어야 한다.
   *
   * <p>{@code provision_tenant_defaults} 는 {@code SECURITY DEFINER} 라 호출자의 테넌트 컨텍스트와 무관하게 동작한다 —
   * 운영자 토큰은 컨텍스트가 비어 있으므로(GUC 미설정) 이것이 RLS 테이블에 쓸 수 있는 유일한 경로다. 여기서 GUC 를 직접 심지 않는다.
   *
   * <p><b>데이터 스키마({@code data_t{id}})는 만들지 않는다.</b> {@code TenantSchemaProvisioner} 는 의도적으로 <b>지연
   * 생성</b>이다 — 공유 test DB 에 테넌트가 수백 개 누적돼 있어 테넌트마다 선제 생성하면 스키마가 무한히 쌓인다. 실제로 데이터 테이블을 만드는 테넌트만 스키마를
   * 갖는다.
   *
   * <p><b>파이프라인 실행 DB 롤은 여기서 만든다(#680).</b> 스키마와 달리 롤은 지연 생성할 수 없다 — 스키마를 만드는 시점에 롤이 없으면 {@code
   * TenantSchemaProvisioner} 가 executor GRANT 를 통째로 건너뛰고, 그 사실은 <b>그 테넌트가 파이프라인을 처음 돌릴 때까지</b> 아무
   * 데도 드러나지 않는다 (2026-09-17 운영 장애). 그래서 롤이 먼저다.
   *
   * <p><b>외부 저장소(Tibero) 선택(WD-73).</b> {@code setUp} 한 번으로 저장소 설정과 암호화한 계정 비밀번호를 같은 트랜잭션에 저장하고,
   * DBA_AUTO 면 고객 DB 의 실제 계정까지 만든다. 이 호출은 감사와 <b>파이프라인 롤 생성 앞</b>에 둔다. 둘 다 이 트랜잭션으로 되돌릴 수 없는 부수
   * 효과지만 외부 계정은 롤백 보상({@code afterCompletion(STATUS_ROLLED_BACK)} DROP)이 있고 PG 롤은 없다. 그래서 흔히 실패하는
   * 쪽(DBA 비밀번호 오입력·접속 불가)인 외부 생성을 먼저 두어, 그 실패가 롤을 만들기 전에 터지게 한다 — 반대 순서면 재시도마다 고아 롤 11개가 쌓인다. (계획서
   * R3 는 "외부 생성은 롤 생성 뒤 맨 마지막"이었으나 이 이유로 뒤집었다.) 계획·비밀번호·DBA 자격은 이 서비스로 넘어오지 않고, 감사 details 와 SCRIPT
   * 스크립트만 돌려받는다.
   *
   * <p>실패 시나리오(아래 (a)/(a')/(b) 는 롤 생성 주석 참고)를 순서대로 보면:
   *
   * <ul>
   *   <li>(c) 외부 생성 중간 실패 → 실행기가 이번에 만든 계정만 즉시 보상 DROP 하고 예외를 던져 전체가 롤백된다. 감사·롤 생성에 닿지 않으므로 PG 롤도
   *       남지 않는다. 같은 이름의 계정이 이미 있으면 아무것도 만들지 않고 실패한다.
   *   <li>(d) 외부 생성은 성공했는데 감사가 실패 → 예외 전파로 롤백되고 {@code afterCompletion(STATUS_ROLLED_BACK)} 이 외부
   *       계정을 DBA 자격(메모리에만 존재)으로 best-effort DROP 한다. 롤 생성 전이라 PG 롤은 남지 않는다.
   *   <li>(e) 외부 생성·감사는 성공했는데 롤 생성이 실패((a)/(a')) → (d) 와 같이 롤백·보상 DROP 된다. (a') 이면 실행 롤 하나는 여전히 고아로
   *       남는다.
   *   <li>(f) 외부 생성·롤 생성이 모두 성공했는데 그 뒤(findById·커밋)가 실패 → (b) 의 고아 롤이 남고, 외부 계정은 (d) 와 같이 보상 DROP
   *       된다. 커밋 결과를 알 수 없으면({@code STATUS_UNKNOWN}) 지우지 않고 계정 이름만 ERROR 로 남긴다.
   * </ul>
   *
   * <p>DROP 이 실패한 계정은 ERROR 로그에 이름만 남는다(비밀번호 없음). SCRIPT 모드는 외부 접속이 없어 위 (c)~(f) 의 외부 부분이 없다.
   */
  @Transactional
  public TenantCreatedResponse create(CreateTenantRequest request, long operatorId) {
    // 사용자 존재 확인은 기존 UserRepository 를 쓴다 — 같은 전역 테이블에 조회를 하나 더 만들면
    // 나중에 사용자 조회 규칙이 바뀔 때 이쪽만 남는다.
    UserResponse owner =
        userRepository
            .findById(request.ownerUserId())
            // Owner 없는 테넌트는 아무도 들어갈 수 없다 — 만들기 전에 막는다.
            .orElseThrow(
                () -> new IllegalArgumentException("존재하지 않는 사용자입니다: " + request.ownerUserId()));
    if (!owner.isActive()) {
      // 비활성 계정은 로그인할 수 없다 — Owner 로 두면 아무도 들어갈 수 없는 테넌트가 만들어진다(WD-316).
      // 화면의 Owner 검색은 비활성 계정을 빼지만, 고른 뒤 비활성화되는 경합·API 직접 호출은 서버만 막을 수 있다.
      throw new IllegalArgumentException(
          "비활성화된 계정은 초기 Owner 로 지정할 수 없습니다. 계정을 다시 활성화하거나 다른 사용자를 선택하세요: " + owner.username());
    }
    if (tenantRepository.slugExists(request.slug())) {
      // UNIQUE 위반을 500 으로 흘리지 않고 400 으로 알려준다.
      throw new IllegalArgumentException("이미 사용 중인 식별자입니다: " + request.slug());
    }
    // 저장소 선택 검증은 테넌트 행을 만들기 전에 한다 — 사용할 수 없는 백엔드·필수값 누락이면 아무것도 만들지 않는다.
    dataStoreSetupService.validate(request.dataStore());

    long tenantId = tenantRepository.insertTenant(request.slug(), request.name());
    // 순서 의존: provisionDefaults 는 역할을 정의한 뒤 OWNER 멤버십을 읽어 소유자에게 ADMIN 을
    // 배정한다(V121). 멤버십 삽입이 먼저여야 소유자가 권한 0개로 태어나지 않는다.
    tenantRepository.insertOwnerMembership(tenantId, request.ownerUserId());
    provisioningService.provisionDefaults(tenantId);
    // 저장소 행 저장 + 외부 계정 생성(DBA_AUTO). 롤 생성보다 먼저다 — 롤백 보상이 있는 쪽이 먼저 실패해야 고아 롤이 남지 않는다
    // (위 Javadoc (c)). SCRIPT 모드면 외부 접속 없이 스크립트만 돌려받는다. 리졸버 캐시는 커밋 뒤 setUp 이 무효화하므로
    // 순서가 정확성을 좌우하지는 않지만, 백엔드를 해석할 수 있는 호출보다 행을 먼저 넣는 편이 낫다.
    SetupOutcome dataStore = dataStoreSetupService.setUp(tenantId, request.dataStore());
    // WD-12: 생성 감사(tenant NULL). 롤 생성(별도 커밋)보다 앞에 둔다 — 뒤에 두면 감사 실패가 (b) 고아 롤 창을 넓힌다.
    // 같은 트랜잭션이라 감사가 실패하면 생성도 롤백되고, 외부 계정은 보상 DROP 된다(위 Javadoc (d)).
    Map<String, Object> auditDetails = new LinkedHashMap<>();
    auditDetails.put("tenantSlug", request.slug());
    auditDetails.put("tenantName", request.name());
    // 외부 저장소일 때만 백엔드·호스트·모드가 붙는다(비밀 제외).
    auditDetails.putAll(dataStore.auditDetails());
    auditRecorder.record(
        operatorId,
        "TENANT_CREATE",
        "tenant",
        String.valueOf(tenantId),
        "테넌트 생성(소유자 userId " + request.ownerUserId() + ")",
        auditDetails);
    // DB 롤 생성은 소유자 커넥션이라 이 트랜잭션에 묶이지 않는다 — 그래서 **일부러 마지막**이다(보상이 있는 외부 생성만 앞에 둔다).
    //
    // 롤 생성은 **커밋 단위 두 개**다 — 실행 롤(ensureRole) 한 트랜잭션, 그 뒤 PYTHON 읽기 슬롯 롤 10개
    // (ensurePythonReadRoles, WD-29) 한 트랜잭션. 실패 세 가지를 구분할 것:
    //  (a) 실행 롤 생성이 실패 → 그 트랜잭션이 롤백돼 **롤은 남지 않고**, 예외가 전파돼 위의
    //      tenant/membership 삽입도 롤백된다. 아무것도 남지 않으므로 같은 slug 로 재시도하면 된다.
    //  (a') 실행 롤은 커밋됐는데 슬롯 롤 생성이 실패 → 슬롯 롤은 하나도 남지 않지만(10개가 한 트랜잭션)
    //      실행 롤은 고아로 남고, 예외 전파로 tenant/membership 은 롤백된다. 재시도는 멱등이라 같은 slug 로 된다.
    //  (b) 두 롤 생성은 커밋됐는데 그 **뒤**가 실패(아래 findById, 또는 커밋 자체) → 존재하지 않을
    //      테넌트 id 의 고아 롤(실행 롤 + 슬롯 롤 10개)이 남는다. 무해하고 멱등이지만 남기는 남는다.
    // 이 호출을 마지막에 두는 이유가 바로 (b) 의 창을 최소화하는 것이다 — 앞에 두면 뒤따르는
    // 모든 단계의 실패가 전부 (b) 가 된다. 반대로 순서를 뒤집어 롤을 나중에 "언젠가" 만들게 하면
    // "행은 있고 롤은 없는" 테넌트 2와 똑같은 상태가 커밋과 함께 확정된다 — 그것이 이 장애다.
    pipelineRoleProvisioner.ensureRoleIfAutoProvisionEnabled(tenantId);

    TenantSummaryResponse tenant =
        tenantRepository
            .findById(tenantId)
            .orElseThrow(() -> new IllegalStateException("생성한 테넌트를 다시 읽지 못했다: " + tenantId));
    return new TenantCreatedResponse(tenant, dataStore.setupScript().orElse(null));
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
   * <p>백그라운드 실행(예약·연쇄·데이터 변경·외부 API·웹훅 트리거, 예약·이상 탐지 스마트 작업)은 다음 발화부터 즉시 멈춘다(WD-174) — 각 발화 시점이 테넌트
   * 상태를 다시 본다. 이미 시작된 실행은 끝까지 돈다.
   *
   * <p><b>로그인 반영은 즉시가 아니다.</b> 정지는 {@code select-tenant}/{@code refresh} 에서 재검증되므로 이미 발급된 액세스 토큰은
   * 만료(기본 30분)까지 유효하다. 즉시 차단이 필요하면 별도 조치가 필요하며 이 밴드 범위 밖이다 — 런북에 명시한다.
   *
   * <p>예외로 <b>알림 스트림(SSE)은 즉시 끊는다</b>(WD-190): 커밋 뒤 이 워크스페이스로 연 연결을 모두 닫고, 재구독은 구독 엔드포인트가 워크스페이스
   * 상태를 보고 403 으로 거절한다. 열린 연결은 토큰 만료와 무관하게 최대 1시간 유지돼 정지된 워크스페이스의 알림이 계속 갔기 때문이다.
   */
  @Transactional
  public void suspend(long tenantId, long operatorId) {
    changeStatus(tenantId, "SUSPENDED", "TENANT_SUSPEND", "테넌트 정지", operatorId);
  }

  /** 테넌트를 다시 활성화한다. 정지와 같은 상태 스위치의 반대 방향이다. */
  @Transactional
  public void activate(long tenantId, long operatorId) {
    changeStatus(tenantId, "ACTIVE", "TENANT_ACTIVATE", "테넌트 활성화", operatorId);
  }

  /**
   * 상태 스위치 + 감사(WD-12). updateStatus 는 같은 상태여도 1 을 돌려주므로 먼저 읽어 무변화면 아무것도 하지 않는다 — 같은 요청 재전송이 감사 로그를
   * 부풀리지 않게(계정 조치와 같은 규칙).
   */
  private void changeStatus(
      long tenantId, String status, String action, String description, long operatorId) {
    TenantSummaryResponse tenant =
        tenantRepository.findById(tenantId).orElseThrow(() -> notFound(tenantId));
    if (status.equals(tenant.status())) {
      return;
    }
    tenantRepository.updateStatus(tenantId, status);
    auditRecorder.record(
        operatorId,
        action,
        "tenant",
        String.valueOf(tenantId),
        description,
        Map.of("tenantSlug", tenant.slug(), "tenantName", tenant.name()));
    if ("ACTIVE".equals(status)) {
      // 재개 시 파이프라인 롤(실행 롤 + PYTHON 읽기 슬롯 롤)을 보장한다(WD-29). V137·기동 치유는 ACTIVE 테넌트만
      // 돌므로, 그때 정지 상태였던 테넌트는 슬롯 롤 없이 재개되고 PYTHON 이 "롤이 준비되지 않았습니다"로 전부 거부된다
      // (fail-closed 지만 가용성 결함). 멱등이고, 생성과 같은 이유로 감사 뒤 마지막에 두며 실패는 전파한다 — 삼키면
      // "ACTIVE 인데 롤 없음"이 커밋으로 확정되고, 전파하면 상태 변경이 롤백돼 재시도할 수 있다.
      pipelineRoleProvisioner.ensureRoleIfAutoProvisionEnabled(tenantId);
      // 예약 재등록(WD-174) — 기동 재등록은 ACTIVE 테넌트만 돌아, 정지 중 재기동이 있었다면 이 워크스페이스의 예약이 하나도 걸려 있지 않다. 커밋
      // 뒤에 등록한다(롤백되면 정지 상태 그대로이므로 걸면 안 된다). 이미 걸려 있으면 바꿔 끼울 뿐 중복되지 않는다.
      scheduleResumer.resumeAfterCommit(tenantId);
    }
    if (!"ACTIVE".equals(status)) {
      // 정지된 워크스페이스로 연 알림 연결을 모두 닫는다(WD-190). 커밋 뒤에 닫는다 — 롤백되면 여전히 ACTIVE 라 끊으면 안 된다.
      // 재활성(ACTIVE)은 닫을 이유가 없고, 정지 중 거절당한 웹은 새로고침·재로그인으로 다시 붙는다.
      AfterCommitRunner.run(() -> sseEmitterRegistry.closeTenant(tenantId));
    }
    // 정지 쪽은 등록된 예약을 그대로 둔다(WD-174). 발화 시점 관문(트리거 발화·스마트 작업 예약 콜백)이 정지 워크스페이스의 실행을 막는다.
    // 스케줄 맵이 트리거·작업 id 만 키로 들고 있어 테넌트 단위로 걷어 낼 근거가 없다.
  }

  private TenantNotFoundException notFound(long tenantId) {
    return new TenantNotFoundException("테넌트를 찾을 수 없습니다: " + tenantId);
  }
}
