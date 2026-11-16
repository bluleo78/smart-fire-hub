package com.smartfirehub.platform.dto;

/**
 * 운영자 콘솔 최초 가입(부트스트랩) 가능 여부.
 *
 * <p>사용자가 한 명도 없을 때만 {@code available=true} 다. 로그인 전 화면이 "첫 운영자 만들기" 폼을 보여 줄지 정하는 데 쓴다.
 */
public record PlatformBootstrapStatusResponse(boolean available) {}
