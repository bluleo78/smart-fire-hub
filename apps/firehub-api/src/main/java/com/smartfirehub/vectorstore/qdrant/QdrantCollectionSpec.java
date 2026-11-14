package com.smartfirehub.vectorstore.qdrant;

import static io.qdrant.client.ValueFactory.value;

import com.smartfirehub.embedding.EmbeddingDimension;
import io.qdrant.client.grpc.Collections.CollectionConfig;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.KeywordIndexParams;
import io.qdrant.client.grpc.Collections.Memory;
import io.qdrant.client.grpc.Collections.Modifier;
import io.qdrant.client.grpc.Collections.PayloadIndexParams;
import io.qdrant.client.grpc.Collections.PayloadSchemaInfo;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.QuantizationConfig;
import io.qdrant.client.grpc.Collections.QuantizationType;
import io.qdrant.client.grpc.Collections.ScalarQuantization;
import io.qdrant.client.grpc.Collections.SparseVectorConfig;
import io.qdrant.client.grpc.Collections.SparseVectorParams;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorParamsMap;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import io.qdrant.client.grpc.JsonWithInt.Value;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Qdrant 컬렉션 하나의 기대 구성(ADR 0002 결정 7). 만들 요청과 기존 컬렉션 대조를 한곳에서 정의해 둘이 어긋나지 않게 한다.
 *
 * <p><b>스키마 버전:</b> named vector·payload 색인·양자화 같은 구성을 바꾸면 {@link #SCHEMA_VERSION} 을 올린다. 버전은 컬렉션
 * metadata 에 기록하고 기동 때 대조한다. 다만 버전만 믿지 않고 실제 구성도 하나하나 대조한다 — 버전 올리기를 잊어도 어긋남을 잡기 위해서다.
 *
 * <p>키워드 바이그램 생성 규칙의 버전({@code BigramSparseEncoder#RULE_VERSION})은 여기에 넣지 않는다. 규칙이 바뀌면 컬렉션은 그대로 두고
 * 키워드 표시를 비워 백로그로 다시 만든다(결정 8).
 *
 * <p><b>카탈로그·청크는 dense 와 키워드를 다른 컬렉션에 둔다</b>(2026-11-14 사용자 결정, WD-720). 의미 컬렉션 {@code
 * fh_datasets_{dim}}·{@code fh_chunks_{dim}} 에는 dense 만, 키워드 컬렉션 {@code fh_datasets_kw}·{@code
 * fh_chunks_kw} 에는 sparse 만 있다. 키워드는 임베딩 차원과 무관해서 한 점에 함께 두면 임베딩 미설정 테넌트의 둘 곳이 없고, 차원을 바꿀 때 키워드도
 * 옮겨야 하고, 두 쓰기가 같은 점을 두고 경합한다. 행 검색 컬렉션의 구성은 WD-722 에서 정한다.
 *
 * @param name 컬렉션 이름
 * @param kind 컬렉션 종류(metadata {@code fh_kind}). 이름이 같고 종류가 다른 컬렉션을 잘못 쓰지 않게 대조한다.
 * @param denseDim dense 차원. {@code null} 이면 dense 가 없다.
 * @param keyword 키워드 sparse({@code kw_bigram})를 두는가
 * @param payload 만들 payload 색인
 */
public record QdrantCollectionSpec(
    String name, String kind, Integer denseDim, boolean keyword, List<PayloadField> payload) {

  /** 컬렉션 구성 버전. 구성을 바꾸면 올린다. */
  public static final int SCHEMA_VERSION = 1;

  /** 임베딩 dense named vector. */
  public static final String DENSE = "dense";

  /** 음절 바이그램 sparse named vector(결정 8). */
  public static final String KW_BIGRAM = "kw_bigram";

  /** Nori 형태소 sparse 로 예약만 한 이름(결정 8). 1차에는 만들지 않는다. */
  public static final String KW_NORI = "kw_nori";

  static final String META_SCHEMA_VERSION = "fh_schema_version";
  static final String META_KIND = "fh_kind";

  /** 공통 payload 이름. 서비스·어댑터가 같은 이름을 쓰도록 여기 둔다. */
  public static final String TENANT_ID = "tenant_id";

  public static final String DATASET_ID = "dataset_id";
  public static final String DOCUMENT_ID = "document_id";

  /** 청크 점의 청크 id — 사후 확인에서 점을 청크로 되짚으려고 싣는다. 거르지 않으므로 색인하지 않는다(WD-720). */
  public static final String CHUNK_ID = "chunk_id";

  public static final String MODEL = "model";
  public static final String SEQ = "seq";
  public static final String ROW_ID = "row_id";
  public static final String CHUNK_NO = "chunk_no";
  public static final String SOURCE_HASH = "source_hash";
  public static final String GENERATION = "generation";

  /** 키워드 전용 행 검색 컬렉션 이름(결정 7). */
  public static final String ROWS_KEYWORD_COLLECTION = "fh_rows_kw";

  /** 데이터셋 카탈로그 키워드 컬렉션 이름(WD-720, 차원과 무관). */
  public static final String DATASETS_KEYWORD_COLLECTION = "fh_datasets_kw";

  /** 문서 청크 키워드 컬렉션 이름(WD-720, 차원과 무관). */
  public static final String CHUNKS_KEYWORD_COLLECTION = "fh_chunks_kw";

  /**
   * payload 색인 하나.
   *
   * <p>{@code tenant_id} 는 <b>키워드형</b>으로 둔다. Qdrant 1.20 은 정수형 색인에 준 {@code is_tenant} 를 오류 없이
   * 버리고(실측 2026-11-13), 키워드형에서만 테넌트별 저장 배치 최적화를 켠다. 그래서 payload 값도 문자열({@code "12"})로 싣는다.
   *
   * @param tenant 테넌트 키 최적화({@code is_tenant}). 키워드형에서만 뜻이 있다.
   */
  public record PayloadField(String name, PayloadSchemaType type, boolean tenant) {
    static PayloadField keyword(String name) {
      return new PayloadField(name, PayloadSchemaType.Keyword, false);
    }

    static PayloadField integer(String name) {
      return new PayloadField(name, PayloadSchemaType.Integer, false);
    }

    static PayloadField tenantKey() {
      return new PayloadField(TENANT_ID, PayloadSchemaType.Keyword, true);
    }

    /** 색인을 만들 때 넘길 파라미터. 테넌트 키만 따로 준다(그 밖은 기본값). */
    PayloadIndexParams params() {
      if (!tenant) {
        return null;
      }
      return PayloadIndexParams.newBuilder()
          .setKeywordIndexParams(KeywordIndexParams.newBuilder().setIsTenant(true))
          .build();
    }
  }

  /** 데이터셋 카탈로그 의미 컬렉션({@code fh_datasets_{dim}}) — dense 만. */
  public static QdrantCollectionSpec datasets(EmbeddingDimension dim) {
    return new QdrantCollectionSpec(
        dim.datasetCollection(),
        "datasets",
        dim.size(),
        false,
        List.of(
            PayloadField.tenantKey(),
            PayloadField.integer(DATASET_ID),
            PayloadField.keyword(MODEL),
            PayloadField.integer(SEQ)));
  }

  /** 문서 청크 의미 컬렉션({@code fh_chunks_{dim}}) — dense 만. 문서 삭제·데이터셋 삭제 때 거를 수 있게 둘 다 색인한다. */
  public static QdrantCollectionSpec chunks(EmbeddingDimension dim) {
    return new QdrantCollectionSpec(
        dim.chunkCollection(),
        "chunks",
        dim.size(),
        false,
        List.of(
            PayloadField.tenantKey(),
            PayloadField.integer(DATASET_ID),
            PayloadField.integer(DOCUMENT_ID),
            PayloadField.keyword(MODEL),
            PayloadField.integer(SEQ)));
  }

  /** 데이터셋 카탈로그 키워드 컬렉션({@code fh_datasets_kw}) — sparse 만, 모델과 무관해 {@code model} 이 없다. */
  public static QdrantCollectionSpec datasetsKeyword() {
    return new QdrantCollectionSpec(
        DATASETS_KEYWORD_COLLECTION,
        "datasets_kw",
        null,
        true,
        List.of(
            PayloadField.tenantKey(), PayloadField.integer(DATASET_ID), PayloadField.integer(SEQ)));
  }

  /** 문서 청크 키워드 컬렉션({@code fh_chunks_kw}) — sparse 만. */
  public static QdrantCollectionSpec chunksKeyword() {
    return new QdrantCollectionSpec(
        CHUNKS_KEYWORD_COLLECTION,
        "chunks_kw",
        null,
        true,
        List.of(
            PayloadField.tenantKey(),
            PayloadField.integer(DATASET_ID),
            PayloadField.integer(DOCUMENT_ID),
            PayloadField.integer(SEQ)));
  }

  /**
   * 행 검색 컬렉션({@code fh_rows_{dim}}). 행 검색은 테넌트 공급자 차원을 그대로 쓰므로(상한 2000) 열거형이 아닌 정수를 받고, 필요할 때 만든다.
   */
  public static QdrantCollectionSpec rows(int dim) {
    if (dim < 2 || dim > 65_536) {
      // 1 은 키워드 전용 표시값(KEYWORD_ONLY_DIM)이라 rowsKeyword() 로 가야 한다. 상한은 Qdrant 차원 한도.
      throw new IllegalArgumentException("행 검색 dense 차원은 2~65536 이어야 합니다: " + dim);
    }
    // 행 검색은 아직 한 점에 dense 와 키워드를 함께 둔다 — 구성은 WD-722 에서 다시 정한다.
    return new QdrantCollectionSpec("fh_rows_" + dim, "rows", dim, true, rowFields(true));
  }

  /** 키워드 전용 행 검색 컬렉션({@code fh_rows_kw}) — dense 가 없다. */
  public static QdrantCollectionSpec rowsKeyword() {
    return new QdrantCollectionSpec(
        ROWS_KEYWORD_COLLECTION, "rows_kw", null, true, rowFields(false));
  }

  private static List<PayloadField> rowFields(boolean withModel) {
    List<PayloadField> fields = new ArrayList<>();
    fields.add(PayloadField.tenantKey());
    fields.add(PayloadField.integer(DATASET_ID));
    if (withModel) {
      fields.add(PayloadField.keyword(MODEL));
    }
    fields.add(PayloadField.integer(SEQ));
    fields.add(PayloadField.integer(ROW_ID));
    fields.add(PayloadField.integer(CHUNK_NO));
    fields.add(PayloadField.keyword(SOURCE_HASH));
    fields.add(PayloadField.integer(GENERATION));
    return List.copyOf(fields);
  }

  /** 컬렉션 생성 요청. payload 색인은 컬렉션이 생긴 뒤 따로 만든다(Qdrant API 가 나뉘어 있다). */
  public CreateCollection toCreateRequest(QdrantProperties props) {
    CreateCollection.Builder b =
        CreateCollection.newBuilder()
            .setCollectionName(name)
            .setShardNumber(props.shardNumber())
            .putAllMetadata(expectedMetadata());
    if (keyword) {
      b.setSparseVectorsConfig(expectedSparse());
    }
    if (denseDim != null) {
      b.setVectorsConfig(
          VectorsConfig.newBuilder()
              .setParamsMap(VectorParamsMap.newBuilder().putMap(DENSE, expectedDense(props))));
      QuantizationConfig q = expectedQuantization(props);
      if (q != null) {
        b.setQuantizationConfig(q);
      }
    }
    return b.build();
  }

  /**
   * 기존 컬렉션이 기대 구성과 다른 점을 찾는다. 비어 있으면 그대로 써도 된다.
   *
   * <p>payload 색인이 <b>빠진 것</b>은 {@link #missingPayloadFields} 가 따로 돌려준다 — 컬렉션만 만들고 색인을 만들기 전에 죽은
   * 경우를 다음 기동이 채우게 하려는 것이다. 이미 있는 색인의 종류가 다르면 여기서 어긋남으로 본다.
   */
  public List<String> mismatches(CollectionInfo info, QdrantProperties props) {
    List<String> out = new ArrayList<>();
    CollectionConfig config = info.getConfig();

    Map<String, Value> meta = config.getMetadataMap();
    Value version = meta.get(META_SCHEMA_VERSION);
    if (version == null
        || !version.hasIntegerValue()
        || version.getIntegerValue() != SCHEMA_VERSION) {
      out.add("스키마 버전 " + describe(version) + " ≠ 기대 " + SCHEMA_VERSION);
    }
    Value kindValue = meta.get(META_KIND);
    if (kindValue == null || !kind.equals(kindValue.getStringValue())) {
      out.add("종류 " + describe(kindValue) + " ≠ 기대 " + kind);
    }

    int shards = config.getParams().getShardNumber();
    if (shards != props.shardNumber()) {
      out.add("샤드 수 " + shards + " ≠ 설정 " + props.shardNumber() + "(샤드 수는 만든 뒤 바꿀 수 없다)");
    }

    Map<String, VectorParams> dense =
        config.getParams().getVectorsConfig().hasParamsMap()
            ? config.getParams().getVectorsConfig().getParamsMap().getMapMap()
            : Map.of();
    if (config.getParams().getVectorsConfig().hasParams()) {
      out.add("이름 없는 단일 dense 벡터로 만들어져 있다(named vector 여야 한다)");
    }
    if (denseDim == null) {
      if (!dense.isEmpty()) {
        out.add("dense 가 없어야 하는데 있다: " + dense.keySet());
      }
    } else {
      VectorParams actual = dense.get(DENSE);
      VectorParams expected = expectedDense(props);
      if (actual == null) {
        out.add("dense 벡터 '" + DENSE + "' 가 없다");
      } else {
        if (actual.getSize() != expected.getSize()) {
          out.add("dense 차원 " + actual.getSize() + " ≠ 기대 " + expected.getSize());
        }
        if (actual.getDistance() != expected.getDistance()) {
          out.add("dense 거리 " + actual.getDistance() + " ≠ 기대 " + expected.getDistance());
        }
        if (actual.getMemory() != expected.getMemory()) {
          out.add("dense 원본 위치 " + actual.getMemory() + " ≠ 기대 " + expected.getMemory());
        }
      }
      if (dense.size() > 1) {
        out.add("dense 벡터가 '" + DENSE + "' 밖에도 있다: " + dense.keySet());
      }
    }

    Map<String, SparseVectorParams> sparse =
        config.getParams().getSparseVectorsConfig().getMapMap();
    java.util.Set<String> expectedSparseKeys =
        keyword ? expectedSparse().getMapMap().keySet() : java.util.Set.of();
    if (!sparse.keySet().equals(expectedSparseKeys)) {
      out.add("sparse 벡터 " + sparse.keySet() + " ≠ 기대 " + expectedSparseKeys);
    } else if (keyword && sparse.get(KW_BIGRAM).getModifier() != Modifier.Idf) {
      out.add("sparse '" + KW_BIGRAM + "' 의 modifier 가 idf 가 아니다");
    }

    QuantizationConfig expectedQ = denseDim == null ? null : expectedQuantization(props);
    QuantizationConfig actualQ =
        config.hasQuantizationConfig() ? config.getQuantizationConfig() : null;
    if (!quantizationEquals(expectedQ, actualQ)) {
      out.add("양자화 " + oneLine(actualQ) + " ≠ 기대 " + oneLine(expectedQ));
    }

    Map<String, PayloadSchemaInfo> schema = info.getPayloadSchemaMap();
    for (PayloadField field : payload) {
      PayloadSchemaInfo actual = schema.get(field.name());
      if (actual == null) {
        continue; // 빠진 색인은 missingPayloadFields 가 채운다
      }
      if (actual.getDataType() != field.type()) {
        out.add(
            "payload '" + field.name() + "' 종류 " + actual.getDataType() + " ≠ 기대 " + field.type());
      } else if (field.tenant()
          && !(actual.hasParams()
              && actual.getParams().hasKeywordIndexParams()
              && actual.getParams().getKeywordIndexParams().getIsTenant())) {
        out.add("payload '" + field.name() + "' 에 is_tenant 가 없다");
      }
    }
    return out;
  }

  /** 기존 컬렉션에 아직 없는 payload 색인. */
  public List<PayloadField> missingPayloadFields(CollectionInfo info) {
    Map<String, PayloadSchemaInfo> schema = info.getPayloadSchemaMap();
    return payload.stream().filter(f -> !schema.containsKey(f.name())).toList();
  }

  private Map<String, Value> expectedMetadata() {
    return Map.of(META_SCHEMA_VERSION, value(SCHEMA_VERSION), META_KIND, value(kind));
  }

  /**
   * dense: 코사인(pgvector {@code vector_cosine_ops} 와 같다). 양자화를 켜면 원본은 디스크(cold)에 두고 검색은 메모리의 양자화본으로
   * 한다. 양자화를 끄면 원본을 기동 때 디스크 캐시로 미리 올린다(cached) — 원본이 검색 경로라서다. 위치를 늘 명시해야 대조가 서버 기본값 변화에 흔들리지 않는다.
   */
  private VectorParams expectedDense(QdrantProperties props) {
    return VectorParams.newBuilder()
        .setSize(denseDim)
        .setDistance(Distance.Cosine)
        .setMemory(props.quantization() ? Memory.Cold : Memory.Cached)
        .build();
  }

  private static SparseVectorConfig expectedSparse() {
    return SparseVectorConfig.newBuilder()
        .putMap(KW_BIGRAM, SparseVectorParams.newBuilder().setModifier(Modifier.Idf).build())
        .build();
  }

  /** int8 스칼라 양자화본을 메모리에 고정(pinned)한다. 검색 때 rescore 로 원본을 다시 본다(WD-720). */
  private static QuantizationConfig expectedQuantization(QdrantProperties props) {
    if (!props.quantization()) {
      return null;
    }
    return QuantizationConfig.newBuilder()
        .setScalar(
            ScalarQuantization.newBuilder().setType(QuantizationType.Int8).setMemory(Memory.Pinned))
        .build();
  }

  private static boolean quantizationEquals(
      QuantizationConfig expected, QuantizationConfig actual) {
    if (expected == null || actual == null) {
      return expected == null && actual == null;
    }
    if (!actual.hasScalar()) {
      return false;
    }
    ScalarQuantization e = expected.getScalar();
    ScalarQuantization a = actual.getScalar();
    return a.getType() == e.getType() && a.getMemory() == e.getMemory();
  }

  private static String oneLine(QuantizationConfig q) {
    return q == null ? "없음" : q.toString().replaceAll("\\s+", " ").trim();
  }

  private static String describe(Value v) {
    if (v == null) {
      return "없음";
    }
    return v.hasIntegerValue()
        ? String.valueOf(v.getIntegerValue())
        : v.hasStringValue() ? v.getStringValue() : v.toString().trim();
  }
}
