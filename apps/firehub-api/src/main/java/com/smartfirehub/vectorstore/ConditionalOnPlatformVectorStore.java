package com.smartfirehub.vectorstore;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 벡터 저장소가 플랫폼 DB({@code platform})일 때만 빈을 등록하게 하는 표시(ADR 0002 결정 1).
 *
 * <p>{@value VectorStoreType#PROPERTY} 가 없으면 platform 으로 본다. 그래서 기존 설치본과 테스트는 설정을 바꾸지 않아도 지금과 똑같이
 * 뜬다. 플랫폼 DB 종류 조건({@code @ConditionalOnPostgresPlatformDb} 등)과 함께 달아 네 조합을 가른다.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(
    name = VectorStoreType.PROPERTY,
    havingValue = "platform",
    matchIfMissing = true)
public @interface ConditionalOnPlatformVectorStore {}
