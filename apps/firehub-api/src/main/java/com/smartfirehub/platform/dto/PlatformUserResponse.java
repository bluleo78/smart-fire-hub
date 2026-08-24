package com.smartfirehub.platform.dto;

/**
 * 운영자 평면 사용자 검색 결과 1건.
 *
 * <p><b>필드가 셋뿐인 것은 의도다.</b> 이 엔드포인트의 목적은 테넌트 생성 화면에서 초기 Owner 를
 * 고르는 것 하나이고, 그 이상을 실으면 "전 사용자 열거"의 표면이 넓어진다. 활성 여부·가입일·
 * 아이디(username)는 운영자가 Owner 를 고르는 데 필요하지 않다.
 *
 * <p>{@code email} 은 DDL 상 nullable 이다.
 */
public record PlatformUserResponse(Long id, String email, String name) {}
