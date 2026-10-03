package com.smartfirehub.embedding.config.dto;

/**
 * 그 설정(공간)으로 저장했을 때 다시 임베딩할 대상 수. 확인 창과 reindex-all 응답이 쓴다. 행 검색 색인은 판정식에 들어가지 않지만(스윕이 스스로 재색인한다)
 * 비용 안내를 위해 함께 센다.
 */
public record EmbeddingImpact(long chunks, long datasets, long rowSearchIndexes) {}
