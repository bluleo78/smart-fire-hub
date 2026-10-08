package com.smartfirehub.platform.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.smartfirehub.audit.time.StorageZoneDateTimeSerializer;
import java.time.LocalDateTime;

/**
 * 운영자 콘솔 "계정" 화면의 한 줄(#784, WD-47 목록화) — 계정 생성(WD-46) 응답도 같은 DTO 다.
 *
 * <p>{@link PlatformUserResponse}(Owner 선택용·3필드·활성만)와 분리한 이유: 이 화면은 비활성 계정을 찾아 재활성화해야 하므로 비활성도 결과에
 * 나와야 하고 활성 여부가 필요하다. 하나로 합치면 Owner 검색이 비활성 계정을 Owner 로 고를 수 있게 된다(아무도 못 들어가는 테넌트).
 *
 * @param operator 플랫폼 롤 보유 여부 — 운영자 계정은 이 화면에서 비활성화하지 않으므로(잠금 방지) 화면이 버튼을 막는 데 쓴다. 서버도 409 로 거부한다.
 * @param membershipCount 소속 워크스페이스 수(멤버십 ACTIVE + SUSPENDED, WD-47). 0 이면 화면이 "미소속" 으로 보인다 — 근거는
 *     PlatformUserRepository#findAccounts 주석
 * @param createdAt user.created_at(저장 TZ 벽시계) — JSON 에는 저장 TZ 오프셋이 붙는다(WD-11, 테넌트 createdAt 과 같은
 *     직렬화)
 */
public record PlatformAccountResponse(
    Long id,
    String username,
    String email,
    String name,
    boolean active,
    boolean operator,
    int membershipCount,
    @JsonSerialize(using = StorageZoneDateTimeSerializer.class) LocalDateTime createdAt) {}
