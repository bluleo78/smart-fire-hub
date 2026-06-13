package com.smartfirehub.document.controller;

import com.smartfirehub.document.dto.DocumentSearchHit;
import com.smartfirehub.document.dto.DocumentSearchRequest;
import com.smartfirehub.document.service.DocumentSearchService;
import com.smartfirehub.global.security.RequirePermission;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 문서 의미검색(RAG) 엔드포인트. */
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentSearchController {

  private final DocumentSearchService searchService;

  /** 쿼리에 대한 top-K 문서 청크 검색. */
  @PostMapping("/search")
  @RequirePermission("dataset:read")
  public List<DocumentSearchHit> search(@RequestBody DocumentSearchRequest request) {
    return searchService.search(request);
  }
}
