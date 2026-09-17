package com.smartfirehub.platform;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.tenant.TenantPipelineRoleProvisioner;
import com.smartfirehub.global.tenant.TenantProvisioningService;
import com.smartfirehub.platform.dto.CreateTenantRequest;
import com.smartfirehub.platform.dto.TenantSummaryResponse;
import com.smartfirehub.platform.repository.PlatformTenantRepository;
import com.smartfirehub.platform.service.PlatformTenantService;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 테넌트 생성이 <b>파이프라인 실행 롤까지 만들고 끝나는지</b> 고정한다(#680).
 *
 * <p>DB 없이 배선만 본다 — 롤이 실제로 접속 가능해지는지는 {@code TenantPipelineRoleProvisionerTest}
 * 가 살아있는 DB 로 확인한다. 여기서 막으려는 회귀는 "프로비저너 호출 한 줄이 사라지는 것"이고,
 * 그 회귀는 통합 테스트로는 안 잡힌다 — test 프로필은 자동 프로비저닝이 꺼져 있어 호출이 있든
 * 없든 롤이 생기지 않기 때문이다.
 */
@ExtendWith(MockitoExtension.class)
class PlatformTenantServiceRoleProvisioningTest {

  @Mock PlatformTenantRepository tenantRepository;
  @Mock TenantProvisioningService provisioningService;
  @Mock UserRepository userRepository;
  @Mock TenantPipelineRoleProvisioner pipelineRoleProvisioner;

  @InjectMocks PlatformTenantService service;

  private static final CreateTenantRequest REQUEST = new CreateTenantRequest("jeonju", "전주시", 7L);

  /** 소유자 존재 확인만 통과하면 되므로 내용은 보지 않는다(레코드라 목이 아니라 실물을 쓴다). */
  private static final UserResponse OWNER =
      new UserResponse(7L, "owner", "owner@example.com", "소유자", true, LocalDateTime.now());

  private void givenValidRequest() {
    when(userRepository.findById(7L)).thenReturn(Optional.of(OWNER));
    when(tenantRepository.slugExists("jeonju")).thenReturn(false);
    when(tenantRepository.insertTenant("jeonju", "전주시")).thenReturn(42L);
  }

  @Test
  void create_provisionsPipelineRole_afterDefaults() {
    givenValidRequest();
    when(tenantRepository.findById(42L))
        .thenReturn(
            Optional.of(
                new TenantSummaryResponse(42L, "jeonju", "전주시", "ACTIVE", 1, LocalDateTime.now())));

    service.create(REQUEST);

    // 순서가 계약이다 — 롤 생성은 소유자 커넥션이라 이 트랜잭션에 묶이지 않고 독립적으로 커밋된다.
    // 마지막에 두어야 "롤은 커밋됐는데 뒤가 실패해 고아 롤이 남는" 창이 가장 좁다(상세는
    // PlatformTenantService.create 주석).
    InOrder order = inOrder(tenantRepository, provisioningService, pipelineRoleProvisioner);
    order.verify(tenantRepository).insertOwnerMembership(42L, 7L);
    order.verify(provisioningService).provisionDefaults(42L);
    order.verify(pipelineRoleProvisioner).ensureRoleIfAutoProvisionEnabled(42L);
  }

  /**
   * 롤 생성이 실패하면 예외가 그대로 전파돼야 한다 — 삼키면 "행은 있고 롤은 없는" 테넌트 2와
   * 똑같은 상태가 트랜잭션 커밋과 함께 확정된다. 전파되면 프로비저너 자신의 트랜잭션도, 이
   * {@code @Transactional} 의 tenant·membership 삽입도 모두 롤백되므로 아무것도 남지 않고 같은
   * slug 로 재시도할 수 있다.
   */
  @Test
  void create_propagatesRoleProvisioningFailure() {
    givenValidRequest();
    Mockito.doThrow(new IllegalStateException("CREATE ROLE 실패"))
        .when(pipelineRoleProvisioner)
        .ensureRoleIfAutoProvisionEnabled(42L);

    assertThatThrownBy(() -> service.create(REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CREATE ROLE 실패");
  }

  /** 입력 검증에서 막히면 DB 롤을 만들지 않는다 — 고아 롤을 남길 이유가 없다. */
  @Test
  void create_withUnknownOwner_doesNotTouchRoleProvisioner() {
    when(userRepository.findById(anyLong())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(REQUEST)).isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(pipelineRoleProvisioner);
    verify(tenantRepository, Mockito.never())
        .insertTenant(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
  }
}
