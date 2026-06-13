package com.smartfirehub.global.exception;

import com.smartfirehub.analytics.exception.ChartNotFoundException;
import com.smartfirehub.analytics.exception.DashboardNotFoundException;
import com.smartfirehub.analytics.exception.SavedQueryNotFoundException;
import com.smartfirehub.apiconnection.exception.ApiConnectionException;
import com.smartfirehub.dataimport.exception.ConcurrentImportException;
import com.smartfirehub.dataimport.exception.ImportProcessingException;
import com.smartfirehub.dataset.exception.CategoryNotFoundException;
import com.smartfirehub.dataset.exception.ColumnModificationException;
import com.smartfirehub.dataset.exception.DuplicateDatasetNameException;
import com.smartfirehub.embedding.EmbeddingException;
import com.smartfirehub.file.exception.FileNotFoundException;
import com.smartfirehub.file.exception.FileSizeLimitExceededException;
import com.smartfirehub.file.exception.UnsupportedUploadFileTypeException;
import com.smartfirehub.pipeline.exception.CyclicTriggerDependencyException;
import com.smartfirehub.proactive.exception.ProactiveJobException;
import com.smartfirehub.user.exception.UserDeactivatedException;
import jakarta.validation.ConstraintViolationException;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** 추가 예외 핸들러 커버리지를 위한 stub 컨트롤러 — GlobalExceptionHandlerExtendedTest 전용. */
@RestController
@RequestMapping("/test/exception2")
public class ExceptionStubControllerExtended {

  @GetMapping("/saved-query-not-found")
  public void savedQueryNotFound() {
    throw new SavedQueryNotFoundException("Saved query not found: 55");
  }

  @GetMapping("/chart-not-found")
  public void chartNotFound() {
    throw new ChartNotFoundException("Chart not found: 66");
  }

  @GetMapping("/dashboard-not-found")
  public void dashboardNotFound() {
    throw new DashboardNotFoundException("Dashboard not found: 77");
  }

  @GetMapping("/api-connection-not-found")
  public void apiConnectionNotFound() {
    throw new ApiConnectionException("ApiConnection not found: 88");
  }

  @GetMapping("/file-not-found")
  public void fileNotFound() {
    throw new FileNotFoundException(99L);
  }

  @GetMapping("/file-size-exceeded")
  public void fileSizeExceeded() {
    throw new FileSizeLimitExceededException("test", 1024L * 1024L);
  }

  @GetMapping("/unsupported-upload-type")
  public void unsupportedUploadType() {
    throw new UnsupportedUploadFileTypeException("Unsupported upload type");
  }

  @GetMapping("/proactive-job-error")
  public void proactiveJobError() {
    throw new ProactiveJobException("Proactive job failed");
  }

  @GetMapping("/user-deactivated")
  public void userDeactivated() {
    throw new UserDeactivatedException("User is deactivated");
  }

  @GetMapping("/category-not-found")
  public void categoryNotFound() {
    throw new CategoryNotFoundException("Category not found");
  }

  @GetMapping("/duplicate-dataset-name")
  public void duplicateDatasetName() {
    throw new DuplicateDatasetNameException("Duplicate dataset name");
  }

  @GetMapping("/column-modification")
  public void columnModification() {
    throw new ColumnModificationException("Column modification not allowed");
  }

  @GetMapping("/crypto-error")
  public void cryptoError() {
    throw new CryptoException("Crypto error occurred");
  }

  @GetMapping("/serialization-error")
  public void serializationError() {
    throw new SerializationException("Serialization failed");
  }

  @GetMapping("/external-service-error")
  public void externalServiceError() {
    throw new ExternalServiceException("External service unavailable");
  }

  @GetMapping("/embedding-error")
  public void embeddingError() {
    throw new EmbeddingException("Embedding service unavailable");
  }

  @GetMapping("/import-processing")
  public void importProcessing() {
    throw new ImportProcessingException("Import processing failed");
  }

  @GetMapping("/concurrent-import")
  public void concurrentImport() {
    throw new ConcurrentImportException("Concurrent import not allowed");
  }

  @GetMapping("/cyclic-trigger-dependency")
  public void cyclicTriggerDependency() {
    throw new CyclicTriggerDependencyException("Cyclic trigger dependency detected");
  }

  /** 업로드 파일 크기 초과 시 GlobalExceptionHandler가 400을 반환하는지 검증하기 위한 stub (#137) */
  @GetMapping("/max-upload-size-exceeded")
  public void maxUploadSizeExceeded() {
    throw new MaxUploadSizeExceededException(50 * 1024 * 1024L);
  }

  /**
   * @Validated 쿼리 파라미터 제약 위반 시 400 반환 검증 stub (#139)
   */
  @GetMapping("/constraint-violation")
  public void constraintViolation() {
    throw new ConstraintViolationException("page: must be greater than or equal to 0", Set.of());
  }

  /**
   * path variable이 {@code Long} 타입인데 비-숫자 문자열이 전달되면 MethodArgumentTypeMismatchException 발생 (#219)
   */
  @GetMapping("/type-mismatch-long/{id}")
  public void typeMismatchLong(@PathVariable Long id) {
    // 정상 케이스에선 도달하지 않음. 'abc' 같은 입력이면 Spring이 컨버터 단에서 예외를 던진다.
  }

  /** 쿼리 파라미터가 {@code Boolean} 타입인데 'notbool' 같은 값이 전달되면 동일한 예외 발생 (#219) */
  @GetMapping("/type-mismatch-boolean")
  public void typeMismatchBoolean(@RequestParam Boolean flag) {
    // 정상 케이스에선 도달하지 않음.
  }

  /** malformed JSON body 시 HttpMessageNotReadableException 발생 (#219) */
  @PostMapping("/message-not-readable")
  public void messageNotReadable(@RequestBody java.util.Map<String, Object> body) {
    // 정상 케이스에선 도달하지 않음.
  }
}
