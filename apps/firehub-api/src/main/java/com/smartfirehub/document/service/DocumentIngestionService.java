package com.smartfirehub.document.service;

import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.document.dto.DocumentFileResponse;
import com.smartfirehub.document.dto.ExtractedText;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.document.repository.DocumentFileRepository;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.transaction.AfterCommitRunner;
import com.smartfirehub.notification.service.NotificationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 문서 업로드 + 비동기 인제스션(추출→청킹→임베딩→저장). */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentIngestionService {

  private final DatasetRepository datasetRepository;
  private final DocumentStorageService storageService;
  private final DocumentFileRepository fileRepository;
  private final DocumentChunkRepository chunkRepository;
  private final TextExtractor textExtractor;
  private final TextChunker textChunker;
  private final EmbeddingProviderFactory embeddingProviderFactory;
  private final NotificationService notificationService;
  private final JobScheduler jobScheduler;

  /** 동기: 중복검사 + blob 저장 + document_file(PENDING) 생성 + 잡 enqueue. */
  // 메서드 레벨 트랜잭션을 두지 않는다 — 리포지토리가 자기 트랜잭션을 열어 GUC 를 보장하고,
  // 여기서 감싸면 파일 전송/외부 저장이 DB 커넥션을 잡은 채로 진행된다.
  public DocumentFileResponse upload(
      Long datasetId, byte[] data, String originalName, String mimeType, Long userId) {
    // 잘못된 대상에 blob 이 먼저 저장되지 않도록, 체크섬/저장보다 앞서 대상 데이터셋을 검증한다.
    DatasetResponse dataset =
        datasetRepository
            .findById(datasetId)
            .orElseThrow(() -> new DatasetNotFoundException("Dataset not found: " + datasetId));
    // 문서 인제스션은 DOCUMENT 타입 데이터셋에서만 허용한다(SOURCE 등 다른 타입 차단).
    if (!"DOCUMENT".equals(dataset.storageType())) {
      throw new IllegalArgumentException("DOCUMENT 데이터셋만 문서를 업로드할 수 있습니다");
    }
    String checksum = storageService.checksum(data);
    // 같은 데이터셋에 동일 내용 문서가 이미 있으면 중복 인제스션을 막는다.
    if (fileRepository.existsByChecksum(datasetId, checksum)) {
      throw new IllegalArgumentException("이미 업로드된 동일 문서입니다: " + originalName);
    }
    String storagePath = storageService.store(data, originalName);
    Long id;
    try {
      id =
          fileRepository.create(
              datasetId, originalName, mimeType, data.length, storagePath, checksum, userId);
    } catch (org.springframework.dao.DuplicateKeyException e) {
      // 동시 업로드 경합으로 유니크 인덱스(uq_document_file_dataset_checksum) 위반 시
      // 방금 저장한 고아 blob 을 정리하고 친화적 메시지로 변환한다.
      storageService.delete(storagePath);
      throw new IllegalArgumentException("이미 업로드된 동일 문서입니다: " + originalName);
    } catch (RuntimeException e) {
      // create 실패 시에도 고아 blob 이 남지 않도록 정리 후 원 예외를 재전파한다.
      storageService.delete(storagePath);
      throw e;
    }
    // 배경 잡에는 요청 스코프의 테넌트가 승계되지 않으므로 페이로드에 실어 보낸다.
    long tenantId = TenantContext.require();
    // JobRunr StorageProvider 는 DataSource 에서 자기 커넥션을 직접 얻어(DataSourceUtils 를 거치지
    // 않음) 호출자의 스프링 트랜잭션에 합류하지 않는다. 즉 잡 레코드가 먼저 커밋되면 워커가
    // create() 커밋 전에 잡을 집어가 processIngestion 첫 줄의 findById 가 실패한다(PENDING 고착).
    // 이 메서드 자체는 트랜잭션을 열지 않으므로 지금은 즉시 실행되지만, 상위 호출자가 트랜잭션을
    // 여는 경우를 대비해 커밋 이후로 미루는 가드를 유지한다.
    AfterCommitRunner.run(() -> jobScheduler.enqueue(() -> processIngestion(id, tenantId)));
    return fileRepository.findById(id).orElseThrow();
  }

  /** 비동기 잡: 추출 → 청킹 → 임베딩 → document_chunk 저장 → 상태 전이. */
  @Job(name = "Document ingestion: file %0")
  public void processIngestion(Long documentFileId, long tenantId) {
    // 잡 스레드에는 요청 컨텍스트가 없다. RLS가 걸린 테이블을 읽고 쓰려면 여기서 세워야 한다.
    // 이 메서드에 @Transactional을 붙이면 안 된다 — 본문 시작 전에 트랜잭션이 열려 이미 늦는다.
    TenantContext.runScoped(
        tenantId,
        () -> {
      DocumentFileResponse file = fileRepository.findById(documentFileId).orElseThrow();
      boolean completed = false;
      try {
        // 잡 재시도 시 이전에 부분 적재된 청크가 남아 중복되지 않도록 먼저 정리한다(멱등성 보장).
        chunkRepository.deleteByDocumentFileId(documentFileId);
        fileRepository.updateStatus(documentFileId, "PARSING");
        // storagePath 는 응답 DTO에 없으므로 저장소 경로를 별도 조회로 얻는다.
        byte[] data = storageService.read(fileRepository.findStoragePath(documentFileId));
        ExtractedText extracted =
            textExtractor.extract(data, file.mimeType(), file.originalName());
        List<Chunk> chunks = textChunker.chunk(extracted.text());

        if (chunks.isEmpty()) {
          // 추출 텍스트가 비어 청크가 없으면 임베딩 없이 0건으로 완료 처리한다.
          fileRepository.markCompleted(documentFileId, extracted.pageCount(), 0);
        } else {
          fileRepository.updateStatus(documentFileId, "EMBEDDING");
          EmbeddingProvider provider = embeddingProviderFactory.current();
          List<float[]> embeddings =
              provider.embed(chunks.stream().map(Chunk::content).toList());

          chunkRepository.insertBatch(
              documentFileId, file.datasetId(), chunks, embeddings, provider.modelId());
          fileRepository.markCompleted(documentFileId, extracted.pageCount(), chunks.size());
          log.info("Document ingested: file={} chunks={}", documentFileId, chunks.size());
        }
        completed = true;
      } catch (Exception e) {
        log.error("Document ingestion failed: file={}", documentFileId, e);
        fileRepository.markFailed(documentFileId, e.getMessage());
        notificationService.notifyDocumentIngested(
            file.uploadedBy(), file.datasetId(), file.originalName(), false);
      }
      // 알림 브로드캐스트 중 예외가 COMPLETED 상태를 FAILED로 뒤집지 않도록 try 밖에서 성공 알림을 보낸다.
      if (completed) {
        notificationService.notifyDocumentIngested(
            file.uploadedBy(), file.datasetId(), file.originalName(), true);
      }
        });
  }

  /** 문서 1건 삭제: document_chunk 는 FK CASCADE, 원본 파일과 메타를 정리. */
  public void deleteDocument(Long documentFileId) {
    // 메타 삭제 전에 저장소 경로를 먼저 확보한다(삭제 후엔 조회 불가).
    String storagePath = fileRepository.findStoragePath(documentFileId);
    fileRepository.delete(documentFileId);
    try {
      storageService.delete(storagePath);
    } catch (Exception e) {
      log.warn("원본 파일 삭제 실패(메타는 삭제됨): {}", storagePath, e);
    }
  }
}
