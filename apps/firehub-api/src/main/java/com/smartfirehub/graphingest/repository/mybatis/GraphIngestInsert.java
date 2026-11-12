package com.smartfirehub.graphingest.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.GeneratedId;

/**
 * 그래프 적재 이력 INSERT 파라미터 겸 생성 키 홀더(WD-711). {@code useGeneratedKeys} 가 생성된 id 를 {@code id} 에 써 넣는다 —
 * Tibero 에는 RETURNING 이 없어 jOOQ 의 {@code returning(ID)} 대신 JDBC 생성 키로 받는다.
 */
public class GraphIngestInsert extends GeneratedId {

  private final long datasetId;
  private final int schemaVersionAtIngest;
  private final int chunkCount;
  private final int nodeCount;
  private final int edgeCount;
  private final int extractionFailures;
  private final String status;

  public GraphIngestInsert(
      long datasetId,
      int schemaVersionAtIngest,
      int chunkCount,
      int nodeCount,
      int edgeCount,
      int extractionFailures,
      String status) {
    this.datasetId = datasetId;
    this.schemaVersionAtIngest = schemaVersionAtIngest;
    this.chunkCount = chunkCount;
    this.nodeCount = nodeCount;
    this.edgeCount = edgeCount;
    this.extractionFailures = extractionFailures;
    this.status = status;
  }

  public long getDatasetId() {
    return datasetId;
  }

  public int getSchemaVersionAtIngest() {
    return schemaVersionAtIngest;
  }

  public int getChunkCount() {
    return chunkCount;
  }

  public int getNodeCount() {
    return nodeCount;
  }

  public int getEdgeCount() {
    return edgeCount;
  }

  public int getExtractionFailures() {
    return extractionFailures;
  }

  public String getStatus() {
    return status;
  }
}
