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
import com.smartfirehub.file.exception.FileNotFoundException;
import com.smartfirehub.file.exception.FileSizeLimitExceededException;
import com.smartfirehub.file.exception.UnsupportedUploadFileTypeException;
import com.smartfirehub.pipeline.exception.CyclicTriggerDependencyException;
import com.smartfirehub.proactive.exception.ProactiveJobException;
import com.smartfirehub.user.exception.UserDeactivatedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
