package com.smartfirehub.pipeline.service;

import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.dataset.storage.tibero.TiberoNames;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import org.springframework.stereotype.Component;

/**
 * 현재 테넌트의 "파이프라인 SQL 이 쓰는 데이터 스키마"를 테넌트 백엔드에 따라 고르는 단일 지점(WD-77, 스펙 §5.0).
 *
 * <p>왜 한 곳인가: 사용자 SQL 은 테넌트의 <b>실제</b> 스키마를 붙여 쓴다(PG 는 {@code data}/{@code data_t{id}}, Tibero 는
 * {@code GENIA_T{id}}). 그 스키마를 검증기({@code SqlValidator})·보안 게이트({@code DatasetAccessGuard})·{@code
 * {{#N}}} 치환({@code PipelineAsyncRunner})·저장 시점 검증({@code PipelineService})이 각자 고르면 한쪽만 PG 값을 쓰는 순간
 * 판정한 스키마와 실행하는 스키마가 갈린다. 그래서 넷이 이 빈 하나를 함께 쓴다.
 *
 * <p>PG 테넌트의 값은 기존 {@link DataSchema} 와 바이트 단위로 같다 — PG 동작은 바뀌지 않는다. 백엔드는 {@link
 * DataStoreResolver#currentBackend()}(테넌트별 캐시, 테넌트 생성 때 고정)로 정하므로 테넌트 컨텍스트 안에서만 부른다.
 */
@Component
public class PipelineDataSchema {

  private final DataStoreResolver dataStoreResolver;

  /** PG 테넌트용 기본 검증기(스프링 빈) — 허용 스키마를 검증 시점에 {@link DataSchema#current()} 로 해석한다. */
  private final SqlValidator pgValidator;

  public PipelineDataSchema(DataStoreResolver dataStoreResolver, SqlValidator pgValidator) {
    this.dataStoreResolver = dataStoreResolver;
    this.pgValidator = pgValidator;
  }

  /** 현재 테넌트의 저장소가 Tibero 인가. */
  public boolean isTibero() {
    return dataStoreResolver.currentBackend() == DataStoreBackend.TIBERO;
  }

  /**
   * 현재 테넌트의 데이터 스키마 이름. PG 는 {@link DataSchema#current()}, Tibero 는 테넌트 OWNER 계정 스키마 {@code
   * GENIA_T{id}}({@link DataStoreAccountRole#usernameFor} 규칙)다.
   */
  public String current() {
    if (!isTibero()) {
      return DataSchema.current();
    }
    return DataStoreAccountRole.OWNER.usernameFor(TenantContext.require("Tibero 데이터 스키마 해석"));
  }

  /**
   * 메타데이터 테이블 이름을 현재 테넌트 스키마로 한정한다. PG 는 {@link DataSchema#qualify}(예: {@code data_t5."orders"})
   * 그대로, Tibero 는 스키마·테이블 둘 다 인용한 대문자 물리 이름({@code "GENIA_T5"."ORDERS"}, {@link
   * TiberoNames#table})이다.
   */
  public String qualify(String metadataTableName) {
    if (!isTibero()) {
      return DataSchema.qualify(metadataTableName);
    }
    return TiberoNames.table(current(), metadataTableName);
  }

  /**
   * 현재 테넌트의 파이프라인 SQL 검증기. PG 는 주입된 기본 빈 그대로, Tibero 는 {@link SqlValidator#forTiberoSchema}(테넌트
   * 스키마·대문자 접기·DUAL 예외·Tibero 가드).
   */
  public SqlValidator validator() {
    if (!isTibero()) {
      return pgValidator;
    }
    return SqlValidator.forTiberoSchema(current());
  }
}
