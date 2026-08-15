package com.smartfirehub.global.security;

import com.smartfirehub.permission.repository.PermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PermissionChecker {

  private final PermissionRepository permissionRepository;

  /**
   * 사용자가 특정 권한 코드를 갖는지 검사한다.
   *
   * <p>자체 트랜잭션이 필요한 이유: 유일한 프로덕션 호출자가 {@code PipelineAsyncRunner} 의
   * {@code @Async("pipelineExecutor")} 스레드다. 앰비언트 트랜잭션이 없어 GUC 가 주입되지
   * 않으면 RLS 아래에서 권한 목록이 조용히 0개가 되고, 호출자는 그것을 "권한 없음"으로
   * 해석해 파이프라인 스텝을 거부한다.
   */
  @Transactional(readOnly = true)
  public boolean hasPermission(Long userId, String permissionCode) {
    return permissionRepository.findPermissionCodesByUserId(userId).contains(permissionCode);
  }
}
