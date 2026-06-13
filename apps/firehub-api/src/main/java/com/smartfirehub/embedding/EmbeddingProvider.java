package com.smartfirehub.embedding;

import java.util.List;

/** 텍스트 배치를 벡터로 변환하는 임베딩 provider 추상화. 구현 교체로 provider 전환. */
public interface EmbeddingProvider {
  /** 입력 텍스트들을 같은 순서의 임베딩 벡터로 변환한다. */
  List<float[]> embed(List<String> texts);

  /** 청크에 기록할 모델 식별자 (예: "bge-m3"). */
  String modelId();

  /** 생성 벡터 차원 (pgvector 컬럼 차원과 일치해야 함). */
  int dimension();
}
