package com.smartfirehub.dataimport.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataimport.dto.ColumnMappingEntry;
import com.smartfirehub.dataimport.dto.ImportMode;
import com.smartfirehub.dataimport.dto.ImportPreviewResponse;
import com.smartfirehub.dataimport.dto.ImportResponse;
import com.smartfirehub.dataimport.dto.ImportStartResponse;
import com.smartfirehub.dataimport.dto.ImportValidateResponse;
import com.smartfirehub.dataimport.dto.ParseOptions;
import com.smartfirehub.dataimport.service.DataImportService;
import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.global.util.ClientIpExtractor;
import com.smartfirehub.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/datasets/{datasetId}")
@RequiredArgsConstructor
public class DataImportController {

  private final DataImportService importService;
  private final UserRepository userRepository;
  private final ObjectMapper objectMapper;

  /** 감사 IP 를 신뢰 프록시 기준으로 고른다(WD-402) — X-Forwarded-For 위조로 감사 IP 를 속이지 못하게. */
  private final ClientIpExtractor clientIpExtractor;

  @PostMapping("/imports/preview")
  @RequirePermission("data:import")
  public ResponseEntity<ImportPreviewResponse> previewImport(
      @PathVariable Long datasetId,
      @RequestParam("file") MultipartFile file,
      @RequestParam(defaultValue = ",") String delimiter,
      @RequestParam(defaultValue = "AUTO") String encoding,
      @RequestParam(defaultValue = "true") boolean hasHeader,
      @RequestParam(defaultValue = "0") int skipRows,
      @RequestParam(defaultValue = "false") boolean partial,
      // 엑셀에서 읽을 시트 이름(WD-253). 없으면 첫 번째 보이는 시트. CSV 는 무시한다.
      @RequestParam(required = false) String sheetName)
      throws Exception {
    ParseOptions parseOptions =
        new ParseOptions(delimiter, encoding, hasHeader, skipRows, sheetName);
    // partial=true: 프론트가 대용량 CSV의 앞부분만 잘라 전송한 경우. 파일 끝이 잘려 있어
    // 전체 행수 계산(countRows)을 건너뛴다. (프론트 dataImports.previewImport 참고)
    return ResponseEntity.ok(importService.previewImport(datasetId, file, parseOptions, partial));
  }

  @PostMapping("/imports/validate")
  @RequirePermission("data:import")
  public ResponseEntity<ImportValidateResponse> validateImport(
      @PathVariable Long datasetId,
      @RequestParam("file") MultipartFile file,
      // 선택 값으로 받고 아래에서 직접 확인한다(WD-250) — 필수(required)로 두면 빠졌을 때 Spring 의 누락 예외가 범용 핸들러로 떨어져 500 이
      // 났다. 사전 검증은 확정한 매핑을 검사하는 단계라 매핑 없이는 400 한국어로 거부한다(빈 배열 [] 은 허용).
      @RequestParam(value = "mappings", required = false) String mappingsJson,
      @RequestParam(defaultValue = ",") String delimiter,
      @RequestParam(defaultValue = "AUTO") String encoding,
      @RequestParam(defaultValue = "true") boolean hasHeader,
      @RequestParam(defaultValue = "0") int skipRows,
      // 미리보기에서 고른 시트와 같은 시트를 검사한다(WD-253)
      @RequestParam(required = false) String sheetName)
      throws Exception {
    List<ColumnMappingEntry> mappings = parseMappings(mappingsJson);
    if (mappings == null) {
      throw new IllegalArgumentException(MISSING_MAPPINGS_MESSAGE);
    }
    ParseOptions parseOptions =
        new ParseOptions(delimiter, encoding, hasHeader, skipRows, sheetName);
    return ResponseEntity.ok(importService.validateImport(datasetId, file, mappings, parseOptions));
  }

