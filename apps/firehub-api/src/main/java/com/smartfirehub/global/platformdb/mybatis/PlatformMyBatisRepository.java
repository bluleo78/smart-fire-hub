package com.smartfirehub.global.platformdb.mybatis;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 플랫폼 리포지토리 인터페이스의 MyBatis 구현 표지(WD-706).
 *
 * <p>무엇을: {@link PlatformMyBatisRepositoryRegistrar} 가 이 애노테이션이 붙은 클래스를 빈으로 등록한다. 생성자 주입(매퍼·기타 빈)으로
 * 만들어지고 클래스의 {@code @Transactional} 프록시도 적용된다.
 *
 * <p>왜 {@code @Repository} 가 아닌가: 컴포넌트 스캔은 PG 운영 컨텍스트에서도 빈을 만든다. 이 구현은 Tibero 설치본({@code
 * firehub.platform-db.type=tibero}) 또는 테스트 프로필(MyBatis 배선 켬)에서만 등록돼야 하므로 등록기가 조건을 걸어 등록한다. 짝 jOOQ
 * 구현은 {@code @ConditionalOnPostgresPlatformDb} 를 달아 서로 배타적이다. 구현은 {@code ..repository.mybatis..}
 * 패키지에 둔다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PlatformMyBatisRepository {}
