package com.smartfirehub.permission.service;

import com.smartfirehub.permission.dto.PermissionResponse;
import com.smartfirehub.permission.repository.PermissionRepository;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PermissionService {

  private final PermissionRepository permissionRepository;

  @Transactional(readOnly = true)
  public List<PermissionResponse> getAllPermissions() {
    return permissionRepository.findAll();
  }

  @Transactional(readOnly = true)
  public List<PermissionResponse> getPermissionsByCategory(String category) {
    return permissionRepository.findByCategory(category);
  }

  @Transactional(readOnly = true)
  public Set<String> getUserPermissions(Long userId) {
    return permissionRepository.findPermissionCodesByUserId(userId);
  }

  /**
   * 이 테넌트에서 권한 {@code permissionCode} 를 가진 ACTIVE 멤버(계정 활성) id 집합(WD-187 — 권한이 필요한 알림의 수신자 선정).
   *
   * <p>자체 읽기 트랜잭션이 필요한 이유: 호출자(API 연결 헬스체크 스케줄러)는 앰비언트 트랜잭션이 없다. RLS GUC 는 트랜잭션 시작 시점에만 주입되므로 트랜잭션
   * 없이 조회하면 user_role 이 0행이 되어 「받을 사람 없음」으로 조용히 끝난다.
   */
  @Transactional(readOnly = true)
  public Set<Long> getActiveMemberIdsWithPermission(long tenantId, String permissionCode) {
    return permissionRepository.findActiveMemberIdsWithPermission(tenantId, permissionCode);
  }
}
