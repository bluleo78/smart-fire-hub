# Phase 7-1: PDF 리포트 내보내기 — 설계 문서

> **작성일**: 2026-04-02
> **상태**: 승인됨
> **의존**: Phase 7-0a (실행 결과 조회 API + 상세 페이지)
> **범위**: Backend (firehub-api) + Frontend (firehub-web)

---

## 1. 목표

프로액티브 리포트 실행 결과를 PDF로 내보낼 수 있게 한다.

- 실행 결과 상세에서 "PDF 다운로드" 버튼으로 1건의 결과를 PDF 파일로 저장
- 이메일 채널에 "PDF 첨부" 옵션을 추가하여 자동 실행 시 PDF를 첨부 파일로 전송
- 기존 이메일 전송 파이프라인(Thymeleaf + CommonMark + 차트 렌더링)을 재사용

---

## 2. 기술 접근

### 2.1 HTML→PDF 변환 (Flying Saucer + OpenPDF)

백엔드에서 Thymeleaf로 HTML을 렌더링한 뒤, Flying Saucer(OpenPDF)로 PDF 변환한다.

**선택 이유:**
- 이메일 전송에 이미 Thymeleaf 템플릿 + CommonMark 마크다운→HTML 변환이 구현되어 있음
- PDF 전용 Thymeleaf 템플릿만 추가하면 됨
- 한글 폰트 번들링으로 환경 독립적인 출력 보장

**신규 의존성:**
- `org.xhtmlrenderer:flying-saucer-openpdf:9.7.1` — HTML→PDF 변환
- `com.github.librepdf:openpdf:2.0.3` — PDF 렌더링 엔진 (Flying Saucer 내부 의존)

---

## 3. 주요 변경 사항

### 3.1 공통 렌더링 유틸 추출 (`ReportRenderUtils`)

`EmailDeliveryChannel`에서 마크다운→HTML 변환, 카드 데이터 추출, 차트 이미지 수집 로직을 공통 유틸 클래스로 추출한다. PDF와 이메일 양쪽에서 공유한다.

**추출 대상 메서드:**
- `markdownToHtml(String markdown)` → `ReportRenderUtils.markdownToHtml()`
- `buildTemplateSections(List<Section> sections)` → `ReportRenderUtils.buildTemplateSections()`
- `renderChartImages(List<Map<String, Object>> templateSections)` → `ReportRenderUtils.renderChartImages()`

**`ReportRenderUtils` 구조:**
```java
@Service
public class ReportRenderUtils {
    private final Parser markdownParser;   // CommonMark (GFM tables)
    private final HtmlRenderer htmlRenderer;
    private final WebClient agentWebClient;
    private final ObjectMapper objectMapper;

    // 마크다운 → HTML 변환
    public String markdownToHtml(String markdown);

    // ProactiveResult.Section 목록 → Thymeleaf 템플릿용 Map 목록 변환
    // (label, content→HTML, cards 추출)
    public List<Map<String, Object>> buildTemplateSections(List<ProactiveResult.Section> sections);

    // cards가 있는 섹션에 대해 /agent/chart-render 호출 → ChartImage 목록 반환
    // 각 섹션 Map에 "chartCid" 키 추가
    public List<ChartImage> renderChartImages(List<Map<String, Object>> templateSections);

    public record ChartImage(String cid, String base64) {}
}
```

**`EmailDeliveryChannel` 변경:** 위 3개 메서드와 관련 필드를 제거하고, `ReportRenderUtils`에 위임.

### 3.2 PDF 생성 서비스 (`PdfExportService`)

```java
@Service
public class PdfExportService {
    private final TemplateEngine templateEngine;
    private final ReportRenderUtils renderUtils;

    // 실행 결과 → PDF byte[] 변환
    public byte[] generatePdf(ProactiveResult result, String jobName);
}
```

**처리 흐름:**
1. `renderUtils.buildTemplateSections(result.sections())` — 섹션 데이터 준비
2. `renderUtils.renderChartImages(templateSections)` — 차트 이미지 수집
3. 차트 이미지를 Base64 `data:image/png;base64,...` URI로 변환 (PDF는 inline CID 불가, data URI 사용)
4. Thymeleaf로 `proactive-report-pdf` 템플릿 렌더링
5. Flying Saucer `ITextRenderer`로 HTML→PDF 변환
6. `byte[]` 반환

