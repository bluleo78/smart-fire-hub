package com.smartfirehub.pipeline.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.pipeline.repository.mybatis.TriggerRows.RefLookup;
import com.smartfirehub.pipeline.repository.mybatis.TriggerRows.RefRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 비인증 트리거 경로의 테넌트 해석 매퍼(WD-708, 스펙 D2). SQL 은 {@code
 * mybatis/pipeline/TriggerTenantResolverMapper.xml}.
 *
 * <p>해석 문장은 벤더마다 결과 자리가 다르다 — PG 는 반환 목록(trigger_id, tenant_id), Tibero 는 {@link
 * RefLookup#getTenantId()} OUT 인자(반환 목록은 비어 있다).
 */
@PlatformMapper
public interface TriggerTenantResolverMapper {

  /** API 토큰 해시 → (트리거, 테넌트) 또는 테넌트(정책 우회). */
  List<RefRow> resolveByTokenHash(@Param("q") RefLookup q);

  /** 웹훅 id → (트리거, 테넌트) 또는 테넌트(정책 우회). */
  List<RefRow> resolveByWebhookId(@Param("q") RefLookup q);

  /** 테넌트를 심은 트랜잭션에서 RLS 아래 API 트리거 id 를 다시 읽는다(Tibero 재조회). 없으면 null. */
  Long selectTriggerIdByTokenHash(@Param("tokenHash") String tokenHash);

  /** 테넌트를 심은 트랜잭션에서 RLS 아래 웹훅 트리거 id 를 다시 읽는다(Tibero 재조회). 없으면 null. */
  Long selectTriggerIdByWebhookId(@Param("webhookId") String webhookId);
}
