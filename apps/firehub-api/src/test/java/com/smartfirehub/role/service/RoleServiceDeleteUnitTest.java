package com.smartfirehub.role.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.permission.repository.PermissionRepository;
import com.smartfirehub.role.dto.RoleResponse;
import com.smartfirehub.role.exception.RoleNotFoundException;
import com.smartfirehub.role.repository.RoleRepository;
import com.smartfirehub.securitylevel.repository.DatasetAccessGrantRepository;
import com.smartfirehub.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link RoleService#deleteRole} 의 삭제 행 수 판정을 순수 단위 테스트로 검증한다(WD-582).
 *
 * <p>동시 삭제에서 진 요청은 사전 조회(findById)는 통과하지만 실제 삭제는 0행이다. 그 경로를 DB 경합 없이 결정적으로 재현하려고 리포지토리를 Mock 으로 두고
 * 0행을 돌려준다 — 실제 경합은 {@code
 * RoleAuditServiceTest#concurrentDeleteRole_onlyOneSucceeds_andWritesSingleAudit} 가 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class RoleServiceDeleteUnitTest {

  private static final long ROLE_ID = 42L;
  private static final long CALLER_ID = 7L;

  @Mock private RoleRepository roleRepository;
  @Mock private PermissionRepository permissionRepository;
  @Mock private DatasetAccessGrantRepository grantRepository;
  @Mock private UserRepository userRepository;
  @Mock private AuditLogService auditLogService;
  @Mock private RoleGrantGuard roleGrantGuard;

  @InjectMocks private RoleService roleService;

  /** 사전 검사를 모두 통과하는 사용자 정의 역할 — 삭제 행 수만 결과를 가르게 한다. */
  private void givenDeletableRole() {
    when(roleRepository.findById(ROLE_ID))
        .thenReturn(Optional.of(new RoleResponse(ROLE_ID, "TEMP", "임시", false)));
    when(grantRepository.datasetsWhereRoleIsSoleGrantOnAllowlistLevel(ROLE_ID))
        .thenReturn(List.of());
    when(permissionRepository.findByRoleId(ROLE_ID)).thenReturn(List.of());
    when(roleRepository.findUserIdsByRoleId(ROLE_ID)).thenReturn(List.of());
  }

  /** 0행 삭제(다른 요청이 먼저 지움)는 404 로 거부하고 ROLE_DELETE 감사를 남기지 않는다. */
  @Test
  void deleteRole_zeroRowsDeleted_throwsNotFound_andWritesNoAudit() {
    givenDeletableRole();
    when(roleRepository.deleteById(ROLE_ID)).thenReturn(0);

    assertThatThrownBy(() -> roleService.deleteRole(ROLE_ID, CALLER_ID))
        .isInstanceOf(RoleNotFoundException.class);

    verify(auditLogService, never())
        .log(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  /** 1행 삭제(이 요청이 지움)는 ROLE_DELETE 감사를 정확히 한 번 남긴다. */
  @Test
  void deleteRole_oneRowDeleted_writesSingleAudit() {
    givenDeletableRole();
    when(roleRepository.deleteById(ROLE_ID)).thenReturn(1);
    when(userRepository.findById(CALLER_ID)).thenReturn(Optional.empty());

    roleService.deleteRole(ROLE_ID, CALLER_ID);

    verify(auditLogService, times(1))
        .log(
            eq(CALLER_ID),
            anyString(),
            eq("ROLE_DELETE"),
            eq("role"),
            eq(String.valueOf(ROLE_ID)),
            anyString(),
            any(),
            any(),
            eq("SUCCESS"),
            any(),
            any());
    verify(roleGrantGuard).assertCanDeleteRole(anyLong(), anyLong());
  }
}