**한글 폰트 처리:**
- NanumGothic 폰트 파일을 `src/main/resources/fonts/NanumGothic-Regular.ttf`에 번들
- `ITextRenderer.getFontResolver().addFont()` 로 등록
- CSS에서 `font-family: 'NanumGothic', sans-serif` 지정

### 3.3 PDF 다운로드 API

**엔드포인트:** `GET /api/v1/proactive/jobs/{jobId}/executions/{executionId}/pdf`

**컨트롤러:** `ProactiveJobExecutionController` (기존 파일에 메서드 추가)

**동작:**
1. `executionId`로 실행 결과 조회
2. `jobId` 일치 + 상태가 `COMPLETED`인지 검증 (아니면 400 에러)
3. `result` JSONB → `ProactiveResult` 파싱
4. `pdfExportService.generatePdf(result, jobName)` 호출
5. 응답: `Content-Type: application/pdf`, `Content-Disposition: attachment; filename="report-{jobName}-{executionId}.pdf"`

**권한:** 기존 `@RequirePermission("proactive:read")` 적용

### 3.4 이메일 PDF 첨부

**채널 config 확장:**
```json
{
  "channels": [
    {
      "type": "EMAIL",
      "recipientUserIds": [1],
      "recipientEmails": ["user@example.com"],
      "attachPdf": true
    }
  ]
}
```

**`ProactiveConfigParser.ChannelConfig` 확장:**
```java
public record ChannelConfig(
    String type,
    List<Long> recipientUserIds,
    List<String> recipientEmails,
    boolean attachPdf   // 신규 필드
) {}
```

**`EmailDeliveryChannel.deliver()` 변경:**
- `ChannelConfig.attachPdf()`가 `true`이면:
  1. `PdfExportService.generatePdf()` 호출
  2. `MimeMessageHelper.addAttachment("report.pdf", new ByteArrayResource(pdfBytes), "application/pdf")`
- `false`이면 기존 동작 유지 (HTML 본문만)

### 3.5 Thymeleaf PDF 전용 템플릿

**파일:** `src/main/resources/templates/proactive-report-pdf.html`

이메일 템플릿(`proactive-report.html`) 기반이되 PDF에 맞게 조정:
- 고정 너비 A4 (210mm)
- `@page` CSS로 여백 설정 (상하 15mm, 좌우 20mm)
- 한글 폰트 `font-family` 지정
- 차트 이미지: `<img src="data:image/png;base64,..." />` (CID 대신 data URI)
- 헤더: 리포트 제목 + 작업명 + 생성 일시
- 섹션: 라벨 + HTML 콘텐츠 + 카드 (있으면) + 차트 이미지 (있으면)
- 푸터: "Smart Fire Hub에서 자동 생성됨" + 페이지 번호 없음 (7-1 범위)

### 3.6 프론트엔드 — 다운로드 버튼

**`JobExecutionsTab.tsx` 변경:**
- COMPLETED 상태인 실행 결과 상세 영역에 "PDF 다운로드" 버튼 추가
- 다운로드 아이콘 (`FileDown` from lucide-react) + "PDF" 텍스트
- 클릭 시 `proactiveApi.downloadExecutionPdf(jobId, executionId)` 호출
- blob 응답 → `URL.createObjectURL()` → `<a>` 클릭 트리거 → 자동 다운로드

**`proactiveApi` 확장:**
```typescript
downloadExecutionPdf: async (jobId: number, executionId: number): Promise<Blob> => {
  const response = await api.get(
    `/proactive/jobs/${jobId}/executions/${executionId}/pdf`,
    { responseType: 'blob' }
  );
  return response.data;
}
```

### 3.7 프론트엔드 — PDF 첨부 토글

**스마트 작업 생성/편집 폼 변경:**

이메일 채널 설정 영역에 "PDF 첨부" 체크박스 추가.

- 체크 시 `channels[emailIndex].attachPdf = true`
- 기본값: `false` (체크 해제)
- 위치: 이메일 수신자 목록 아래

