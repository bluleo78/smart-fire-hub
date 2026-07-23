package com.smartfirehub.admin.embedding;

import java.util.List;

/**
 * 내부 임베딩 실행 응답.
 *
 * @param model 실제 사용한 모델 식별자(예: bge-m3, text-embedding-3-small)
 * @param dimension 벡터 차원(pgvector 컬럼과 일치, 1024)
 * @param embeddings 입력과 같은 순서의 임베딩 벡터
 */
public record EmbedResponse(String model, int dimension, List<float[]> embeddings) {}
