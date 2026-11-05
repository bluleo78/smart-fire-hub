package com.smartfirehub.global.platformdb.mybatis;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 플랫폼 DB MyBatis 매퍼 인터페이스 표지(WD-487).
 *
 * <p>무엇을: {@link MyBatisPlatformConfig} 의 {@code @MapperScan} 이 이 애노테이션이 붙은 인터페이스만 매퍼로 등록한다.
 *
 * <p>왜: 패키지 전체를 스캔하면 다른 인터페이스를 매퍼로 잘못 집는다. 매퍼는 {@code ..repository.mybatis..} 에 두고 이 표지를 단다.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PlatformMapper {}
