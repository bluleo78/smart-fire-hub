package com.smartfirehub.platform.dto;

/**
 * 운영자 콘솔 "계정" 화면의 검색 결과 1건(#784).
 *
 * <p>{@link PlatformUserResponse}(Owner 선택용·3필드·활성만)와 분리한 이유: 이 화면은 비활성 계정을 찾아 재활성화해야 하므로 비활성도 결과에
 * 나와야 하고 활성 여부가 필요하다. 하나로 합치면 Owner 검색이 비활성 계정을 Owner 로 고를 수 있게 된다(아무도 못 들어가는 테넌트).
 *
 * @param operator 플랫폼 롤 보유 여부 — 운영자 계정은 이 화면에서 비활성화하지 않으므로(잠금 방지) 화면이 버튼을 막는 데 쓴다. 서버도 409 로 거부한다.
 */
public record PlatformAccountResponse(
    Long id, String username, String email, String name, boolean active, boolean operator) {}