**영향 파일:** 스마트 작업 생성/편집 폼 컴포넌트 (채널 설정 부분)

---

## 4. 영향받는 파일

### 신규 생성
| 파일 | 역할 |
|------|------|
| `proactive/service/ReportRenderUtils.java` | 마크다운→HTML, 카드 추출, 차트 렌더링 공통 유틸 |
| `proactive/service/PdfExportService.java` | HTML→PDF 변환 서비스 |
| `resources/templates/proactive-report-pdf.html` | PDF용 Thymeleaf 템플릿 |
| `resources/fonts/NanumGothic-Regular.ttf` | 한글 폰트 번들 |

### 수정
| 파일 | 변경 내용 |
|------|-----------|
| `build.gradle.kts` | Flying Saucer + OpenPDF 의존성 추가 |
| `proactive/service/delivery/EmailDeliveryChannel.java` | 공통 로직 → `ReportRenderUtils` 위임, PDF 첨부 로직 추가 |
| `proactive/util/ProactiveConfigParser.java` | `ChannelConfig`에 `attachPdf` 필드 추가 |
| `proactive/controller/ProactiveJobExecutionController.java` | PDF 다운로드 엔드포인트 추가 |
| `JobExecutionsTab.tsx` | PDF 다운로드 버튼 추가 |
| `proactiveApi.ts` | `downloadExecutionPdf()` 함수 추가 |
| 스마트 작업 폼 컴포넌트 | 이메일 채널 PDF 첨부 토글 추가 |

---

## 5. 설계 결정 기록

| 결정 | 선택 | 이유 |
|------|------|------|
| PDF 생성 방식 | 백엔드 HTML→PDF (Flying Saucer) | 이메일용 Thymeleaf + CommonMark 로직 재사용, 서버 사이드 일관성 |
| 차트 이미지 | 기존 `/agent/chart-render` 재사용 | 이메일과 동일한 파이프라인, 추가 개발 최소 |
| PDF 스타일 | 이메일 템플릿 기반 | 빠른 구현, 일관된 룩앤필, 고도화는 7-5에서 |
| 공통 유틸 추출 | `ReportRenderUtils` | DRY — 이메일/PDF에서 동일 로직 공유 |
| PDF 생성 방식 | 동기 | 결과가 이미 DB에 존재하므로 변환만 수행, 비동기 불필요 |
| 한글 폰트 | NanumGothic 번들링 | 서버 환경 독립적, OFL 라이선스 |

---

## 6. 검증 기준

### 빌드/타입 검증
- [ ] `./gradlew build` 통과 (백엔드)
- [ ] `pnpm typecheck` 통과 (프론트엔드)
- [ ] `pnpm build` 통과 (프론트엔드)

### 백엔드 테스트
- [ ] `ReportRenderUtils` 단위 테스트: 마크다운→HTML 변환, 카드 추출
- [ ] `PdfExportService` 통합 테스트: ProactiveResult → PDF byte[] 생성, PDF 유효성 검증
- [ ] PDF 다운로드 API 테스트: COMPLETED 실행 → 200 + PDF, 미완료 실행 → 400
- [ ] `ProactiveConfigParser` 테스트: `attachPdf` 파싱 (true/false/미지정→false)
- [ ] `EmailDeliveryChannel` 테스트: attachPdf=true 시 첨부 파일 포함 검증

### 프론트엔드 검증
- [ ] 실행 결과 상세에서 PDF 다운로드 버튼 표시 (COMPLETED 상태만)
- [ ] 다운로드 클릭 → PDF 파일 저장됨
- [ ] 스마트 작업 폼에서 이메일 채널 PDF 첨부 토글 표시/저장

### 비기능 검증
- [ ] PDF에 한글 정상 렌더링
- [ ] 차트 이미지 포함된 리포트 PDF 정상 출력
- [ ] 차트 없는 리포트도 정상 출력

---

## 7. 범위 외 (Not In Scope)

- 커스텀 PDF 디자인 (표지, 목차, 워터마크, 페이지 번호) → 추후 고도화
- PDF 비동기 생성 → 동기로 충분
- PDF 템플릿 커스터마이징 UI → 추후
- 대량 리포트 일괄 PDF 내보내기 → 추후