  @PostMapping("/imports")
  @RequirePermission("data:import")
  public ResponseEntity<ImportStartResponse> importFile(
      @PathVariable Long datasetId,
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "mappings", required = false) String mappingsJson,
      @RequestParam(defaultValue = ",") String delimiter,
      @RequestParam(defaultValue = "AUTO") String encoding,
      @RequestParam(defaultValue = "true") boolean hasHeader,
      @RequestParam(defaultValue = "0") int skipRows,
      @RequestParam(defaultValue = "APPEND") String importMode,
      // 미리보기에서 고른 시트(WD-253). ParseOptions 에 실려 임포트 잡까지 그대로 전달된다.
      @RequestParam(required = false) String sheetName,
      HttpServletRequest request,
      Authentication authentication)
      throws Exception {
    Long userId = (Long) authentication.getPrincipal();
    String username =
        userRepository.findById(userId).map(user -> user.name()).orElse(String.valueOf(userId));
    // 리버스 프록시 환경에서 실제 클라이언트 IP를 기록한다(이슈 #147). 헤더는 신뢰 프록시를 거쳐 온 경우에만 본다(WD-402).
    String ipAddress = clientIpExtractor.extract(request);
    String userAgent = request.getHeader("User-Agent");

    ImportMode resolvedImportMode = parseImportMode(importMode);
    List<ColumnMappingEntry> mappings = parseMappings(mappingsJson);

    ParseOptions parseOptions =
        new ParseOptions(delimiter, encoding, hasHeader, skipRows, sheetName);
    ImportStartResponse response =
        importService.importFile(
            datasetId,
            file,
            mappings,
            userId,
            username,
            ipAddress,
            userAgent,
            parseOptions,
            resolvedImportMode);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping("/imports")
  @RequirePermission("dataset:read")
  public ResponseEntity<List<ImportResponse>> getImports(@PathVariable Long datasetId) {
    return ResponseEntity.ok(importService.getImportsByDatasetId(datasetId));
  }

  @GetMapping("/imports/{importId}")
  @RequirePermission("dataset:read")
  public ResponseEntity<ImportResponse> getImport(
      @PathVariable Long datasetId, @PathVariable Long importId) {
    return ResponseEntity.ok(importService.getImportById(datasetId, importId));
  }

  /** importMode 값이 허용 목록에 없을 때의 400 문구 — 허용 값은 enum 에서 만들어 목록이 어긋나지 않게 한다(WD-250). */
  static final String INVALID_IMPORT_MODE_MESSAGE =
      "가져오기 방식(importMode) 값이 올바르지 않습니다. 사용할 수 있는 값: "
          + String.join(
              ", ", java.util.Arrays.stream(ImportMode.values()).map(Enum::name).toList());

  /** mappings 가 JSON 배열로 읽히지 않을 때의 400 문구(WD-250). */
  static final String INVALID_MAPPINGS_MESSAGE =
      "열 매핑(mappings) 형식이 올바르지 않습니다. "
          + "[{\"fileColumn\": \"파일 열\", \"datasetColumn\": \"데이터셋 열\"}] 형태의 JSON 배열로 보내 주세요.";

  /** 사전 검증에 mappings 가 없을 때의 400 문구(WD-250). */
  static final String MISSING_MAPPINGS_MESSAGE =
      "열 매핑(mappings)이 없습니다. 파일 열과 데이터셋 열의 매핑을 함께 보내 주세요.";

  /**
   * importMode 문자열을 enum 으로 바꾼다(대소문자 무시). 예전에는 틀린 값에 본문 없는 400 을 돌려 사용자·AI 가 무엇이 틀렸는지 알 수
   * 없었다(WD-250).
   */
  private static ImportMode parseImportMode(String importMode) {
    try {
      return ImportMode.valueOf(importMode.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(INVALID_IMPORT_MODE_MESSAGE);
    }
  }

  /**
   * mappings JSON 을 읽는다. 비었거나 JSON {@code null} 이면 매핑 없음(null)으로 본다.
   *
   * <p>예전에는 깨진 JSON 의 Jackson 예외가 범용 핸들러로 떨어져 500 이 났다 — 클라이언트 입력 오류이므로 한국어 400 으로 바꾼다(WD-250).
   */
  private List<ColumnMappingEntry> parseMappings(String mappingsJson) {
    if (mappingsJson == null || mappingsJson.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(mappingsJson, new TypeReference<List<ColumnMappingEntry>>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(INVALID_MAPPINGS_MESSAGE);
    }
  }
}
