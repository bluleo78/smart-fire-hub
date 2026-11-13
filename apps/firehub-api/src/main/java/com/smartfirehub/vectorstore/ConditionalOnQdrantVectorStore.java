package com.smartfirehub.vectorstore;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 벡터 저장소가 Qdrant({@code qdrant})일 때만 빈을 등록하게 하는 표시(ADR 0002 결정 1).
 *
 * <p>{@link ConditionalOnPlatformVectorStore} 의 짝이다. 속성이 없으면 platform 이라 이 조건은 맞지 않는다. 플랫폼 DB 종류와
 * 무관하게 (PG·Tibero 모두) Qdrant 구현을 쓰므로 플랫폼 DB 조건은 함께 달지 않는다.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = VectorStoreType.PROPERTY, havingValue = "qdrant")
public @interface ConditionalOnQdrantVectorStore {}
