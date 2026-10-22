package com.smartfirehub.proactive.service.delivery;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 스마트 작업 리포트 수신자 해석(WD-172) — 설정에 저장된 사용자 id 가운데 <b>지금 받을 수 있는 사람</b>만 남긴다: 실행 중인 워크스페이스의 ACTIVE
 * 멤버이면서 계정도 활성인 사용자.
 *
 * <p>예전에는 채널이 저장된 id 를 그대로 쓰고(채팅) 전역 {@code user} 조회로 메일 주소를 찾아(이메일), 정지·제거된 멤버에게도 리포트가 메일·앱 알림으로
 * 계속 갔다. 저장된 id 는 건드리지 않는다 — 정지를 풀거나 다시 합류하면 다시 받는다. 거르는 것은 전달 시점뿐이다.
 */
@Component
@RequiredArgsConstructor
public class ActiveRecipientResolver {

  private final UserRepository userRepository;

  /** 지정 순서를 지키며(중복 제거) 받을 수 있는 사용자만 돌려준다. 실행 테넌트 컨텍스트 안에서 부른다. */
  public List<UserResponse> activeUsers(List<Long> userIds) {
    if (userIds == null || userIds.isEmpty()) {
      return List.of();
    }
    long tenantId = TenantContext.require("리포트 수신자 해석");
    // findByIds 는 이 테넌트의 ACTIVE 멤버만 돌려준다(정지·제거 제외) — 전역 계정 비활성은 여기서 한 번 더 거른다.
    Map<Long, UserResponse> receivable =
        userRepository.findByIds(tenantId, userIds).stream()
            .filter(UserResponse::isActive)
            .collect(Collectors.toMap(UserResponse::id, Function.identity()));
    return userIds.stream().distinct().map(receivable::get).filter(Objects::nonNull).toList();
  }
}
