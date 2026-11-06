package com.smartfirehub.global.platformdb;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** 플랫폼 DB 가 Tibero 일 때만 등록하는 빈 표시(WD-496) — {@value PlatformDbType#PROPERTY}={@code tibero}. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = PlatformDbType.PROPERTY, havingValue = "tibero")
public @interface ConditionalOnTiberoPlatformDb {}
