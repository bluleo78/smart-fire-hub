package com.smartfirehub.document.controller;

import com.smartfirehub.document.dto.ChunkContentResponse;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.global.security.RequirePermission;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 데이터셋의 문서 청크 전체를 반환하는 내부 bulk-read 엔드포인트(GraphRAG 추출 원천). */
@RestController
@RequestMapping("/api/v1/datasets/{datasetId}/document-chunks")
@RequiredArgsConstructor
public class DocumentChunkController {

  private final DocumentChunkRepository chunkRepository;

  /** datasetId의 모든 청크를 (chunkId, content)로 반환한다. 페이지네이션 없음(스켈레톤 범위). */
  @GetMapping
  @RequirePermission("dataset:read")
  public List<ChunkContentResponse> list(@PathVariable Long datasetId) {
    return chunkRepository.findChunkContentsByDataset(datasetId).stream()
        .map(c -> new ChunkContentResponse(c.chunkId(), c.content()))
        .toList();
  }
}
