package com.smartfirehub.global.platformdb;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 플랫폼 DB 가 PostgreSQL 일 때만 등록하는 빈 표시(WD-496).
 *
 * <p>{@value PlatformDbType#PROPERTY} 가 없으면 PostgreSQL 로 본다 — 기존 설치본·테스트는 설정을 바꾸지 않아도 지금과 똑같이 뜬다.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(
    name = PlatformDbType.PROPERTY,
    havingValue = "postgres",
    matchIfMissing = true)
public @interface ConditionalOnPostgresPlatformDb {}
