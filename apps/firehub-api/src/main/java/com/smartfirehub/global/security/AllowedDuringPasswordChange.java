package com.smartfirehub.global.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 비밀번호 변경이 강제된 사용자(토큰 클레임 pwc)도 호출할 수 있는 핸들러 표식.
 *
 * <p>경로 문자열 목록 대신 핸들러 애너테이션을 쓰는 이유: {@code getRequestURI()} 기반 매칭은 퍼센트 인코딩 등으로 우회될 수
 * 있다(PlatformPlaneFilter Javadoc). 핸들러가 정해진 뒤 판정하면 그 문제가 없다. 정확한 부착 집합은
 * PasswordChangeGateTest.allowlist_isExactlyTheNineHandlers 가 고정한다 — 여기에 핸들러를 더하거나 빼면 그 테스트를 함께 고칠
 * 것.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AllowedDuringPasswordChange {}
