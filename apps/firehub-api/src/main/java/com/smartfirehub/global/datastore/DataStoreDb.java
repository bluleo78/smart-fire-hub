package com.smartfirehub.global.datastore;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * PG 데이터 저장소(사용자 데이터셋 테이블·테넌트 스키마·사용자 SQL·파이프라인 롤)에 붙는 DataSource·DSLContext 를 가리키는 한정자.
 *
 * <p>왜: 플랫폼 DB(@Primary)와 데이터 저장소 연결을 분리해, Tibero 플랫폼 DB 설치본이 PG 데이터 저장소를 따로 쓸 수 있게 한다(WD-494). PG
 * 설치본에서는 {@code @Primary} 빈의 별칭이라 동작이 같다.
 */
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Qualifier
public @interface DataStoreDb {}
