# Known Issues

버그 및 개선 과제 트래킹. 발견 시 추가, 수정 시 상태 업데이트.

## 형식

```
### [#번호] 제목
- **심각도**: Critical / Major / Minor / UX
- **컴포넌트**: 파일 경로
- **발견**: YYYY-MM-DD (방법)
- **상태**: 🔴 미처리 / 🟡 진행 중 / ✅ 수정 완료
```

---

### [#55] AddTriggerDialog — 유효하지 않은 Cron 표현식으로 트리거 생성 허용
- **심각도**: Major
- **컴포넌트**: `apps/firehub-web/src/pages/pipeline/components/AddTriggerDialog.tsx:76-95`, `apps/firehub-api/src/main/java/com/smartfirehub/pipeline/service/TriggerService.java:459-471`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 파이프라인/트리거)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: 스케줄 트리거 생성 시 "invalid-cron" 같은 유효하지 않은 Cron 표현식을 입력하면 `CronExpressionInput` 인라인 에러 "유효하지 않은 cron 표현식입니다"가 표시되지만 "트리거 생성" 버튼은 비활성화되지 않고 제출 가능. 백엔드도 검증 없이 저장하여 스케줄러 등록 실패(silent failure).

**재현**:
1. 파이프라인 상세 → 트리거 탭 → 트리거 추가
2. 스케줄 선택
3. Cron 표현식에 "invalid-cron" 입력 → 인라인 에러 표시됨
4. 이름 입력 후 "트리거 생성" 클릭 → 트리거 생성됨 (DB에 `"cron": "invalid-cron"` 저장)
5. 스케줄러는 등록 실패하지만 UI에는 성공으로 표시

**원인**:
- 프론트엔드: `AddTriggerDialog.validate()` (line 76-95)에서 `!c.cron?.trim()` (빈값만 체크), cron 형식 유효성 미검증
- 백엔드: `TriggerService.validateScheduleConfig()` (line 459-471)에서 cron null 여부만 체크, 형식 검증 없음

**수정 방향**:
- 프론트엔드: `cronstrue.toString(c.cron)` try/catch로 Cron 유효성 검증 추가
- 백엔드: Spring `CronExpression.isValidExpression(cron)` 검증 추가

---

### [#54] ApiImportWizard — 0개 필드 매핑으로 완료 허용
- **심각도**: Major
- **컴포넌트**: `apps/firehub-web/src/pages/data/components/ApiImportWizard.tsx:229-235`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 데이터셋/API 가져오기)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: API 가져오기 마법사 Step 2(응답 매핑)에서 필드 매핑을 하나도 추가하지 않아도 Step 3(미리보기)와 Step 4(실행 옵션)로 진행 가능하며, "완료" 버튼으로 파이프라인 생성까지 됨. 실제 실행 시 "path can not be null or empty" 에러로 실패하지만 사용자에게 사전 경고 없음.

**재현**:
1. 데이터셋 상세 → 데이터 탭 → "API 가져오기"
2. URL 입력 후 다음 클릭
3. 테스트 호출 후 필드 매핑 0개인 상태에서 다음 클릭
4. 미리보기(Step 3) → 실행 옵션(Step 4) 진행 가능
5. "완료" 클릭 → 파이프라인 생성됨, 즉시 실행 시 FAILED

**원인**: `handleNext()`가 step 0 URL 빈값만 체크. Step 1(필드 매핑) → Step 2(미리보기) 전환 시 `fieldMappings.length > 0` 조건 없음.

**수정 방향**: Step 1→2 전환 시 `fieldMappings.length === 0` 이면 경고 표시 (차단 또는 확인 다이얼로그).

---

### [#53] ApiImportWizard — 유효하지 않은 URL 형식 허용
- **심각도**: Minor
- **컴포넌트**: `apps/firehub-web/src/pages/data/components/ApiImportWizard.tsx:229-235`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 데이터셋/API 가져오기)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: API 가져오기 마법사 Step 1에서 "not-a-valid-url" 같은 유효하지 않은 URL 입력 시 toast 없이 Step 2로 이동됨. 빈값만 검증하고 URL 형식(http/https 스킴) 검증 없음. Step 2에서 "테스트 호출" 시 "URL scheme not allowed: null" 에러 발생.

**재현**:
1. 데이터셋 상세 → 데이터 탭 → "API 가져오기"
2. URL 입력란에 "not-a-valid-url" 입력
3. "다음" 클릭 → Step 2로 이동됨 (Toast 없음)

**원인**: `handleNext()` (line 229-235)에서 `!url` (빈값) 체크만 존재. URL 형식/스킴 유효성 검증 없음.

**수정 방향**: URL 입력값을 `new URL(url)` 파싱 시도 또는 `^https?://` 정규식으로 검증 추가.

---

### [#52] HomePage — 활동 피드 React 중복 키 경고 (pipeline_execution.id vs audit_log.id 충돌)
- **심각도**: Minor
- **컴포넌트**: `apps/firehub-api/src/main/java/com/smartfirehub/dashboard/service/DashboardService.java:568,639` / `apps/firehub-web/src/pages/HomePage.tsx:508`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 홈)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: 홈 페이지 활동 피드에서 React 콘솔 경고 발생: "Encountered two children with the same key". `pipeline_execution` 테이블과 `audit_log` 테이블의 id 1이 동시에 존재하여 프론트엔드에서 `key=1`이 중복됨.

**재현**:
1. / (홈 페이지) 접근
2. 브라우저 콘솔에서 "Encountered two children with the same key" 경고 확인
3. `pipeline_execution.id=1` 과 `audit_log.id=1` 이 모두 활동 피드에 포함됨

**원인**: `DashboardService.getActivityFeed()`가 두 소스(pipeline_execution, audit_log)에서 id를 그대로 `ActivityItem.id`로 사용. 두 테이블의 id 공간이 겹치면 프론트엔드 `key={item.id}`가 중복됨.

**수정 방향**: 서버: `ActivityItem.id`를 `"PE-" + peId` / `"AL-" + alId` 형식의 복합 키로 변경. 또는 프론트엔드에서 `key={item.eventType + "-" + item.id}` 사용.

---

### [#51] SmtpSettingsTab — SMTP 테스트 실패 시 성공 토스트 표시
- **심각도**: Minor (UX)
- **컴포넌트**: `apps/firehub-web/src/pages/admin/SmtpSettingsTab.tsx:80`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 관리/설정/이메일)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: SMTP 호스트가 설정되지 않은 상태에서 "테스트 발송" 버튼 클릭 시 서버가 `{success: false, message: "SMTP 호스트가 설정되지 않았습니다"}`를 200 OK로 반환하지만, 프론트엔드는 HTTP 200을 성공으로 처리하여 "테스트 이메일이 발송되었습니다." 토스트를 표시함.

**재현**:
1. /admin/settings → "이메일" 탭 이동
2. SMTP 설정이 비어있는 상태에서 "테스트 발송" 버튼 클릭
3. "테스트 이메일이 발송되었습니다." 성공 토스트 표시 (실제로는 실패)

**원인**: 서버가 실패 케이스에서도 HTTP 200을 반환하며 `{success: false}` 페이로드를 사용하는데, `onSuccess` 콜백이 HTTP 상태만 확인하고 `data.success` 필드를 확인하지 않음.

**수정 방향**: `onSuccess` 콜백에서 `data.data?.success === false`일 때 `toast.error(data.data?.message)` 표시.

---

### [#50] ReportViewerPage — 리포트 로드 실패 시 인쇄/PDF 버튼 활성화 상태 유지
- **심각도**: UX
- **컴포넌트**: `apps/firehub-web/src/pages/ai-insights/ReportViewerPage.tsx:68-90`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — AI 인사이트/실행 이력)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: 실행 결과 HTML 로드 실패(`isError=true`) 시에도 "인쇄"/"PDF" 버튼이 활성화 상태로 표시됨. PDF 클릭 시 400 오류 발생.

**재현**:
1. 실패한 실행의 리포트 뷰어 접근 (`/ai-insights/jobs/1/executions/1/report`)
2. "리포트를 불러올 수 없습니다." 표시
3. "인쇄"/"PDF" 버튼 여전히 활성화 상태

**원인**: `isError` 상태를 본문 영역에서만 확인하고 헤더 버튼의 `disabled` 속성에 반영하지 않음.

**수정 방향**: 버튼에 `disabled={isError || isLoading}` 추가.

---

### [#49] ProactiveJobDetailPage — "지금 실행" 버튼 더블클릭 시 중복 실행 요청
- **심각도**: Minor
- **컴포넌트**: `apps/firehub-web/src/pages/ai-insights/ProactiveJobDetailPage.tsx:243`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — AI 인사이트/스마트 작업)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: "지금 실행" 버튼 더블클릭 시 POST /api/v1/proactive/jobs/:id/execute 요청이 2회 발생하여 동일 작업이 중복 실행됨.

**재현**:
1. 작업 상세 페이지 → "지금 실행" 버튼 더블클릭
2. 네트워크 로그: POST /execute → 202, POST /execute → 202 (2회)

**원인**: 버튼에 `disabled={executeMutation.isPending}` 있으나, 더블클릭 두 번째 click 이벤트가 React 재렌더링(disabled 적용) 이전에 발화됨.

**수정 방향**: `useRef`로 실행 중 플래그 관리 또는 `executeMutation.isPending`을 ref로 동기 추적.

---

### [#48] ReportTemplateDetailPage — JSON 탭에서 편집 후 저장 시 JSON 변경 무시 (tree 상태 사용)
- **심각도**: Major
- **컴포넌트**: `apps/firehub-web/src/pages/ai-insights/ReportTemplateDetailPage.tsx:117`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — AI 인사이트/리포트 양식, 코드 분석)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: JSON 탭에서 섹션 구조를 수정한 후 빌더 탭으로 전환하지 않고 바로 저장하면, JSON 편집 내용이 무시되고 이전 빌더 상태가 저장됨.

**재현**:
1. 템플릿 편집 모드 → JSON 탭으로 이동
2. JSON 내용 수정 (섹션 추가/삭제/key 변경)
3. 빌더 탭으로 돌아가지 않고 바로 저장
4. 저장된 결과: JSON 변경 내용 대신 이전 빌더 상태가 저장됨

**원인**: `handleSave`에서 `sections: tree.sections` 사용. `tree.sections`는 JSON 탭 편집 중에 업데이트되지 않음. `setStructureJson(json)` 호출만으로는 `tree.sections`가 동기화되지 않음. `handleTabChange`에서만 JSON→tree 동기화 실행됨.

**수정 방향**: 저장 전 `parseTemplateSections(structureJson)` 결과를 `sections`로 사용하거나, 저장 시 JSON이 유효하면 `tree.setSections(parsed)` 먼저 호출.

---

### [#37] ProactiveJobDetailPage — 삭제 버튼에 확인 다이얼로그 없음
- **심각도**: Major
- **컴포넌트**: `apps/firehub-web/src/pages/ai-insights/ProactiveJobDetailPage.tsx:148-156`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — AI 인사이트/스마트 작업)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: 작업 상세 페이지의 삭제 버튼 클릭 시 확인 없이 즉시 삭제 후 목록으로 이동.

**재현**:
1. /ai-insights/jobs/:id 에서 "삭제" 버튼 클릭
2. 즉시 DELETE /api/v1/proactive/jobs/:id 호출 후 목록으로 이동

**원인**: `handleDelete()`가 AlertDialog 없이 `deleteMutation.mutate()` 직접 호출.

**수정 방향**: 다른 삭제 버튼들처럼 AlertDialog 확인 후 삭제 실행.

---

### [#38] JobOverviewTab — 트리거 유형 ANOMALY 선택 시 실행 주기/타임존 필드 미숨김
- **심각도**: UX
- **컴포넌트**: `apps/firehub-web/src/pages/ai-insights/tabs/JobOverviewTab.tsx:423-474`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — AI 인사이트/스마트 작업)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: 트리거 유형을 "이상 탐지 (이벤트 기반)"로 선택해도 "실행 주기"/"타임존"/"다음 실행" 필드가 계속 표시됨.

**재현**:
1. /ai-insights/jobs/new 에서 트리거 유형 드롭다운 → "이상 탐지 (이벤트 기반)" 선택
2. 실행 주기/타임존/다음 실행 필드가 여전히 화면에 표시됨

**원인**: `JobOverviewTab.tsx`의 실행 주기(line 423), 타임존(line 456), 다음 실행(line 480) 섹션에 `watch('triggerType') !== 'ANOMALY'` 조건부 렌더링 없음.

**수정 방향**: 각 섹션을 `{watch('triggerType') !== 'ANOMALY' && (...)}` 로 감싸기.

---

### [#39] JobMonitoringTab — 쿨다운 음수 입력 시 Zod 검증 실패가 UI에 미표시
- **심각도**: Minor (UX)
- **컴포넌트**: `apps/firehub-web/src/pages/ai-insights/tabs/JobMonitoringTab.tsx:330-342`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — AI 인사이트/스마트 작업)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: 쿨다운(분) 필드에 음수 입력 후 저장 시 Zod 검증(`min(1)`)이 실패하여 PATCH 요청이 발생하지 않으나 UI에 에러 메시지가 표시되지 않음. 사용자는 왜 저장이 안 되는지 알 수 없음.

**재현**:
1. 모니터링 탭 편집 모드 → 이상 탐지 활성화
2. 쿨다운 필드에 `-5` 입력 (nativeInputValueSetter 또는 직접 입력)
3. 저장 클릭 → 화면 변화 없음, PATCH 요청도 없음

**원인**: `proactive-job.ts` Zod 스키마에 `cooldownMinutes: z.number().min(1, '최소 1분 이상이어야 합니다')` 있으나, `JobMonitoringTab.tsx` cooldown 입력 필드 하단에 `{errors.config?.anomaly?.cooldownMinutes && ...}` 에러 표시 코드 없음.

**수정 방향**: cooldown `Input` 하단에 에러 표시 추가:
```tsx
{errors.config?.anomaly?.cooldownMinutes && (
  <p className="text-xs text-destructive">{errors.config.anomaly.cooldownMinutes.message}</p>
)}
```

---

### [#40] ReportTemplateDetailPage — 템플릿 섹션 Key 중복 시 클라이언트/서버 검증 없음
- **심각도**: Major
- **컴포넌트**: `apps/firehub-web/src/pages/ai-insights/ReportTemplateDetailPage.tsx`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — AI 인사이트/리포트 양식)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: 동일한 key값을 가진 섹션을 여러 개 추가해도 저장이 허용됨. 이후 리포트 생성 시 동일 key 섹션 간 충돌 발생 가능.

**재현**:
1. /ai-insights/templates/:id 편집 모드 → 섹션 추가
2. 새 섹션의 Key를 기존 섹션과 동일한 값(예: "summary")으로 변경
3. 저장 → PUT 204 성공, 에러 없음

**원인**: `SectionPropertyEditor`에서 key 변경 시 중복 확인 로직 없음. 서버 API도 key 유일성을 검증하지 않음.

**수정 방향**: 저장 전 섹션 key 배열에서 중복 검사 후 에러 표시.

---

### [#41] ProactiveJobListPage — 작업 삭제 후 GET /jobs/:id 응답 400 반환 (404 예상)
- **심각도**: Minor
- **컴포넌트**: `apps/firehub-api` proactive jobs controller
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — AI 인사이트/스마트 작업)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: 작업 삭제(DELETE 204) 직후 프론트엔드가 GET /api/v1/proactive/jobs/:id 재조회 시 400 반환. HTTP 표준상 존재하지 않는 리소스는 404 반환이 맞음.

**재현**:
1. 작업 목록에서 작업 삭제 (DELETE → 204)
2. 바로 이어서 GET /api/v1/proactive/jobs/{삭제된 id} → 400 Bad Request

**원인**: 삭제된 ID 조회 시 서버가 400을 반환. 404가 아닌 400은 REST 규칙 위반.

**수정 방향**: 삭제된 리소스 조회 시 `ResponseEntity.notFound()` (404) 반환.

---

### [#47] ProactiveJobDetailPage — 존재하지 않는 작업 ID 접근 시 빈 편집 폼 표시
- **심각도**: Minor
- **컴포넌트**: `apps/firehub-web/src/pages/ai-insights/ProactiveJobDetailPage.tsx:94`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — AI 인사이트/스마트 작업)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: 존재하지 않는 작업 ID로 접근(예: /ai-insights/jobs/9999)하면 404 처리나 에러 메시지 없이 제목 "-"과 빈 기본값을 가진 편집 폼이 표시됨.

**재현**:
1. /ai-insights/jobs/9999 접근
2. GET 400/404 응답 → 제목 "-" + 빈 편집 폼 표시

**원인**: `useProactiveJob(9999)` 호출 시 API가 400 반환 → `job=undefined` → `JobOverviewTab`의 `!isEditing && !isNew && job` 조건이 false → 편집 폼이 fallthrough 렌더링됨. `isError` 상태 처리 없음.

**수정 방향**: `const { data: job, isLoading, isError } = useProactiveJob(jobId)` 후 `isError` 분기에서 "작업을 찾을 수 없습니다" 표시 및 목록으로 리다이렉트.

---

### [#46] ProactiveJobListPage — API 500 에러 시 빈 상태 화면 표시 (에러 구분 없음)
- **심각도**: Minor (UX)
- **컴포넌트**: `apps/firehub-web/src/pages/ai-insights/AiInsightJobsPage.tsx`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — AI 인사이트/스마트 작업)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: GET /api/v1/proactive/jobs 에서 500 에러 발생 시 UI가 에러 상태 대신 "스마트 작업 없음" 빈 상태 화면을 표시함. 사용자는 API 실패인지 실제로 작업이 없는지 구분할 수 없음.

**재현**:
1. GET /api/v1/proactive/jobs 를 500으로 모킹
2. /ai-insights/jobs 페이지 로드
3. "스마트 작업 없음" 화면 표시

**원인**: `useProactiveJobs` 훅에서 `isError` 상태를 처리하지 않거나, 컴포넌트에서 `isError` 분기 없이 `data || []` 기본값을 사용.

**수정 방향**: TanStack Query의 `isError` 상태 확인 후 에러 메시지(예: "작업 목록을 불러오지 못했습니다. 다시 시도해주세요.") 표시.

---

### [#45] ReportTemplateDetailPage — 섹션 Key 유효성 오류 시 UI 경고만 표시, 저장 차단 안 함
- **심각도**: Major
- **컴포넌트**: `apps/firehub-web/src/pages/ai-insights/components/SectionPropertyEditor.tsx`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — AI 인사이트/리포트 양식)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: 섹션 Key에 공백·대문자·특수문자 입력 시 "영문 소문자, 숫자, 밑줄만 사용 가능" 경고가 표시되지만 저장 버튼이 차단되지 않아 유효하지 않은 key값으로 저장됨.

**재현**:
1. 새 템플릿 생성(/ai-insights/templates/new) → 섹션 추가
2. 섹션 Key 필드에 "INVALID KEY" (공백 포함) 입력
3. 에러 메시지 표시됨
4. 생성 버튼 클릭 → POST 201 성공, key="INVALID KEY"로 저장됨

**원인**: `SectionPropertyEditor`에서 `isValidKey` 검사 후 에러 메시지만 표시하고 상위 컴포넌트 저장 핸들러에 유효성 오류를 전달하지 않음.

**수정 방향**: 저장 전 모든 섹션 key 유효성 검사 후 하나라도 실패하면 toast.error 표시 및 저장 차단.

---

### [#44] JobMonitoringTab — 커스텀 메트릭 폴링 주기 최솟값(60초) 미검증, 60초 미만 추가 허용
- **심각도**: Minor
- **컴포넌트**: `apps/firehub-web/src/pages/ai-insights/tabs/JobMonitoringTab.tsx:519`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — AI 인사이트/스마트 작업)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: 커스텀 메트릭 추가 다이얼로그에서 폴링 주기를 60초 미만으로 입력하고 "추가"를 클릭해도 에러 없이 메트릭이 추가됨.

**재현**:
1. 모니터링 탭 편집 → 이상 탐지 활성화 → 커스텀 메트릭 버튼 클릭
2. 이름/데이터셋/쿼리 입력, 폴링 주기에 "10" 입력 (min=60 미만)
3. 추가 클릭 → 경고 없이 추가됨

**원인**: "추가" 버튼 `disabled` 조건이 `customName/customDatasetId/customQuery` 만 확인하고 `customInterval >= 60` 을 확인하지 않음 (`JobMonitoringTab.tsx:519`).

**수정 방향**: 추가 버튼 disabled 조건에 `|| customForm.customInterval < 60` 추가. 에러 메시지도 표시 권장.

---

### [#43] JobOverviewTab — "직접 입력" 선택 시 커스텀 Cron 입력 필드 미표시
- **심각도**: Major
- **컴포넌트**: `apps/firehub-web/src/pages/ai-insights/tabs/JobOverviewTab.tsx:428-441`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — AI 인사이트/스마트 작업)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: 실행 주기 드롭다운에서 "직접 입력"을 선택해도 Cron 표현식 텍스트 입력 필드가 나타나지 않아 커스텀 스케줄을 설정할 수 없음.

**재현**:
1. 새 작업 생성(/ai-insights/jobs/new) 또는 기존 작업 편집
2. 실행 주기 드롭다운 → "직접 입력" 선택
3. 커스텀 Cron 텍스트 입력 필드가 나타나지 않음

**원인**: `onValueChange`가 `'__custom__'` 선택 시 `setValue('cronExpression', ...)` 를 호출하지 않아 `cronExpression` 값이 기존 프리셋 값("0 9 * * *")으로 유지됨. `cronPreset`이 프리셋 일치로 재계산되어 `{cronPreset === '__custom__' && (...)}` 조건이 false.

**수정 방향**: `__custom__` 선택 시 `setValue('cronExpression', '')` 호출하여 `cronPreset`이 `'__custom__'`으로 계산되도록 수정:
```typescript
onValueChange={(v) => {
  if (v === '__custom__') setValue('cronExpression', '');
  else setValue('cronExpression', v);
}}
```

---

### [#42] ProactiveJobDetailPage — 작업 이름 200자 초과 시 Zod/UI 검증 없고 서버 500 반환
- **심각도**: Major
- **컴포넌트**: `apps/firehub-web/src/lib/validations/proactive-job.ts:28` / DB: `V42__create_proactive_tables.sql:6`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — AI 인사이트/스마트 작업)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: 작업 이름 입력 필드에 201자 이상 입력 후 저장 시 Zod 검증 없이 PUT 요청 발생, 서버가 DB VARCHAR(200) 제약 위반으로 500 반환.

**재현**:
1. /ai-insights/jobs/:id 편집 모드 → 이름 필드에 201자 입력
2. 저장 클릭 → 서버 500 오류 발생

**원인**: `proactive-job.ts` `jobFormSchema.name`에 `.max(200)` 제약 없음. 입력 필드에도 `maxLength` 속성 없음. DB는 `VARCHAR(200)`.

**수정 방향**: Zod 스키마에 `name: z.string().min(1, ...).max(200, '이름은 200자 이내여야 합니다')` 추가, 또는 `<Input maxLength={200} />` 추가.

---

### [#16] SaveDialog — 쿼리 이름 200자 초과 시 클라이언트 검증 없음
- **심각도**: Minor (UX)
- **컴포넌트**: `apps/firehub-web/src/pages/analytics/QueryEditorPage.tsx:342` + `apps/firehub-api` saved_query.name
- **발견**: 2026-04-23 (Playwright 탐색 테스트 — 분석/쿼리)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 쿼리 이름 입력 필드에 `maxLength` 속성이 없어 250자 이상도 입력 가능. 저장 시도 시 백엔드에서 400 반환하지만 클라이언트에서 입력 시점 피드백 없음.

**재현**:
1. 저장 다이얼로그 → 이름에 250자 입력 (AAAAAA... ×250)
2. 저장 버튼 클릭 → URL 변경 없음, 다이얼로그 유지
3. DB 제약: `saved_query.name VARCHAR(200)` — 200자 초과 시 400

**원인**: `SaveDialog` 내 `<input placeholder="쿼리 이름을 입력하세요" />`에 `maxLength={200}` 없음. analytics 폴더에 Zod 검증 스키마도 없음.

**수정 방향**: 입력 필드에 `maxLength={200}` 추가 또는 Zod 스키마로 `name: z.string().max(200)` 검증 추가.

---

### [#15] QueryEditorPage — 자동완성 Tab 키 미작동 (completionKeymap 누락)
- **심각도**: Minor (UX)
- **컴포넌트**: `apps/firehub-web/src/pages/analytics/QueryEditorPage.tsx:103`
- **발견**: 2026-04-23 (Playwright 탐색 테스트 — 분석/쿼리)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: SQL 에디터에서 자동완성 팝업이 표시된 상태에서 Tab 키를 눌러도 선택된 후보가 삽입되지 않음. ArrowDown으로 항목을 이동한 뒤 Enter는 작동하나, Tab은 항상 미작동.

**재현**:
1. 쿼리 에디터에서 `SEL` 입력 → 자동완성 팝업 표시
2. `Tab` 키 → 아무 변화 없음 (팝업 여전히 표시)
3. `ArrowDown` + `Enter` → 선택 항목 삽입 ✅

**원인**: `keymap.of([...defaultKeymap, ...historyKeymap, ...searchKeymap, ...])` 에서 `completionKeymap`이 누락됨. `completionKeymap`이 없으면 Tab 키가 completion 수락에 바인딩되지 않음. `autocompletion()`은 Arrow 키와 조건부 Enter만 내부적으로 처리함.

**수정 방향**:
```typescript
import { autocompletion, completionKeymap } from '@codemirror/autocomplete';
// keymap 배열에 추가:
keymap.of([
  ...completionKeymap,  // ← 추가
  ...defaultKeymap,
  ...historyKeymap,
  ...searchKeymap,
  { key: 'Mod-Enter', run: ... }
])
```

---

### [#14] SaveDialog — 동기 이중 클릭 시 중복 저장 요청 발생
- **심각도**: Minor
- **컴포넌트**: `apps/firehub-web/src/pages/analytics/QueryEditorPage.tsx:342,459`
- **발견**: 2026-04-23 (Playwright 탐색 테스트 — 분석/쿼리)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 저장 다이얼로그의 "저장" 버튼을 동일 JS 틱 내에서 두 번 빠르게 클릭하면 POST 요청이 2회 전송됨. `isSaving` 가드(`disabled={!name.trim() || isSaving}`)는 React 리렌더 후에야 `true`가 되므로 동기 이중 클릭을 막지 못함.

**재현**:
1. 쿼리 에디터 → 저장 다이얼로그 열기
2. 이름 입력 후 "저장" 버튼을 100ms 내 두 번 클릭
3. 네트워크: POST `/api/v1/analytics/queries` 2회 전송 확인

**원인**: `handleSave` 함수가 `async`이므로 첫 번째 클릭 후 `createSavedQuery.isPending`이 `true`로 바뀌기 전에 두 번째 클릭이 발생하면 동일한 뮤테이션이 두 번 실행됨.

**수정 방향**: `useRef` 플래그로 동기 중복 실행 방지:
```typescript
const savingRef = useRef(false);
const handleSave = async () => {
  if (savingRef.current || !saveForm.name.trim()) return;
  savingRef.current = true;
  try {
    // 저장 로직
  } finally {
    savingRef.current = false;
  }
};
```

---

### [#13] QueryListPage — 탭 전환 시 폴더 필터 초기화 안 됨
- **심각도**: Minor (UX)
- **컴포넌트**: `apps/firehub-web/src/pages/analytics/QueryListPage.tsx:55`
- **발견**: 2026-04-23 (Playwright 탐색 테스트 — 분석/쿼리)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: "내 쿼리" 탭에서 폴더 필터("보고서")를 선택한 상태로 "공유됨" 탭으로 전환해도 폴더 필터가 유지됨. 필터가 적용된 채로 탭이 바뀌면 사용자는 "공유 쿼리 없음"으로 오해할 수 있음.

**재현**:
1. 쿼리 목록 → 폴더 필터 드롭다운에서 "보고서" 선택
2. "공유됨" 탭 클릭 → "저장된 쿼리가 없습니다." 표시
3. 폴더 드롭다운 확인 → "보고서" 여전히 선택됨

**원인**: `QueryListPage.tsx`의 탭 전환 핸들러가 `setPage(0)`만 초기화하고 `setFolder('')`와 `setSearch('')`를 초기화하지 않음.
```typescript
onValueChange={(v) => {
  setTab(v as 'mine' | 'shared');
  setPage(0); // ← folder, search 초기화 누락
}}
```

**수정 방향**:
```typescript
onValueChange={(v) => {
  setTab(v as 'mine' | 'shared');
  setPage(0);
  setFolder('');   // 추가
  setSearch('');   // 추가
}}
```

---

### [#12] QueryEditorPage — macOS에서 "Ctrl+Enter로 실행" 힌트 오류
- **심각도**: Minor (UX)
- **컴포넌트**: `apps/firehub-web/src/pages/analytics/QueryEditorPage.tsx:355`
- **발견**: 2026-04-23 (Playwright 탐색 테스트 — 분석/쿼리)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: SQL 에디터 하단에 "Ctrl+Enter로 실행" 힌트 텍스트가 표시되지만, macOS에서 실제 단축키는 `Cmd+Enter`임. `Ctrl+Enter`를 누르면 실행되지 않음.

**재현**:
1. 쿼리 에디터에서 SQL 입력
2. macOS에서 `Ctrl+Enter` → 실행 안 됨
3. `Cmd+Enter` → 실행됨

**원인**: CodeMirror v6의 `Mod-Enter` 바인딩은 macOS에서 `Cmd+Enter`, Windows/Linux에서 `Ctrl+Enter`에 매핑되나, UI 힌트 텍스트는 플랫폼 구분 없이 `Ctrl+Enter`로 고정됨.

**수정 방향**: 플랫폼 감지로 힌트 텍스트 분기:
```typescript
const isMac = navigator.platform.toUpperCase().includes('MAC');
// "Ctrl+Enter로 실행" → isMac ? "Cmd+Enter로 실행" : "Ctrl+Enter로 실행"
```

---

### [#11] DeleteConfirmDialog — 조사 오류 "을(를)" 하드코딩 (전체 엔티티 영향)
- **심각도**: Minor (UX)
- **컴포넌트**: `apps/firehub-web/src/components/ui/delete-confirm-dialog.tsx:36`
- **발견**: 2026-04-23 (Playwright MCP 탐색 테스트 — 카테고리)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 삭제 확인 다이얼로그에서 `"카테고리을(를) 정말 삭제하시겠습니까?"` 출력. 카테고리(ㅣ 모음 종성)는 "를" 이어야 하나 "을(를)"로 하드코딩됨.

**재현**: 카테고리 관리 → 임의 항목 삭제 버튼 클릭 → 확인 다이얼로그 메시지 확인.

**원인**: `delete-confirm-dialog.tsx:36`에서 `{entityName}을(를)` 고정 문자열 사용. 모음/자음 종성에 따라 조사를 동적으로 선택하지 않음. 카테고리(ㅣ) · 데이터셋(ㅅ) · 파이프라인(ㄴ) 등 모든 엔티티에 동일 패턴 적용됨.

**수정 방향**: 마지막 음절의 받침 유무를 기준으로 "을"/"를" 동적 선택 유틸 함수 추가:
```typescript
function josa(word: string, eul: string, reul: string) {
  const code = word.charCodeAt(word.length - 1) - 0xAC00;
  return code >= 0 && code % 28 > 0 ? eul : reul;
}
// 예: josa('카테고리', '을', '를') → '를'
```

---

### [#10] DataTab 체크박스 — 단일 행 선택 시 전체 행 selected 표시, 헤더 미반응
- **심각도**: Major
- **컴포넌트**: `apps/firehub-web/src/pages/data/tabs/DatasetDataTab.tsx`
- **발견**: 2026-04-23 (Playwright MCP 탐색 테스트)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료 (#7 수정으로 동시 해결)

**현상**: DataTab에서 임의의 행 체크박스 클릭 시 전체 행이 "checked" 상태로 표시됨. 헤더 "전체 선택" 체크박스는 모든 행이 선택된 상태에서도 "unchecked"로 유지되며 클릭해도 반응 없음.

**재현**:
1. 데이터셋 상세 → 데이터 탭
2. 임의의 행 체크박스 클릭
3. 전체 행이 checked 상태로 변경되는 것 확인
4. 헤더 "전체 선택" 체크박스가 unchecked 유지 확인

**원인**: 버그 #7(`id` vs `_id` 불일치)의 연장. 모든 row의 `id`가 `undefined`/`null`이므로 `selectedRowIds.add(undefined)` 한 번으로 모든 행이 `selectedRowIds.has(undefined)=true` 매칭. `isAllSelected = selectedRowIds.size === allRows.length` → `1 !== 6` → 항상 false → 헤더 unchecked.

**수정 방향**: 버그 #7 수정 시 `_id` 필드 올바르게 매핑하면 동시 해결됨.

---

### [#9] 모든 DialogContent — `Description` / `aria-describedby` 누락 (접근성)
- **심각도**: UX (접근성)
- **컴포넌트**: 모든 Dialog 컴포넌트 (`ColumnDialog`, `ExportDialog`, `ImportMappingDialog`, `RowAddDialog` 등)
- **발견**: 2026-04-23 (playwright-cli 탐색 테스트 — 콘솔 경고)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 다이얼로그가 열릴 때마다 콘솔에 `Warning: Missing Description or aria-describedby={undefined} for {DialogContent}` 반복 출력. 스크린리더 사용자가 다이얼로그의 목적을 파악할 수 없음.

**수정 방향**: 각 `DialogContent` 내부에 `<DialogDescription>` 추가 또는 `aria-describedby` 설정. Radix UI 권고사항 준수.

---

## AI Chat

### [#1] 스트리밍 중단 시 메시지 유실
- **심각도**: Critical
- **컴포넌트**: `apps/firehub-web/src/hooks/queries/useAIChat.ts` — `stopStreaming()`
- **발견**: 2026-04-23 (playwright-cli 탐색 테스트)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 중단 버튼 클릭 시 사용자 메시지와 부분 AI 응답이 모두 사라짐.

**재현**:
1. AI 패널 열기
2. 길거나 복잡한 메시지 전송 (예: "데이터셋 목록을 보여주고 각 컬럼 정보를 자세히 설명해줘")
3. AI 응답 스트리밍 시작 직후 전송 버튼(→중단 버튼) 클릭
4. 채팅 내용 전체 소멸 확인

**원인**: `stopStreaming()`이 `setPendingUserMessage(null)` + `setStreamingMessage(null)` 호출 시, 서버 첫 응답 전에는 사용자 메시지가 `messages[]`에 없어 유실됨 (line 331-332).

**수정 방향**: `stopStreaming()` 내에서 `pendingUserMessage`가 있으면 `messages[]`에 추가 후 클리어.

---

### [#2] SessionSwitcher — "새 대화" 버튼 중복 표시
- **심각도**: Minor (UX)
- **컴포넌트**: `apps/firehub-web/src/components/ai/SessionSwitcher.tsx:35`
- **발견**: 2026-04-23 (playwright-cli 탐색 테스트)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 세션이 존재하지만 선택된 세션이 없을 때, 세션 드롭다운 트리거와 새 세션 액션 버튼이 모두 "새 대화" 텍스트로 표시됨.

**수정 방향**: `triggerLabel` fallback을 `'새 대화'` → `'대화 선택'`으로 변경 (1줄 수정).

```typescript
// SessionSwitcher.tsx:33-35
const triggerLabel = currentSession
  ? (currentSession.title || `대화 #${currentSession.id}`)
  : '대화 선택';  // '새 대화' → '대화 선택'
```

---

### [#4] XLSX 파일 첨부 미지원
- **심각도**: Minor (기능 제한)
- **컴포넌트**: `apps/firehub-web/src/components/ai/ChatInput.tsx:9` — `ACCEPT_ATTR`
- **발견**: 2026-04-23 (playwright-cli 탐색 테스트)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: `.xlsx` 파일 첨부 시 "지원하지 않는 파일 형식입니다: test.xlsx" 토스트 표시. CSV는 지원되나 XLSX는 미지원.

**현재 허용 형식**: `image/*, .pdf, .txt, .md, .json, .xml, .yaml, .yml, .csv, .docx`

**수정 방향**: `ACCEPT_ATTR`에 `.xlsx` 추가 + AI 에이전트 측 XLSX 파싱 처리 추가.

```typescript
// ChatInput.tsx:9
const ACCEPT_ATTR = 'image/*,.pdf,.txt,.md,.json,.xml,.yaml,.yml,.csv,.xlsx,.docx';
```

---

## Dataset

### [#5] 예약 컬럼명 제출 시 500 오류 + UI 에러 피드백 없음
- **심각도**: Major
- **컴포넌트**: `apps/firehub-api/.../DatasetColumnService` / `apps/firehub-web/src/pages/data/components/ColumnDialog.tsx`
- **발견**: 2026-04-23 (playwright-cli 탐색 테스트)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 필드 추가 다이얼로그에서 `id` 같은 예약어 컬럼명으로 제출 시 `POST /api/v1/datasets/{id}/columns` → 500 Internal Server Error 반환. UI에는 아무 에러 메시지가 표시되지 않고 다이얼로그만 열려 있음.

**재현**:
1. 데이터셋 상세 → 필드 탭
2. "필드 추가" 클릭
3. 필드명에 `id` 입력 후 "추가" 클릭
4. 응답 없음 (토스트/인라인 에러 없음), 네트워크 탭에서 500 확인

**원인 (로그 확인됨)**: `PSQLException: ERROR: column "id" of relation "customers" already exists` — `id`는 모든 데이터 테이블에 자동 생성되는 시스템 컬럼. 백엔드가 DDL 실행 전 예약어 검증을 하지 않아 DB 레벨 오류가 그대로 500으로 변환됨. 프론트엔드 `ColumnDialog`는 에러 케이스 처리 없음.

**수정 방향**:
- 백엔드: 예약 컬럼명 목록(`id`, `created_at` 등) 사전 검증 후 400 + 메시지 반환
- 프론트엔드: mutation error 핸들러에서 toast/인라인 에러 표시

---

### [#7] 행 수정/삭제 불가 — `id` vs `_id` 필드명 불일치 (Critical)
- **심각도**: Critical
- **컴포넌트**: `apps/firehub-web/src/pages/data/tabs/DatasetDataTab.tsx:261` / `DataTableRowService.java:203`
- **발견**: 2026-04-23 (playwright-cli 탐색 테스트)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 행 더블클릭 시 편집 다이얼로그가 "행 편집 (ID: )" (ID 공백)로 열림. 삭제 시 `rowId = undefined`로 API 호출됨.

**재현**:
1. 데이터셋 상세 → 데이터 탭
2. 행 추가 후 더블클릭 → "행 편집 (ID: )" 확인
3. 또는 행 체크박스 선택 후 삭제 → 실제 삭제 여부 불확실

**원인**: 백엔드 `queryData()` SQL이 `SELECT id, ...` 로 실행되어 JSON에 `"id"` 키로 반환되지만, 프론트엔드 `DatasetDataTab.tsx:261`은 `row['_id']`(언더스코어 접두사)를 읽음. 항상 `undefined`.

```
Backend → { "id": 1, "name": "홍길동" }
Frontend → row['_id'] === undefined  ❌
```

**추가 확인**: 행 선택 후 삭제 버튼 클릭 → 확인 다이얼로그 → 삭제 실행 → `POST /data/delete` 200 OK 반환 **BUT 행이 삭제되지 않음**. `rowIds=[null]`이 전송되어 백엔드가 무시함. 사용자에게는 성공처럼 보이는 **Silent failure**.

**수정 방향**: 백엔드에서 `id`를 `_id`로 alias하거나(`SELECT id AS "_id", ...`), 프론트엔드에서 `row['id']`로 변경.

---

### [#8] 데이터 내보내기 500 오류 + UI 에러 피드백 없음
- **심각도**: Major
- **컴포넌트**: `apps/firehub-api/.../DataExportService` / `apps/firehub-web/src/pages/data/components/ExportDialog.tsx`
- **발견**: 2026-04-23 (playwright-cli 탐색 테스트)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 내보내기 다이얼로그에서 CSV 포맷 선택 후 "내보내기" 클릭 → `POST /api/v1/datasets/{id}/export` → 500 Internal Server Error. UI에 에러 메시지 없고 다이얼로그만 열린 채 유지됨.

**재현**:
1. 데이터셋 상세 → 데이터 탭 → "내보내기" 클릭
2. CSV 포맷 선택 (기본값) → "내보내기" 클릭
3. 네트워크: estimate 200 OK → export 500 Internal Server Error
4. UI: 아무 반응 없음 (에러 토스트/인라인 에러 없음)

**원인 (로그 확인됨)**: `DataExportService.java:160`에서 `audit_log` 테이블 INSERT 시도 시 `ERROR: cannot execute INSERT in a read-only transaction` 발생. `exportDataset()` 메서드가 `@Transactional(readOnly=true)`로 선언된 트랜잭션 내에서 audit_log 기록을 시도하기 때문.

**수정 방향**:
- `DataExportService.exportDataset()`의 audit 로그 INSERT를 별도 트랜잭션으로 분리 (`@Transactional(propagation = REQUIRES_NEW)`) 또는 readOnly 제거
- 프론트엔드 에러 토스트 표시

---

### [#6] 필드 추가 다이얼로그 — "텍스트" 타입 옵션 중복 표시
- **심각도**: Minor (UX)
- **컴포넌트**: `apps/firehub-web/src/pages/data/components/ColumnDialog.tsx`
- **발견**: 2026-04-23 (playwright-cli 탐색 테스트)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 데이터 타입 드롭다운에 "텍스트" 항목이 2개 표시됨.

**재현**:
1. 데이터셋 상세 → 필드 탭 → "필드 추가" 클릭
2. 데이터 타입 드롭다운 확인 → "텍스트"가 두 번 나타남

**수정 방향**: `ColumnDialog.tsx` 타입 옵션 배열에서 중복 항목 제거.

---

### [#3] 차트 위젯 — 일부 도구 호출 상태가 "실행 중..."으로 고착
- **심각도**: Minor (Visual)
- **컴포넌트**: `apps/firehub-web/src/components/ai/MessageBubble.tsx` — `ToolCallBubble`
- **발견**: 2026-04-23 (playwright-cli 탐색 테스트)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 차트 생성 완료 후에도 "에이전트 실행" 컨테이너와 "데이터셋 목록 조회" 단계가 "실행 중..." (pulsing) 상태로 남음. 나머지 단계는 "✓ 완료" 정상 표시.

**원인**: `ToolCallBubble`이 `toolCall.result` 유무로 완료 여부를 판단 (line 155-171). 두 단계는 result가 없어 `hasResult === false` → "실행 중..." 표시:
- 에이전트 실행: 서브에이전트 래퍼로 result 없이 완료됨
- 데이터셋 목록 조회(`list_datasets`): SSE 스트림에서 해당 step의 result가 전달되지 않음

**수정 방향**: 응답 완료(스트리밍 종료) 후에는 result 없는 tool call도 "✓ 완료"로 표시하거나, 서버 측 SSE에서 list_datasets result를 함께 전송.

---

### [#33] Analytics 쿼리 실행 — public 스키마 접근으로 비밀번호 해시/암호화 자격증명 유출 가능
- **심각도**: Critical (보안)
- **컴포넌트**: `apps/firehub-api/src/main/java/com/smartfirehub/analytics/service/AnalyticsQueryExecutionService.java:93`
- **발견**: 2026-04-23 (API 탐색 테스트 — curl)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 인증된 사용자라면 누구든 Analytics 쿼리 실행 엔드포인트(`POST /api/v1/analytics/queries/{id}/execute`)를 통해 `public` 스키마 전체에 접근 가능. `public."user"` 테이블에서 모든 사용자의 bcrypt 비밀번호 해시를 조회할 수 있고, `public.api_connection` 테이블에서 외부 API 인증정보(AES-256-GCM 암호화)를 추출 가능.

**재현**:
```sql
-- attacker@test.com 계정으로 쿼리 생성 후 실행
SELECT id, username, password FROM public."user"
-- 결과: id=1, 비밀번호 해시 $2a$10$f3ZxpFcVX... 노출
```

**원인**: `executeDirectly()`가 `SET LOCAL search_path = 'data', 'public'`으로 설정 (line 93). 이는 미정규화된 테이블명만 `data` 스키마로 기본 지정하며, `public."user"` 처럼 스키마 명시 시 그대로 접근됨. `SqlValidationUtils.stripAndValidate()`는 DDL/다중 구문만 차단하고 스키마 접근은 차단하지 않음.

**수정 방향**:
1. (권장) DB 레이어: analytics 실행에 전용 DB 역할 생성 (`analytics_runner`), `data` 스키마에만 SELECT 권한 부여. `public` 스키마 접근 완전 차단.
2. (보완) SQL 파싱 레이어: `SqlValidationUtils`에서 `public.` 스키마 참조 및 `information_schema`, `pg_catalog` 접근 차단 패턴 추가.

---

### [#34] Analytics 쿼리 실행 — INSERT/UPDATE/DELETE로 public 스키마 데이터 변조·권한 상승 가능
- **심각도**: Critical (보안)
- **컴포넌트**: `apps/firehub-api/src/main/java/com/smartfirehub/analytics/service/AnalyticsQueryExecutionService.java:179`
- **발견**: 2026-04-23 (API 탐색 테스트 — curl)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 인증된 일반 사용자가 Analytics 쿼리 실행을 통해 `public.user_role`에 INSERT하여 자신에게 ADMIN 역할을 부여하는 권한 상승에 성공함 (재현 확인). `SqlValidationUtils`의 허용 키워드에 INSERT/UPDATE/DELETE가 포함되어 있어 `public` 스키마 데이터 변조 가능.

**재현**:
```sql
-- attacker@test.com이 자신(user_id=2)에게 ADMIN 역할 부여
INSERT INTO public.user_role (user_id, role_id)
SELECT 2, id FROM public.role WHERE name = 'ADMIN'
-- 결과: affectedRows=1, 재로그인 후 roles=['ADMIN','USER'] 확인됨
```

**원인**: `executeDirectly()` (line 179)가 `SELECT`가 아닌 경우 `dsl.execute(cleanSql)` 실행. SAVEPOINT 기반 롤백은 예외 발생 시에만 작동하고, 정상 DML 실행은 commit됨. `public` 스키마 격리 없음.

**수정 방향**:
1. (권장) DB 역할 분리: analytics_runner 역할에 `public` 스키마 DML 권한 미부여 (버그 #33과 동시 해결).
2. (즉시 적용 가능) `SqlValidationUtils.stripAndValidate()`에서 `readOnly=false` 모드에서도 `SELECT`/`WITH`만 허용하도록 변경, 또는 `executeDirectly()` 상단에 `readOnly=true` 강제 적용.
3. SAVEPOINT 외에도 analytics 실행 트랜잭션을 항상 rollback하는 방식 검토.

---

### [#35] CreateDashboardRequest — autoRefreshSeconds @Min(5) Bean Validation 미적용 (API 레벨 미검증)
- **심각도**: Minor
- **컴포넌트**: `apps/firehub-api/src/main/java/com/smartfirehub/analytics/dto/CreateDashboardRequest.java`
- **발견**: 2026-04-23 (API 탐색 테스트 — curl)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: `autoRefreshSeconds=4`, `autoRefreshSeconds=0`, `autoRefreshSeconds=-1` 모두 API 레벨에서 200/201 반환. 클라이언트의 `min={5}` HTML 속성만으로는 Axios 직접 호출이나 DevTools 조작을 막지 못함. (버그 #19의 API 레벨 확인)

**원인**: `CreateDashboardRequest` DTO에 `@Min(5)` Jakarta Bean Validation 어노테이션 없음.

**수정 방향**: `CreateDashboardRequest.autoRefreshSeconds` 필드에 `@Min(value = 5, message = "자동 새로고침은 최소 5초 이상이어야 합니다")` 추가.

---

### [#36] ChartBuilderPage — CANDLESTICK/HISTOGRAM/BOXPLOT 저장 불가 (yAxis 검증 오류)는 프론트엔드 전용 버그
- **심각도**: Minor (기존 #29 보완)
- **컴포넌트**: `apps/firehub-web/src/pages/analytics/ChartBuilderPage.tsx:407`
- **발견**: 2026-04-23 (API 탐색 테스트 — curl)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: `POST /api/v1/analytics/charts`에 `chartType=CANDLESTICK, config.yAxis=[]`로 직접 호출 시 201 성공. 백엔드는 빈 yAxis 허용함. 즉 버그 #29는 프론트엔드의 `handleSave` 검증 로직 문제.

**원인**: `ChartBuilderPage.tsx:407`의 `!config.xAxis || config.yAxis.length === 0` 조건이 CANDLESTICK에서 잘못 실패함. 백엔드는 문제 없음.

---

### [#17] ChartListPage — 공유됨 탭에서 전체 차트 반환 (sharedOnly 무시)
- **심각도**: Major
- **컴포넌트**: `apps/firehub-api/src/main/java/com/smartfirehub/analytics/controller/ChartController.java:24`
- **발견**: 2026-04-23 (Playwright 탐색 테스트 — 차트)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: "공유됨" 탭 클릭 시 `sharedOnly=true` 파라미터가 API로 전송되지만, 백엔드 Controller가 해당 파라미터를 `@RequestParam`으로 받지 않아 전체 차트가 반환됨. 공유된 차트만 표시되어야 함.

**재현**:
1. 분석 > 차트 목록 이동
2. "공유됨" 탭 클릭
3. 전체 차트가 그대로 표시됨 (공유된 3개만 나와야 함)

**원인**: `ChartController.java` GET `/analytics/charts` 핸들러에 `@RequestParam(required = false) Boolean sharedOnly` 파라미터가 없음. Service/Repository 레이어에도 필터 로직 없음.

**수정 방향**: `ChartController` 및 `ChartService.listCharts()`에 `sharedOnly` 파라미터 추가 후, jOOQ 쿼리에 `WHERE chart.is_shared = true` 조건 추가.

---

### [#18] DashboardListPage — 공유됨 탭에서 전체 대시보드 반환 (sharedOnly 무시)
- **심각도**: Major
- **컴포넌트**: `apps/firehub-api/src/main/java/com/smartfirehub/analytics/controller/AnalyticsDashboardController.java:37`
- **발견**: 2026-04-23 (Playwright 탐색 테스트 — 대시보드)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: "공유됨" 탭 클릭 시 `?sharedOnly=true` 파라미터로 API 호출하지만, 실제 응답에는 `isShared:false` 대시보드까지 모두 포함됨. "내 대시보드" 탭과 동일한 결과 표시.

**재현**:
1. `/analytics/dashboards` 접근
2. "공유됨" 탭 클릭
3. API `GET /api/v1/analytics/dashboards?sharedOnly=true` 호출됨 (확인)
4. 응답: `isShared:false`인 대시보드 4개 포함 (id=1,2,3,5)

**원인**: `AnalyticsDashboardController.listDashboards()`에 `@RequestParam sharedOnly` 파라미터 선언 없음. 프론트 파라미터 완전 무시. `SavedQueryController`는 동일 파라미터가 올바르게 구현됨 — 불일치.

**수정 방향**:
1. `AnalyticsDashboardController.listDashboards()`에 `@RequestParam(required = false) Boolean sharedOnly` 추가
2. `AnalyticsDashboardService.list()`에 파라미터 전달
3. `AnalyticsDashboardRepository.findAll()`에 `sharedOnly` 조건 추가: `sharedOnly=true`이면 `D_IS_SHARED.isTrue()` 단독 조건으로 교체

---

### [#19] CreateDashboardDialog — autoRefresh min=5 HTML 제약이 서버에서 미검증
- **심각도**: Minor
- **컴포넌트**: `apps/firehub-web/src/pages/analytics/DashboardListPage.tsx:76` + `apps/firehub-api/src/main/java/com/smartfirehub/analytics/dto/CreateDashboardRequest.java`
- **발견**: 2026-04-23 (Playwright 탐색 테스트 — 대시보드)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: autoRefresh 입력 필드에 `min={5}` HTML 속성이 있지만, React 상태를 직접 조작하거나 브라우저 DevTools로 4 이하 값을 입력해도 서버에서 그대로 수용됨. 실제로 "4초" 자동갱신 대시보드 생성 성공.

**재현**:
1. 새 대시보드 다이얼로그 → 자동갱신 필드에 React nativeInputValueSetter로 "4" 입력
2. 생성 클릭 → `/analytics/dashboards/6` 생성 완료
3. 에디터에서 "4초" 표시 확인

**원인**: `CreateDashboardRequest` DTO에 `@Min(5)` Bean Validation 어노테이션 없음. HTML `min` 속성은 브라우저 기본 폼 제출 시에만 작동하고 React 제어 컴포넌트 + Axios 전송에는 무효.

**수정 방향**: `CreateDashboardRequest.autoRefreshSeconds` 필드에 `@Min(5)` 또는 서비스 레이어 검증 추가. 프론트엔드에도 Zod `z.number().min(5)` 검증 추가 권장.

---

### [#20] 삭제 확인 메시지 전체 — 한국어 조사 기계적 처리 (을(를)/이(가))
- **심각도**: UX
- **컴포넌트**: `apps/firehub-web/src/components/ui/delete-confirm-dialog.tsx:36`, `DashboardListPage.tsx`, `ChartListPage.tsx:70` (토스트)
- **발견**: 2026-04-23 (Playwright 탐색 테스트 — 대시보드, 차트)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 삭제 확인 다이얼로그: `"새 차트" 차트을(를) 정말 삭제하시겠습니까?` / `"대시보드" 대시보드을(를) 정말 삭제하시겠습니까?` → "차트", "대시보드" 모두 받침 없으므로 "를"이 맞지만 "을(를)" 병기 표시. 토스트: `"새 차트"이(가) 삭제되었습니다.` → "이(가)" 병기 (올바른 조사는 "가").

**재현**: 차트/대시보드 삭제 클릭 → AlertDialog 및 완료 토스트 텍스트 확인

**원인**: `delete-confirm-dialog.tsx:36`에 `{entityName}을(를)` 하드코딩. 각 페이지 토스트에도 `이(가)` 병기 하드코딩. 조사를 동적으로 결정하는 유틸리티 없음.

**수정 방향**: 마지막 글자 받침 여부에 따라 조사를 선택하는 `getJosa(word, 을, 를)` 유틸리티 함수 적용. `delete-confirm-dialog.tsx`와 각 페이지 토스트에 일괄 적용. (버그 #16, #22와 동일 패턴)

---

### [#24] BarChartView/LineChartView — Y축 레이블 큰 숫자 잘림
- **심각도**: Minor (UX)
- **컴포넌트**: `apps/firehub-web/src/components/analytics/BarChartView.tsx:51`, `LineChartView.tsx`, `AreaChartView.tsx`
- **발견**: 2026-04-23 (Playwright 탐색 테스트 — 차트)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: Y축 레이블이 `80000000` (8000만) 이상 큰 숫자일 때 SVG 영역 밖으로 잘려 앞 자리수가 보이지 않음. `0000000` 형태로 표시됨.

**재현**:
1. 차트 빌더 → 매출 데이터(revenue, target, profit) 쿼리 실행
2. 막대 차트 또는 선 차트 렌더링 확인
3. Y축 레이블이 잘려서 보임

**원인**: `<YAxis>` 컴포넌트에 `width` 속성 미설정. recharts 기본값 60px 사용. `80000000` (9자, ~63px at 12px font)이 60px를 초과해 왼쪽으로 잘림. 또한 `margin={{ left: 0 }}`으로 추가 여백도 없음.

**수정 방향**: 데이터 최댓값 자릿수에 따라 동적으로 YAxis width 계산 (`Math.max(60, String(maxVal).length * 8 + 10)`), 또는 큰 숫자를 `1.2억`/`120M` 포맷으로 축약 표시.

---

### [#21] DashboardEditorPage — 새로고침 시 배치 + 개별 차트 API N+1 중복 호출
- **심각도**: Minor
- **컴포넌트**: `apps/firehub-web/src/hooks/queries/useAnalytics.ts:218` (useDashboardData), `apps/firehub-web/src/components/analytics/DashboardWidgetCard.tsx:45`
- **발견**: 2026-04-23 (Playwright 탐색 테스트 — 대시보드)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 새로고침 버튼 클릭 시 `GET /dashboards/{id}/data` (배치) 1회 + `GET /charts/{id}/data` N회 동시 호출됨 (위젯 4개 대시보드 → 5회). 배치만 호출되어야 정상.

**재현**:
1. 위젯 4개 있는 대시보드 에디터 진입
2. 툴바 새로고침 클릭
3. 네트워크: `dashboards/5/data` 1회 + `charts/{14,15,16,17}/data` 4회 동시 확인

**원인**: `useDashboardData()`에 `placeholderData` 옵션 없음. refetch 중 TanStack Query가 `data`를 `undefined`로 초기화하는 순간 `DashboardWidgetCard`의 `!batchData` 조건이 참이 되어 개별 차트 fetch가 트리거됨.

**수정 방향**: `useDashboardData()`에 `placeholderData: (previousData) => previousData` 추가. 또는 `useQuery`에 `keepPreviousData: true` (TanStack Query v4) 혹은 v5의 `placeholderData` 옵션 사용.

---

### [#25] HistogramChartView / BoxPlotChartView / GaugeChartView — 차트 색상 검은색 렌더링
- **심각도**: Major (시각적 오류)
- **컴포넌트**: `apps/firehub-web/src/components/analytics/recharts/HistogramChartView.tsx:42`, `BoxPlotChartView.tsx`, `GaugeChartView.tsx`
- **발견**: 2026-04-23 (Playwright 탐색 테스트 — 차트)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 히스토그램, 박스 플롯, 게이지 차트가 검은색으로 렌더링됨. 다른 차트는 정상 색상.

**재현**:
1. 차트 빌더 → 쿼리 실행 → 히스토그램/박스 플롯/게이지 타입 선택
2. 미리보기에서 막대/선이 검은색으로 표시됨

**원인**: 3개 파일에서 `fill="hsl(var(--chart-1, 220 70% 50%))"` 구문 사용. 그런데 CSS 테마에서 `--chart-1`가 `oklch(0.45 0.2 264)` 형태로 정의되어 있어 `hsl(oklch(...))` 가 유효하지 않은 구문이 됨 → 브라우저가 검은색 폴백 적용.

**수정 방향**: `hsl(var(--chart-1, ...))` 대신 다른 차트처럼 `DEFAULT_COLORS[0]` (즉 `'#8884d8'`) 사용, 또는 `var(--chart-1)` 직접 사용 (`hsl()` 래퍼 제거).

---

### [#22] CreateDashboardDialog — 이름 200자 초과 시 클라이언트 검증 없음
- **심각도**: Minor (UX)
- **컴포넌트**: `apps/firehub-web/src/pages/analytics/DashboardListPage.tsx:90` (이름 input)
- **발견**: 2026-04-23 (Playwright 탐색 테스트 — 대시보드)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 대시보드 이름 입력 필드에 `maxLength` 없어 303자 이상 입력 가능. 저장 시 DB 제약(`dashboard.name VARCHAR(200)`)에 걸려 400 반환되지만 클라이언트 피드백 없음 (다이얼로그 유지).

**원인**: `<input ... />` 에 `maxLength={200}` 없음. Zod 검증 스키마도 없음.

**수정 방향**: 이름 input에 `maxLength={200}` 추가. #16 (쿼리 이름)과 동일 패턴.

---

### [#26] FunnelChartView — X축이 문자열 컬럼일 때 레이블 NaN
- **심각도**: Major (렌더링 깨짐)
- **컴포넌트**: `apps/firehub-web/src/components/analytics/recharts/FunnelChartView.tsx:30`
- **발견**: 2026-04-23 (Playwright 탐색 테스트 — 차트)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 퍼널 차트에서 X축을 문자열 컬럼(예: product_name)으로 설정하면 모든 레이블이 `: NaN`으로 표시됨.

**재현**:
1. 차트 빌더 → 제품별 매출액 쿼리 실행
2. 퍼널 차트 선택 → X축: product_name, Y축: revenue
3. 레이블이 `화재감지 센서: 320,000,000` 대신 `: NaN`으로 표시됨

**원인**: `LabelList`에 `dataKey="value"` 없이 `content` 함수 내에서 `props.value` 접근. recharts Funnel이 LabelList content props에 `value`를 올바르게 전달하지 않아 undefined → `Number(undefined)` = NaN.

**수정 방향**: `<LabelList dataKey="value" position="center" content={...}>` 로 `dataKey` 추가, 또는 content 함수 내에서 `funnelData[props.index]?.value` 방식으로 직접 접근.

---

### [#23] 홈페이지 "새 대시보드" 버튼 — 레이블 혼동 (생성 다이얼로그 미열림)
- **심각도**: UX
- **컴포넌트**: `apps/firehub-web/src/pages/HomePage.tsx:260`
- **발견**: 2026-04-23 (Playwright 탐색 테스트 — 대시보드)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 홈페이지 "새 대시보드" 버튼 클릭 시 대시보드 목록 페이지(`/analytics/dashboards`)로만 이동. 생성 다이얼로그가 열리지 않음. 버튼 레이블이 "즉시 생성"을 암시하지만 실제로는 "목록으로 이동" 동작.

**원인**: `onClick={() => navigate('/analytics/dashboards')}` — 생성 다이얼로그 열기 없이 단순 navigate.

**수정 방향**: 버튼 레이블을 "대시보드 관리"로 변경하거나, 대시보드 목록 페이지에 도착 시 create 파라미터(`?create=true`)를 통해 다이얼로그 자동 열기 구현.

---

### [#29] ChartBuilderPage — 캔들스틱/히스토그램 차트 저장 불가 (yAxis 검증 오류)
- **심각도**: Major (핵심 기능 불가)
- **컴포넌트**: `apps/firehub-web/src/pages/analytics/ChartBuilderPage.tsx:407`
- **발견**: 2026-04-23 (Playwright 탐색 테스트 — 차트)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 캔들스틱 차트 선택 후 저장 시도 시 "X축과 Y축을 설정하세요" 검증에 걸려 저장 불가. 차트가 미리보기에서는 정상 렌더링되지만 저장 API 호출 자체가 안 됨.

**재현**:
1. 새 차트 → OHLC 쿼리 실행 → 캔들스틱 자동 추천/선택
2. 저장 버튼 → 이름 입력 → 저장 클릭
3. 에러 토스트 표시 후 저장 미진행

**원인**: `handleSave`에서 `!config.xAxis || config.yAxis.length === 0` 조건으로 검증. 캔들스틱은 `config.yAxis = []`(빈 배열)이고 open/high/low/close만 사용하므로 이 검증에 걸림. MAP 타입만 별도 처리되고 다른 특수 타입은 미처리.

**수정 방향**: `handleSave`의 yAxis 검증 로직에서 `'CANDLESTICK', 'HISTOGRAM', 'BOXPLOT'` 등 특수 타입을 예외 처리, 또는 각 타입별 필수 필드 존재 여부로 검증 확장.

---

### [#27] GaugeChartView — min/max 설정 UI 없음, 실제 데이터 범위 무시
- **심각도**: Major (렌더링 오류)
- **컴포넌트**: `apps/firehub-web/src/components/analytics/recharts/GaugeChartView.tsx:9-10`, `apps/firehub-web/src/components/analytics/AxisConfigPanel.tsx`
- **발견**: 2026-04-23 (Playwright 탐색 테스트 — 차트)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 게이지 차트는 `config.min ?? 0`, `config.max ?? 100` 기본값을 사용함. 실제 데이터 값이 182,000,000인 경우에도 범위가 "0 ~ 100"으로 표시되고 바늘은 최대값에 고정됨. AxisConfigPanel에서 min/max를 설정하는 UI가 없음.

**재현**:
1. 차트 빌더 → 월별 매출 현황 쿼리 실행
2. 게이지 차트 선택
3. 미리보기에서 "182,000,000 / 0 ~ 100" 표시 확인
4. 바늘이 실제 데이터 비율 반영 안 함

**원인**: `GaugeChartView.tsx`의 `min/max`는 `config.min ?? 0`, `config.max ?? 100`으로 기본값 처리. `AxisConfigPanel.tsx`에 게이지 전용 min/max 입력 UI가 없어 `config.min`, `config.max` 설정 불가.

**수정 방향**: `AxisConfigPanel`에 차트 타입이 GAUGE일 때 min/max 숫자 입력 필드 추가. 또는 데이터에서 자동으로 min/max를 계산하는 `autoRange` 옵션 제공.

---

### [#28] HistogramChartView — 비수치 X축 선택 시 빈 차트 표시 (피드백 없음)
- **심각도**: Minor (UX)
- **컴포넌트**: `apps/firehub-web/src/components/analytics/recharts/HistogramChartView.tsx:31-33`, `apps/firehub-web/src/components/analytics/AxisConfigPanel.tsx`
- **발견**: 2026-04-23 (Playwright 탐색 테스트 — 차트)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 히스토그램 차트에서 X축을 날짜/문자열 컬럼(예: `year_month`)으로 선택하면 차트 영역이 텅 빈 상태로 표시되고 아무런 안내 메시지가 없음. 사용자는 데이터가 없는 것인지 설정 오류인지 알 수 없음.

**재현**:
1. 차트 빌더 → 월별 매출 현황 쿼리 실행
2. 히스토그램 차트 선택 → X축 기본값 `year_month` (문자열)
3. 미리보기: 빈 흰 사각형만 표시됨

**원인**: `HistogramChartView.tsx:32-33`에서 `Number(d[config.xAxis])`로 변환 시 날짜 문자열은 NaN이 되어 `.filter(v => !isNaN(v))`에서 모두 제거됨. `values = []` → `binnedData = []` → 빈 BarChart 렌더링. 빈 데이터 처리 분기(`if (values.length === 0)`)가 없음.

**수정 방향**: `values.length === 0`일 때 `<div>X축은 숫자 컬럼이어야 합니다</div>` 안내 메시지 표시. AxisConfigPanel에서 HISTOGRAM 타입일 때 수치 컬럼만 X축 옵션으로 필터링하는 것도 권장.

---

### [#30] ChartBuilderPage SaveDialog — 이름 200자 초과 시 클라이언트 검증 없음
- **심각도**: Minor (UX)
- **컴포넌트**: `apps/firehub-web/src/pages/analytics/ChartBuilderPage.tsx:267`, `apps/firehub-web/src/components/analytics/ChartSaveDialog.tsx`
- **발견**: 2026-04-23 (Playwright 탐색 테스트 — 차트)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 차트 저장/수정 다이얼로그의 이름 입력 필드에 `maxLength` 속성이 없어 200자 초과 입력이 가능. 200자 초과로 수정 클릭 시 서버 400 "Validation failed" 토스트가 표시되지만 어떤 필드의 어떤 규칙인지 안내 없음.

**재현**:
1. 차트 빌더 → 저장 클릭 → 이름 입력란에 201자 입력
2. 저장/수정 버튼 클릭
3. 결과: "Validation failed" 토스트 (필드별 오류 메시지 없음)

**원인**: 이름 입력 `<input>`에 `maxLength={200}` 누락. `handleApiError`가 `errData.message` ("Validation failed")만 표시하고 `errData.errors.name` ("크기가 0에서 200 사이여야 합니다") 미표시.

**수정 방향**: 이름 `<input>`에 `maxLength={200}` 추가. `extractApiError`에서 `errors` 객체가 있을 때 첫 번째 필드 오류 메시지도 포함해 표시. (버그 #16, #22와 동일 패턴)

---

### [#31] DashboardEditorPage/DashboardListPage — 대시보드 메타데이터(이름/설명/자동갱신) 편집 UI 없음
- **심각도**: Major (기능 누락)
- **컴포넌트**: `apps/firehub-web/src/pages/analytics/DashboardListPage.tsx`, `apps/firehub-web/src/pages/analytics/DashboardEditorPage.tsx`
- **발견**: 2026-04-23 (Playwright 탐색 테스트 — 대시보드)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 대시보드 생성 후 이름, 설명, 자동갱신 주기를 변경할 UI가 없음. `CreateDashboardDialog`에서만 설정 가능하며, 이후에는 수정 불가.

**재현**:
1. 대시보드 목록 → 영업 현황 대시보드 "편집" 클릭 → 에디터 페이지 이동
2. 도구모음에 이름/설명/자동갱신 변경 버튼 없음 (읽기 전용 표시만)
3. 목록 페이지에도 메타데이터 수정 다이얼로그 없음 ("편집" 버튼 = 에디터로 이동)

**원인**: `DashboardListPage`에 `EditDashboardDialog` 구현 없음. `DashboardEditorPage` 도구모음에도 설정(⚙) 버튼/다이얼로그 없음.

**수정 방향**: 대시보드 목록에 "설정" 버튼(또는 행 오른쪽 클릭) → `EditDashboardDialog` 추가. `PUT /api/v1/analytics/dashboards/:id` 엔드포인트 활용. 또는 에디터 도구모음에 Settings 아이콘 버튼 추가.

---

### [#32] PipelineExecutionService — 실행 시작/완료 시간 UTC+9 이중 적용
- **심각도**: Major
- **컴포넌트**: `apps/firehub-api/src/main/java/com/smartfirehub/pipeline/service/PipelineExecutionService.java:235`, `apps/firehub-web/src/lib/formatters.ts:7`
- **발견**: 2026-04-23 (Playwright 탐색 테스트 — 파이프라인)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 실행 이력에서 시작 시간이 실제보다 9시간 늦게 표시됨. UTC 09:52에 실행했는데 `2026. 4. 24. 오전 3:52:00 (KST)`로 표시 — 실제 KST는 `2026. 4. 23. 오후 6:52:00`.

**재현**:
1. 파이프라인 에디터 → 실행 버튼 클릭
2. 실행 이력 탭 → 방금 생성된 실행 row의 시작 시간 확인
3. 브라우저 현재 시간(KST)과 비교 시 9시간 차이 발생

**원인**:
- 백엔드: `LocalDateTime.now()` → JVM 로컬 시간 (서버가 Asia/Seoul이면 KST) 반환 → JSON 직렬화 시 `"2026-04-23T18:52:00"` (timezone 정보 없음)
- 프론트엔드: `parseUtcDate()` → timezone 정보 없으면 `+Z` 붙여 UTC로 파싱 → `new Date("2026-04-23T18:52:00Z")` = UTC 18:52 = KST 다음날 03:52

결과: KST 시간을 UTC처럼 저장 → 프론트에서 UTC로 파싱 → 다시 KST로 변환 = +9h 이중 적용.

**수정 방향**:
1. 백엔드 (권장): `LocalDateTime.now(ZoneOffset.UTC)` 또는 `Instant.now()` 사용으로 UTC 기준 저장. 또는 Spring Jackson 설정에 `spring.jackson.time-zone=UTC` 추가.
2. 프론트엔드 (대안): `parseUtcDate()`에서 `+Z` 제거 (단, 서버가 실제 UTC를 보내도록 변경된 경우에만 적용).

---

### [#36] ReportTemplateDetailPage — 섹션 Key 유효성 오류 표시되나 저장 차단 안 됨
- **심각도**: Major
- **컴포넌트**: `apps/firehub-web/src/pages/ai-insights/components/SectionPropertyEditor.tsx:47-74`, `ReportTemplateDetailPage.tsx:113-142`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 리포트 양식)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 섹션 Key 필드에 `INVALID KEY WITH SPACES!` 등 snake_case 규칙 위반 값 입력 시 "영문 소문자, 숫자, 밑줄만 사용 가능" 에러가 표시되지만 저장 버튼 클릭 시 정상 저장됨. 백엔드에도 잘못된 키가 실제로 저장되어 AI 리포트 생성 시 키 참조 오류 가능성 있음.

**재현**:
1. /ai-insights/templates/:id → 편집 모드 진입
2. 섹션 클릭 → PropertyEditor 우측 패널
3. Key 필드에 `INVALID KEY WITH SPACES!` 입력
4. 저장 클릭 → "템플릿 수정됨" 토스트 표시, 저장 완료
5. 페이지 새로고침 → 잘못된 키가 그대로 유지

**원인**: `SectionPropertyEditor`의 `isValidKey` 체크가 UI 경고 표시만 할 뿐, `ReportTemplateDetailPage.handleSave`에서 `tree.sections` 저장 전 key 유효성 검사가 없음. `handleSave`는 `form.handleSubmit()`으로 RHF 스키마(이름/설명)만 검증하고 섹션 구조는 검증하지 않음.

**수정 방향**: `handleSave`에서 `tree.sections.every(s => /^[a-z][a-z0-9_]*$/.test(s.key))` 검증을 추가하거나, 저장 버튼을 `isValidKey`가 false인 섹션이 있으면 disabled 처리.

---

### [#37] ReportTemplateDetailPage — JSON 탭에서 잘못된 JSON 저장 시 편집 내용 무시 (사일런트)
- **심각도**: UX
- **컴포넌트**: `apps/firehub-web/src/pages/ai-insights/ReportTemplateDetailPage.tsx:113-142`, `components/TemplateJsonEditor.tsx`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 리포트 양식)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: JSON 탭에서 잘못된 JSON (`{INVALID JSON!!!}`)을 입력한 후 저장 버튼 클릭 시 "템플릿이 수정되었습니다." 토스트가 표시되나, 실제로는 JSON 편집 내용이 무시되고 이전 유효한 섹션 상태가 저장됨. 사용자는 자신의 JSON 편집이 저장된 것으로 착각할 수 있음.

**재현**:
1. 편집 모드 → JSON 탭
2. JSON 에디터의 내용을 `{INVALID JSON!!!}` 으로 교체
3. 저장 클릭 → "템플릿 수정됨" 토스트 표시
4. 실제로는 이전 유효 섹션(tree.sections)으로 저장됨

**원인**: `handleSave`가 `tree.sections`를 사용하며, JSON 탭의 `structureJson`과 `tree.sections` 간 동기화는 빌더 탭으로 전환 시에만 발생. JSON이 파싱 불가능할 경우 `tree.sections`은 변경되지 않음.

**수정 방향**: 저장 전 JSON 탭이 활성화 상태일 경우 `parseTemplateSections(structureJson)` 결과가 null이면 저장 차단 + "JSON이 올바르지 않아 저장할 수 없습니다." 오류 메시지 표시.

---

### [#38] ReportTemplateDetailPage — 섹션 Key 중복 허용 저장 (중복 키 차단 없음)
- **심각도**: Major
- **컴포넌트**: `apps/firehub-web/src/pages/ai-insights/components/SectionPropertyEditor.tsx`, `ReportTemplateDetailPage.tsx:113-142`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 리포트 양식)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 두 섹션에 동일한 Key (`text_1`)를 설정한 후 저장 시 저장이 성공되고, 백엔드에도 중복 키가 저장됨. 저장 시 6개의 콘솔 에러 발생. AI 리포트 생성 시 어느 섹션 내용인지 구분 불가로 결과 불일치 발생 가능성 있음.

**재현**:
1. 새 템플릿 생성 → 섹션 추가 (Text) × 2
2. 두 번째 섹션의 Key를 첫 번째와 동일하게 설정 (e.g., `text_1`)
3. 저장 → 성공 ("템플릿 수정됨" 토스트)
4. 뷰 모드에서 JSON 구조에 `"key": "text_1"` 중복 확인

**원인**: `handleSave`에서 `tree.sections`를 그대로 전송. 키 유일성 검증 없음. SortableContext(dnd-kit)는 key를 ID로 사용하기에 6개 콘솔 에러 발생.

**수정 방향**: `handleSave`에서 `tree.sections.map(s => s.key)` 중복 검사 추가. 중복 발견 시 저장 차단 + 에러 토스트 "섹션 Key가 중복되었습니다: {key}". 또는 `SectionPropertyEditor`에서 Key 변경 시 기존 다른 섹션과 중복 여부 실시간 표시.

---

### [#48] ProactiveJobListPage — 활성 토글 성공 시 피드백 없음 (에러만 표시)
- **심각도**: UX
- **컴포넌트**: `apps/firehub-web/src/pages/ai-insights/ProactiveJobListPage.tsx:81-86`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — AI 인사이트/스마트 작업)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 스마트 작업 목록에서 활성/비활성 토글 스위치를 클릭해 상태 변경이 성공해도 아무 토스트 알림이 표시되지 않음. 실패 시에만 `toast.error` 표시됨. 복제·지금 실행은 성공 토스트가 있어 일관성 없음.

**재현**:
1. /ai-insights/jobs 에서 비활성 작업의 토글 클릭
2. 상태가 "비활성" → "대기"로 변경됨
3. 성공 알림 없음 (복제·실행 버튼은 toast.success 표시)

**원인**: `handleToggle`의 `onSuccess` 콜백에 `toast.success` 없음:
```tsx
const handleToggle = (job: ProactiveJob, enabled: boolean) => {
  updateMutation.mutate({ id: job.id, data: { active: enabled } },
    { onError: () => toast.error('상태 변경에 실패했습니다.') }, // onSuccess 없음
  );
};
```

**수정 방향**: `onSuccess` 콜백 추가:
```tsx
{ onSuccess: () => toast.success(`작업이 ${enabled ? '활성화' : '비활성화'}되었습니다.`),
  onError: () => toast.error('상태 변경에 실패했습니다.') }
```

---

### [#49] ReportTemplateDetailPage — 설명 필드 500자 초과 시 Zod 검증 에러 미표시
- **심각도**: Minor (UX)
- **컴포넌트**: `apps/firehub-web/src/pages/ai-insights/ReportTemplateDetailPage.tsx:301-308`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — AI 인사이트/리포트 양식)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: 템플릿 설명 필드에 501자 이상 입력 후 저장 클릭 시 `reportTemplateSchema`의 `max(500)` 검증이 실패하여 저장이 차단되지만, UI에 에러 메시지가 표시되지 않음. 사용자는 왜 저장이 안 되는지 알 수 없음.

**재현**:
1. /ai-insights/templates/:id 편집 모드
2. 설명 필드에 501자 입력
3. 저장 클릭 → 화면 변화 없음, 에러 메시지 없음

**원인**: `ReportTemplateDetailPage.tsx:301-308` 의 설명 입력 필드에 이름 필드와 달리 에러 표시 코드 없음. `form.formState.errors.description` 미참조.

**수정 방향**: 설명 Input 아래에 에러 표시 추가:
```tsx
{form.formState.errors.description && (
  <p className="text-sm text-destructive">{form.formState.errors.description.message}</p>
)}
```

---

### [#56] PipelineEditorPage — 파이프라인 이름 100자 초과 시 서버 500 반환
- **심각도**: Major
- **컴포넌트**: `apps/firehub-web/src/pages/pipeline/components/EditorHeader.tsx`, `apps/firehub-api/src/main/java/com/smartfirehub/pipeline/dto/CreatePipelineRequest.java`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 파이프라인)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: 파이프라인 이름 입력란에 101자 이상 입력 후 저장 시 "파이프라인 저장에 실패했습니다" 토스트만 표시되고 서버 500 반환. DB `pipeline.name` 컬럼이 `VARCHAR(100)`이나 클라이언트/서버 모두 maxLength 검증 없음.

**재현**:
1. /pipelines/new 에서 파이프라인 이름에 201자 입력
2. 스텝 추가 후 저장 클릭 → HTTP 500, "파이프라인 저장에 실패했습니다"

**원인**: `CreatePipelineRequest.java`에 `@NotBlank`만 있고 `@Size(max=100)` 없음. 프론트엔드 input에 `maxLength` 속성 없음.

**수정 방향**: 백엔드 `@Size(max=100)` 추가 + 프론트엔드 `maxLength={100}` 추가. 동일 패턴: #16, #22, #30, #42.

---

### [#57] UserDetailPage — 관리자가 자신의 ADMIN 역할 제거 가능 (자기 잠금)
- **심각도**: Major (보안/운영)
- **컴포넌트**: `apps/firehub-web/src/pages/admin/UserDetailPage.tsx`, `apps/firehub-api/src/main/java/com/smartfirehub/user/`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 사용자 관리)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: 관리자(ADMIN 권한 보유자)가 자신의 사용자 상세 페이지에서 ADMIN 역할 체크박스를 해제하고 "역할 저장" 클릭 시 ADMIN 역할이 실제로 제거됨. 이후 관리자 페이지 접근 불가(403) → 자기 잠금(self-lockout) 발생.

**재현**:
1. /admin/users/1 (본인 계정 상세 페이지)
2. ADMIN 체크박스 해제 → "역할 저장" 클릭
3. ADMIN 역할 DB에서 삭제됨
4. /admin/roles 이동 시 홈 페이지로 리다이렉트됨 (권한 없음)

**원인**: 백엔드 역할 저장 API가 현재 사용자의 자기 ADMIN 역할 제거 여부를 검증하지 않음. 프론트엔드도 경고 없이 허용.

**수정 방향**:
- 백엔드: 현재 인증된 사용자와 수정 대상이 같고, ADMIN 역할을 제거하려 할 경우 400 반환
- 프론트엔드: 현재 로그인한 사용자의 ADMIN 체크박스 UI 비활성화 또는 경고 표시

---

### [#58] RoleManagementPage — 역할 이름에 특수문자 허용 (HTML 특수문자 포함)
- **심각도**: Minor
- **컴포넌트**: `apps/firehub-api/src/main/java/com/smartfirehub/role/dto/CreateRoleRequest.java:6`, `apps/firehub-web/src/pages/admin/RoleManagementPage.tsx`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 역할 관리)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: 역할 이름에 `<`, `>`, `#` 같은 특수문자 허용. "ROLE_#1<test>"가 성공적으로 생성됨. 백엔드 `@NotBlank @Size(max=50)` 검증만 있고 문자 패턴 검증 없음.

**재현**:
1. /admin/roles → 역할 추가
2. 역할 이름에 "ROLE_#1<test>" 입력 → 생성 성공

**원인**: `CreateRoleRequest.java:6`에 `@Pattern` 없음. 허용 문자 제한 없음.

**수정 방향**: `@Pattern(regexp = "[A-Z][A-Z0-9_]*", message = "역할 이름은 대문자, 숫자, 밑줄만 허용됩니다")` 추가. 프론트엔드에도 동일 패턴 검증 추가.

---

### [#59] ApiConnectionService — 전체 갱신 시 userId=0L FK 위반으로 500 오류
- **심각도**: Major
- **컴포넌트**: `apps/firehub-api/src/main/java/com/smartfirehub/apiconnection/service/ApiConnectionService.java:220`, `apps/firehub-api/src/main/resources/db/migration/V13__create_async_job.sql`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — API 연결 관리)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: API 연결 관리 화면에서 "전체 갱신" 버튼 클릭 시 `POST /api/v1/api-connections/refresh-all` → HTTP 500 반환. "An unexpected error occurred" 토스트 표시.

**재현**:
1. /admin/api-connections 진입
2. "전체 갱신" 버튼 클릭 → HTTP 500, "An unexpected error occurred"

**원인**: `ApiConnectionService.refreshAllAsync()`가 `asyncJobService.createJob(..., 0L, null)` 호출 (userId=0L 시스템 사용자). 그러나 `async_job.user_id`는 `REFERENCES "user"(id)` FK 제약이 있고, id=0인 사용자가 DB에 없어 INSERT 실패 → 500.

**수정 방향**:
- 옵션 A: `async_job.user_id`를 NULL 허용으로 변경 (시스템 작업용)
- 옵션 B: 컨트롤러에서 현재 인증 사용자 ID를 받아 서비스에 전달
- 옵션 C: id=0 시스템 사용자 레코드를 초기 데이터로 삽입 (non-login 계정)

---

### [#60] AuditLog — CREATE/DELETE/LOGIN/LOGOUT/EXECUTE 감사 로그 미수집
- **심각도**: Major
- **컴포넌트**: 전반적 (로그인·역할·사용자·파이프라인·데이터셋 등 서비스)
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 감사 로그)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료 (AuthService LOGIN/LOGOUT, DatasetService CREATE/DELETE, PipelineExecutionService EXECUTE 감사 로그 추가)

**현상**: 감사 로그 페이지에서 "생성", "수정", "삭제", "로그인", "로그아웃", "실행" 필터 선택 시 모두 "감사 로그가 없습니다." 표시. 오직 "임포트" 이벤트만 기록됨.

**재현**:
1. /admin/audit-logs 진입
2. 액션 필터에서 생성/수정/삭제/로그인/로그아웃/실행 선택 → 모두 빈 목록

**원인**: `auditLogService.log()` 호출이 `DataImportService`, `DataExportService`, `ApiConnectionNotifier`에만 존재. 인증(로그인/로그아웃), CRUD 연산(데이터셋·파이프라인·역할·사용자 생성·수정·삭제), 파이프라인 실행에 감사 로그 기록 없음.

**수정 방향**: 각 서비스의 주요 조작에 `auditLogService.log(...)` 호출 추가. 또는 Spring AOP 기반 `@AuditLogged` 애노테이션 구현으로 일관 적용.

---

### [#61] SmtpSettingsTab — SMTP 테스트 발송 실패 시에도 성공 toast 표시
- **심각도**: Minor
- **컴포넌트**: `apps/firehub-web/src/pages/admin/SmtpSettingsTab.tsx:80`, `apps/firehub-web/src/hooks/queries/useProactiveMessages.ts:262`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 이메일 설정)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: SMTP 호스트가 미설정(빈 값) 상태에서 "테스트 발송" 클릭 시 "테스트 이메일이 발송되었습니다." 성공 toast 표시. 실제 서버 응답은 `{"success": false, "message": "SMTP 호스트가 설정되지 않았습니다"}`.

**재현**:
1. /admin/settings → 이메일 탭
2. SMTP 호스트 비워둔 채 "테스트 발송" 클릭
3. → "테스트 이메일이 발송되었습니다." 성공 toast (실제로는 실패)

**원인**: `SmtpSettingsTab.tsx:80`에서 `onSuccess: () => toast.success('테스트 이메일이 발송되었습니다.')` 호출. HTTP 200이면 무조건 성공으로 처리. 서버 응답 본문의 `success: false` 미검사.

**수정 방향**: `onSuccess: (data) => { if (data.success) toast.success('SMTP 연결 성공'); else toast.error(data.message || '테스트 발송 실패'); }` 로 수정.

---

### [#62] ReportTemplatesTab — 템플릿 카드 설명이 길 경우 레이아웃 파괴
- **심각도**: UX
- **컴포넌트**: `apps/firehub-web/src/pages/admin/ReportTemplatesTab.tsx:44-45`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 리포트 양식)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: 커스텀 템플릿 카드에 긴 설명(수백 자)이 있는 경우 카드 높이가 과도하게 늘어나 레이아웃이 파괴됨. 다른 카드와 높이가 달라져 그리드 정렬 깨짐.

**재현**:
1. /ai-insights/templates 접속
2. "일간 요약 리포트 (사본)" 커스텀 템플릿 카드 확인
3. 설명 텍스트가 수백 자 반복되어 카드가 길게 늘어남

**원인**: `ReportTemplatesTab.tsx:44-45`에서 `<CardDescription className="text-xs">{t.description}</CardDescription>` 사용. CSS `line-clamp` 미적용으로 텍스트가 무한정 확장됨.

**수정 방향**: `<CardDescription className="text-xs line-clamp-2">{t.description}</CardDescription>` 로 `line-clamp-2` 추가.

---

### [#63] CreateProactiveJobRequest — 작업 이름 200자 초과 시 500 오류
- **심각도**: Minor
- **컴포넌트**: `apps/firehub-api/src/main/java/com/smartfirehub/proactive/dto/CreateProactiveJobRequest.java`, DB `proactive_job.name VARCHAR(200)`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 스마트 작업)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: 스마트 작업 생성 시 이름을 201자 이상 입력 후 생성 클릭 → "An unexpected error occurred" 토스트, HTTP 500.

**재현**:
1. /ai-insights/jobs/new 에서 이름에 201자 입력
2. 프롬프트 입력 후 "생성" → HTTP 500

**원인**: `CreateProactiveJobRequest`의 `name` 필드에 `@NotBlank`만 있고 `@Size(max=200)` 없음. 프론트엔드에도 `maxLength={200}` 없음. DB `VARCHAR(200)` 초과 시 예외 발생.

**수정 방향**: `@Size(max=200)` 추가. 프론트엔드에도 `maxLength={200}` 추가. 동일 패턴: #56.

---

### [#64] DashboardView — 라인차트 Y축 레이블 첫 자리 잘림
- **심각도**: UX
- **컴포넌트**: `apps/firehub-web/src/` (Dashboard 차트 위젯 컴포넌트)
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 대시보드)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: 대시보드 라인 차트의 Y축 레이블("80000000", "160000000" 등)이 왼쪽 컨테이너 경계에 잘려 "0000000"처럼 보임. 콘솔에 `width(-1) and height(-1) of chart should be greater than 0` 경고 8개 발생.

**재현**:
1. /analytics/dashboards/5 (영업 현황 대시보드) 접속
2. 월별 매출 및 목표 추이 차트의 Y축 확인 → 레이블 왼쪽 잘림

**원인**: Recharts 컨테이너 컴포넌트에 `minWidth` 또는 충분한 `margin.left` 미설정. 초기 렌더링 시 width=-1 오류와 함께 Y축 레이블 영역 계산 실패.

**수정 방향**: 차트 컨테이너에 `margin={{ left: 60 }}` 또는 Y축 `tickFormatter`로 단위 축약(예: 8천만 → 80M). Recharts `ResponsiveContainer`에 `minWidth` 설정.

---

### [#65] ChartBuilder — pg_sleep() 반환값 "[object Object]"로 표시
- **심각도**: Minor
- **컴포넌트**: `apps/firehub-web/src/pages/analytics/` (차트 빌더 미리보기 컴포넌트)
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 차트 빌더)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: 차트 빌더에서 `pg_sleep()` 함수를 포함한 쿼리 실행 시 미리보기 테이블의 셀 값이 "[object Object]"로 표시됨.

**재현**:
1. /analytics/charts/new → `<>'"&테스트` 쿼리 선택
2. "쿼리 실행" 클릭
3. 미리보기 테이블 → pg_sleep 컬럼 값이 "[object Object]"

**원인**: `pg_sleep()`의 반환 타입은 PostgreSQL에서 void. 백엔드가 이를 빈 JSON 객체 `{}` 또는 특수 타입으로 직렬화하여 반환. 프론트엔드가 객체를 문자열로 변환하지 않고 직접 렌더링.

**수정 방향**: 차트 빌더 테이블 렌더러에서 셀 값이 객체인 경우 `JSON.stringify()` 또는 `String()` 변환 처리 추가.

---

### [#66] AnalyticsQueryEditor — DELETE/UPDATE/INSERT 쿼리 실행 시 데이터 영구 삭제/수정 가능 (Critical 보안)
- **심각도**: Critical
- **컴포넌트**: `apps/firehub-api/src/main/java/com/smartfirehub/analytics/service/AnalyticsQueryExecutionService.java:184-190`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 저장된 쿼리 / SQL 편집기)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: Analytics SQL 편집기에서 `DELETE FROM customers` 실행 시 전체 6개 행이 영구 삭제됨. `SAVEPOINT`는 오류 시에만 롤백하므로 정상 실행된 DELETE/UPDATE/INSERT는 커밋됨. 실제 운영 데이터 손실 발생.

**재현**:
1. /analytics/queries/new 접속 (SQL 편집기)
2. `DELETE FROM customers` 입력
3. "실행" 클릭 → "7개 행이 처리되었습니다" 성공
4. DB 확인: `SELECT COUNT(*) FROM data.customers;` → 0행

**원인**: `executeDirectly()`에서 SELECT가 아닌 쿼리는 `dsl.execute(cleanSql)`로 실행 후 `RELEASE SAVEPOINT`로 커밋. `readOnly` 파라미터가 Web UI 엔드포인트에서 `false`로 전달되어 DELETE/UPDATE 허용. `SqlValidationUtils.stripAndValidate()`가 SELECT, INSERT, UPDATE, DELETE, WITH를 모두 허용.

**수정 방향**:
- Web UI 엔드포인트의 `readOnly=true` 강제화 또는 
- `SqlValidationUtils`에서 SELECT와 WITH만 허용하도록 수정
- 또는 `executeDirectly`의 else 분기에서 비SELECT 쿼리 ROLLBACK 처리

### [#67] ApiImportWizard — URL 빈값으로 다음 클릭 시 에러 메시지 미표시
- **심각도**: UX
- **컴포넌트**: `apps/firehub-web/src/pages/data/components/ApiImportWizard.tsx`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — API 임포트)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료 (#53 수정에 포함)

**현상**: URL 필드(필수)가 비어있는 상태에서 "다음" 버튼을 클릭해도 에러 메시지 없이 Step 1에 그대로 머문다.

**재현**:
1. 데이터 탭 > "API 가져오기" 버튼 클릭
2. URL 필드를 비운 채 "다음" 버튼 클릭
3. 에러 메시지 없이 Step 1에 유지됨

**원인**: URL 검증 실패 시 조용히 다음 단계 진행을 막되, `zodResolver`나 인라인 에러 표시 로직이 없음.

**수정 방향**: URL 필드에 `react-hook-form` 연동 후 제출 시 인라인 에러 표시.

---

### [#68] ApiImportWizard — 단일 객체 JSONPath 시 필드 자동 매핑 미작동
- **심각도**: Minor
- **컴포넌트**: `apps/firehub-web/src/pages/data/components/ApiImportWizard.tsx:173-179`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — API 임포트)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료 (columns 빈 경우 rows[0] 키를 fallback으로 사용)

**현상**: JSONPath가 단일 객체(배열이 아닌)를 반환할 때 테스트 호출 후 "자동으로 채워집니다" 안내와 달리 필드 매핑이 자동으로 채워지지 않는다.

**재현**:
1. URL: https://httpbin.org/json, JSONPath: $.slideshow 입력
2. "테스트 호출" 클릭 → "총 1개 행 추출" 성공
3. 필드 매핑: "매핑된 데이터가 없습니다" 표시 유지

**원인**: `ApiImportWizard.tsx:173` — `data.columns.length > 0` 조건이 단일 객체 응답 시 columns 배열이 빈 경우 false 반환.

**수정 방향**: 백엔드 미리보기 API에서 단일 객체도 columns 배열로 반환하거나, 프론트엔드에서 rows[0]의 키를 columns로 fallback.

### [#69] AiAgentSettingsTab — Temperature 범위 초과 입력 시 저장 버튼 활성화되나 저장 실패 (무응답)
- **심각도**: Minor
- **컴포넌트**: `apps/firehub-web/src/pages/admin/AiAgentSettingsTab.tsx`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 관리자 설정)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: Temperature 필드에 1.0을 초과하는 값(예: 2.0)을 입력 후 "저장" 클릭 시, 저장 성공 토스트도 없고 에러 메시지도 없이 아무런 피드백 없이 종료. 새로고침 시 원래 값으로 복원됨.

**재현**:
1. /admin/settings → AI 에이전트 탭
2. Temperature에 "2.0" 입력
3. "저장" 클릭 → 반응 없음 (토스트/에러 모두 미표시)
4. 새로고침 → 0.8로 복원

**원인**: Zod 스키마가 `max(1.0)` 검증하지만 실패 시 에러 메시지를 표시하지 않음. `handleSubmit`의 에러 처리 미구현.

**수정 방향**: 폼 검증 실패 시 각 필드별 인라인 에러 메시지 표시.

---

### [#70] AnalyticsQueryEditor — public."user" 테이블 직접 접근으로 bcrypt 패스워드 해시 노출 (Critical 보안)
- **심각도**: Critical
- **컴포넌트**: `apps/firehub-api/src/main/java/com/smartfirehub/analytics/service/AnalyticsQueryExecutionService.java`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — SQL 편집기)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료 (#33/#34 수정에 포함 — public 스키마 접근 차단 이미 적용)

**현상**: Analytics SQL 편집기에서 `SELECT id, username, password FROM public."user" LIMIT 5` 실행 시 시스템 사용자 테이블의 bcrypt 패스워드 해시가 반환됨. 공격자는 이 해시를 오프라인 크래킹 도구로 평문 패스워드 복원 시도 가능.

확인된 데이터:
```
id=1, username=bluleo78@gmail.com, password=$2a$10$f3ZxpFcVXmwKLYBWn2Hf1ud25TMiPrLgAnInylYa6pa5JfQ.BLm4C
```

**재현**:
1. /analytics/queries/new 접속
2. `SELECT id, username, password FROM public."user" LIMIT 5` 실행
3. 사용자 이메일 + bcrypt 해시 반환

**원인**: `AnalyticsQueryExecutionService`와 `DataTableQueryService` 모두 `public` 스키마 명시 접근 허용. `DataTableQueryService`는 `SET LOCAL search_path = 'data'`를 사용하지만 `SELECT * FROM public."user"`처럼 스키마를 명시하면 우회 가능. `#66` (DML 허용)과 결합 시 `UPDATE public."user" SET password='...'` 로 관리자 계정 탈취 가능.

**영향 범위**: `/api/v1/analytics/queries/execute` (Analytics SQL 에디터) AND `/api/v1/datasets/:id/query` (데이터셋 내 SQL 에디터) 모두 취약.

> 데이터셋 내 SQL 에디터에서도 `DELETE FROM customers WHERE 1=0` 실행 시 "0행이 영향 받았습니다." 반환 — DML 실행 가능 확인.

**수정 방향**:
- 쿼리 실행 전 `public` 스키마 접근 차단 (화이트리스트: `data` 스키마만 허용)
- PostgreSQL Row Level Security (RLS) 또는 별도 read-only 분석용 DB 사용자 생성 (data 스키마만 접근 가능한 권한 부여)

---

### [#75] 알림 채널 설정 — 카카오/Slack 채널 "미연결" 상태에서 스위치 활성화 상태 불일치
- **심각도**: UX
- **컴포넌트**: `apps/firehub-web/src/pages/settings/ChannelSettingsPage.tsx`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 알림 채널 설정)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: 카카오 알림톡과 Slack 채널이 "미연결" 상태임에도 토글 스위치가 `checked` 상태로 표시됨. 이메일 채널은 "미연결" + unchecked로 정상 표시됨. 카카오/Slack의 기본값이 활성화(checked)로 설정되어 있어 미연결 채널이 활성화된 것처럼 보이는 혼란 발생.

**재현**:
1. /settings/channels 접속
2. 카카오 알림톡 → "미연결" + 스위치 ON (checked)
3. Slack → "미연결" + 스위치 ON (checked)
4. 이메일 → "미연결" + 스위치 OFF (unchecked) — 올바른 동작

**수정 방향**: 채널이 "미연결" 상태일 때는 스위치를 unchecked + disabled로 표시하거나, 기본값을 false로 설정.

---

### [#76] 카카오/Slack OAuth 연동 — 팝업 탭에서 401 인증 오류
- **심각도**: Major
- **컴포넌트**: `apps/firehub-api/src/main/java/com/smartfirehub/oauth/OAuthController.java`, `apps/firehub-web/src/pages/settings/ChannelSettingsPage.tsx`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 알림 채널 설정)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료

**현상**: "연동하기" 클릭 시 `/api/v1/oauth/kakao/start` 새 탭이 열리지만 `401 Unauthorized: Full authentication is required` 에러 반환. OAuth 연동 불가.

**재현**:
1. /settings/channels → 카카오 "연동하기" 클릭
2. 새 탭: `/api/v1/oauth/kakao/start` → `{"error":"Unauthorized","message":"Full authentication is required..."}`

**원인**: 새 탭/팝업은 Bearer 토큰을 HTTP 헤더에 포함하지 않음. 인증이 필요한 OAuth 시작 엔드포인트가 팝업에서 호출될 때 인증 정보 전달 방법 없음.

**수정 방향**: OAuth 시작 URL에 short-lived token을 query parameter로 전달하거나, OAuth 시작 전 CSRF state token을 세션/쿠키로 관리.

---

### [#71] AdminUserDetailPage — 관리자가 자신의 ADMIN 역할을 제거해 스스로 관리자 권한 박탈 가능
- **심각도**: Major
- **컴포넌트**: `apps/firehub-api/src/main/java/com/smartfirehub/admin/service/AdminUserService.java`, `apps/firehub-web/src/pages/admin/AdminUserDetailPage.tsx`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 관리자 사용자 관리)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료 (#57 수정에 포함 — UserDetailPage.tsx 동일 파일)

**현상**: 관리자가 본인의 사용자 상세 페이지(/admin/users/:myId)에서 ADMIN 역할을 체크 해제하고 "역할 저장" 클릭 시, 확인 다이얼로그나 경고 없이 즉시 자신의 ADMIN 권한이 제거됨. 이후 모든 /admin/* 경로 접근이 차단되어 관리자 기능 이용 불가 상태가 됨.

**재현**:
1. /admin/users/1 접속
2. "역할 할당" 섹션에서 ADMIN 체크 해제
3. "역할 저장" 클릭 → 즉시 저장됨 (경고 없음)
4. /admin/settings 이동 시도 → "/" 로 리다이렉트 (접근 차단)
5. DB에서 수동 복구 필요

**원인**: 역할 저장 로직에서 "현재 로그인한 사용자의 역할에서 ADMIN을 제거하는 경우" 예외 처리 없음.

**수정 방향**:
- 프론트엔드: 자신의 ADMIN 역할 체크 해제 시도 시 경고 다이얼로그 표시
- 백엔드: 마지막 ADMIN 사용자의 ADMIN 역할 제거 시 400 에러 반환

---

### [#73] AdminUserDetailPage — 관리자가 본인 계정을 경고 없이 비활성화 가능
- **심각도**: Major
- **컴포넌트**: `apps/firehub-web/src/pages/admin/AdminUserDetailPage.tsx`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 관리자 사용자 관리)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: 관리자가 자신의 사용자 상세 페이지에서 활성화 토글 스위치를 클릭하면, 경고 없이 즉시 본인 계정이 비활성화됨. 비활성화된 계정으로는 로그인이 불가능하여 관리자 접근 불가 상태가 될 수 있음.

**재현**:
1. /admin/users/1 (자신의 사용자 ID) 접속
2. "활성 상태" 섹션의 스위치 토글 클릭
3. "사용자가 비활성화되었습니다." 즉시 표시 (경고 없음)
4. 재로그인 시 계정 비활성화로 접근 불가

**원인**: 활성화 토글에 "현재 로그인한 사용자의 계정인지" 확인 로직 없음.

**수정 방향**:
- 자신의 계정 비활성화 시도 시 확인 다이얼로그 표시 또는 차단
- "마지막 ADMIN 계정 비활성화" 시 백엔드에서 400 반환

---

### [#72] ProfilePage — 비밀번호/이메일 서버 검증 실패 시 "Validation failed" 영문 메시지 표시
- **심각도**: UX
- **컴포넌트**: `apps/firehub-web/src/pages/ProfilePage.tsx`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 프로필)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: 프로필 페이지에서 잘못된 현재 비밀번호 입력 후 "비밀번호 변경" 클릭 시, 또는 잘못된 이메일 형식으로 "저장" 클릭 시, 서버 에러 메시지 "Validation failed"가 영문 그대로 표시됨. 한국어 애플리케이션에서 사용자 혼란 유발.

**재현**:
1. /profile → 비밀번호 변경 섹션
2. 현재 비밀번호에 잘못된 값 입력 → "비밀번호 변경" → "Validation failed" 표시
3. 이메일 필드에 "not-a-valid-email" 입력 → "저장" → "Validation failed" 표시

**원인**: 프론트엔드에서 `react-hook-form` + `zodResolver`를 통한 클라이언트 검증이 없거나, API 에러 메시지를 그대로 표시하는 처리 로직.

**수정 방향**:
- 이메일 필드에 `z.string().email()` Zod 검증 추가 (클라이언트 사전 검증)
- 비밀번호 변경 API 에러 시 "현재 비밀번호가 올바르지 않습니다" 한국어 메시지로 매핑

---

### [#74] 존재하지 않는 URL 접근 시 빈 화면 표시 (404 페이지 없음)
- **심각도**: UX
- **컴포넌트**: `apps/firehub-web/src/App.tsx` (라우터 설정)
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 라우팅)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: `/nonexistent-page` 등 정의되지 않은 경로 접근 시 빈 화면이 표시됨. "페이지를 찾을 수 없습니다" 메시지나 홈으로 돌아가는 버튼 등 사용자 안내가 없음.

**재현**:
1. `/nonexistent-page` URL 직접 접속
2. 빈 흰 화면 표시 (내비게이션 바도 없음)

**원인**: React Router `<Routes>`에 `path="*"` fallback 라우트가 없거나 404 컴포넌트가 미정의.

**수정 방향**: `<Route path="*" element={<NotFoundPage />} />`로 Catch-all 라우트 추가

---

### [#77] MapChartView — 비공간 데이터로 지도 차트 선택 시 페이지 전체 크래시 (빈 흰 화면)
- **심각도**: Major
- **컴포넌트**: `apps/firehub-web/src/components/analytics/MapChartView.tsx:17`, `apps/firehub-web/src/lib/geo-utils.ts:20`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 차트 빌더)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: 차트 빌더에서 비공간 쿼리(일반 SQL 결과)로 쿼리 실행 후 "지도" 차트 타입을 클릭하면 페이지 전체가 빈 흰 화면으로 크래시. 내비게이션도 사라지며 페이지 복구 불가 (새로고침 필요).

**재현**:
1. /analytics/charts/new 이동
2. 비공간 쿼리 선택 (예: "월별 매출 현황 쿼리")
3. "쿼리 실행" 클릭 → 데이터 로드
4. 차트 타입에서 "지도" 클릭
5. 페이지 전체가 빈 흰 화면으로 크래시

**원인**: `MapChartView.tsx`의 `useMemo`에서 `toFeatureCollection(data, spatialColumn)` 호출 시 `geo-utils.ts`의 `JSON.parse(rawGeom)`가 비JSON 문자열(예: `"2024-01"`)에 대해 `SyntaxError: Unexpected non-whitespace character after JSON at position 4`를 throw. React 에러 바운더리 없어 컴포넌트 트리 전체 unmount → 빈 화면.

**수정 방향**:
1. `geo-utils.ts:20` `JSON.parse` 호출을 try/catch로 감싸서 null 반환
2. `MapChartView`에 ErrorBoundary 추가 또는 상위 `ChartBuilderPage`에서 래핑
3. `spatialColumn`이 GEOMETRY 타입 컬럼이 아닐 경우 사용자에게 경고 표시

---

### [#78] SmtpSettingsTab — SMTP 테스트 발송 실패 시 성공 토스트 표시 (응답 success 필드 미확인)
- **심각도**: Major
- **컴포넌트**: `apps/firehub-web/src/pages/admin/SmtpSettingsTab.tsx:79-82`, `apps/firehub-web/src/hooks/queries/useProactiveMessages.ts:262-266`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 관리자 설정)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료 (SettingsPage.tsx에서 `data?.success === false` 확인 후 `toast.error` 호출로 수정)

**현상**: SMTP 테스트 발송 버튼 클릭 시 실제 이메일 전송 실패(잘못된 SMTP 인증 정보 등)여도 "테스트 이메일이 발송되었습니다." 성공 토스트가 표시됨. API는 HTTP 200으로 `{"success": false, "message": "535-5.7.8 Username and Password not accepted..."}` 반환하지만 프론트엔드는 HTTP 상태만 확인하고 응답 body의 `success` 필드를 무시.

**재현**:
1. /admin/settings → 이메일 탭
2. 잘못된 SMTP 인증 정보 입력 (예: smtp.gmail.com, test@gmail.com, testpassword)
3. "저장" 클릭
4. "테스트 발송" 클릭
5. "테스트 이메일이 발송되었습니다." 성공 토스트 표시 (실제 전송 실패)

**원인**: `useTestSmtpSettings` 훅의 `mutationFn`이 API 응답 body의 `success` 필드를 검사하지 않음. `client.post('/settings/smtp/test')`가 HTTP 200을 반환하면 `onSuccess` 핸들러가 무조건 성공 처리.

**수정 방향**:
- `useTestSmtpSettings` mutationFn에서 `response.data.success === false`이면 `throw new Error(response.data.message)` 처리
- 또는 `SmtpSettingsTab.tsx` onSuccess에서 data를 받아 `data.success`를 확인하고 실패 시 `toast.error` 호출

---

### [#79] 차트 Y축 레이블 — 대형 숫자 포맷 없어 "0000000"으로 잘려 표시
- **심각도**: Minor
- **컴포넌트**: `apps/firehub-web/src/components/analytics/` (recharts 차트 컴포넌트들)
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 대시보드/차트 빌더)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료 (`chart-styles.ts`의 `formatYAxisTick` 함수로 만/억 단위 포맷 적용)

**현상**: 금액 데이터(수천만~수억 원) 차트의 Y축 레이블이 "0000000"으로 표시됨. 실제 값은 "80000000", "160000000" 등이나 Y축 레이블 컨테이너 너비 부족으로 선행 숫자가 잘려 모두 동일하게 "0000000"처럼 보임.

**재현**:
1. /analytics/dashboards → 영업 현황 대시보드 클릭
2. "월별 매출 및 목표 추이" 차트 Y축 확인
3. Y축 레이블이 "0000000" × 4개로 표시 (판독 불가)

**원인**: Recharts Y축 tickFormatter 미설정 → 원시 숫자(80000000)를 문자열로 표시. Y축 너비가 8자 이상을 표시하기엔 좁아서 선행 "8" 또는 "1" 등이 잘림.

**수정 방향**: Y축 tickFormatter에 숫자 포맷 함수 적용:
- `(v) => v >= 1e8 ? (v/1e8).toFixed(1)+'억' : v >= 1e4 ? (v/1e4).toFixed(0)+'만' : v.toLocaleString()`
- 또는 recharts `width` 속성 증가로 레이블 공간 확보

---

### [#80] ApiConnectionDetailPage — 연결 이름 빈값으로 저장 시 무응답 (에러 메시지 없음)
- **심각도**: Minor
- **컴포넌트**: `apps/firehub-web/src/pages/admin/ApiConnectionDetailPage.tsx:74`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — API 연결 관리)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: API 연결 상세 페이지에서 연결 이름을 비운 후 "저장" 클릭 시 아무 반응 없음. 저장이 취소되지만 사용자에게 오류 메시지나 피드백이 전혀 표시되지 않음.

**재현**:
1. /admin/api-connections → 연결 클릭
2. 연결 이름 필드 내용 삭제
3. "저장" 클릭 → 화면 변화 없음, 저장 안 됨

**원인**: `ApiConnectionDetailPage.tsx:74` - `if (!id || !name.trim()) return;` 빈 이름 검증 후 `toast.error()` 없이 바로 return.

**수정 방향**: 빈 이름 시 `toast.error('연결 이름은 필수입니다.')` 추가 또는 input 필드에 `aria-invalid`와 에러 메시지 표시

---

### [#81] ApiConnectionService — 전체 갱신 시 async_job 외래키 위반 500 오류
- **심각도**: Major
- **컴포넌트**: `apps/firehub-api/src/main/java/com/smartfirehub/apiconnection/service/ApiConnectionService.java:216-218`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — API 연결 관리)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료 (#59 수정에 포함 — refreshAllAsync에 userId 전달)

**현상**: API 연결 관리 페이지의 "전체 갱신" 버튼 클릭 시 항상 500 에러 발생. UI에 "An unexpected error occurred" 토스트 표시.

**재현**:
1. /admin/api-connections 이동
2. "전체 갱신" 버튼 클릭 → 500 에러

**원인**:
```java
String jobId = asyncJobService.createJob(
    "API_CONNECTION_REFRESH_ALL", "api_connection", "all",
    0L,   // ← 시스템 userId로 0L 사용
    null);
```
`async_job.user_id` 컬럼에 FK 제약조건(`async_job_user_id_fkey`)이 있어 `user` 테이블에 존재하지 않는 ID 0을 삽입하면 `PSQLException: insert or update on table "async_job" violates foreign key constraint` 발생.

**수정 방향**:
- `user_id`를 nullable로 변경 (시스템 Job은 NULL) — Flyway 마이그레이션 필요
- 또는 `ApiConnectionController.refreshAll()`에서 `Authentication`을 주입해 실제 userId 사용
- 단순 해결책: `refreshAll(Authentication auth)` 파라미터 추가 후 실제 userId 전달

---

### [#82] RoleDetailPage — 시스템 역할(ADMIN/USER) 권한 수정 허용
- **심각도**: Major
- **컴포넌트**: `apps/firehub-web/src/pages/admin/RoleDetailPage.tsx:207-225`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 역할 관리)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: ADMIN, USER 등 시스템 역할의 권한 체크박스가 비활성화되지 않아 ADMIN 역할에서 모든 권한을 제거하는 것이 가능함.

**재현**:
1. /admin/roles → ADMIN 행 클릭
2. "권한 할당" 섹션에서 체크박스 해제
3. "권한 저장" 버튼 클릭 가능

**원인**: 역할 이름 Input은 `disabled={role.isSystem}`로 보호되나, 권한 Checkbox는 `isSystem` 확인 없이 렌더링됨.

**수정 방향**: 권한 체크박스와 "권한 저장" 버튼에 `disabled={role.isSystem}` 추가; 또는 `isSystem=true`인 경우 "권한은 시스템에서 관리됩니다." 안내 문구로 교체

---

### [#83] ExecutionStepPanel — 파이프라인 스텝 소요 시간 1초 미만 실행 시 "0s" 표시
- **심각도**: UX
- **컴포넌트**: `apps/firehub-web/src/pages/pipeline/components/ExecutionStepPanel.tsx:17-27`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 파이프라인 실행)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료

**현상**: API 호출 스텝이 790ms~816ms에 완료됐으나 실행 이력 테이블과 스텝 상세 패널 모두 "소요: 0s"로 표시됨.

**재현**:
1. /pipelines/4 → 실행 이력 탭
2. 최근 실행 행 클릭 → 스텝 선택
3. 소요 시간이 "0s"로 표시되나, 스텝 로그에 "duration=790ms" 기록됨

**원인**: `formatDuration()` 함수에서 `Math.floor((completedAt - startedAt) / 1000)` 계산 시 1초 미만은 0으로 내림 처리됨.

**수정 방향**: 1초 미만 시 "< 1s" 또는 밀리초(예: "790ms") 표시; `if (totalSeconds === 0) return '< 1s';` 추가

---

### [#84] QueryEditorPage — information_schema 접근 허용으로 전체 DB 스키마 노출
- **심각도**: Critical
- **컴포넌트**: 백엔드 SQL 실행 서비스 (쿼리 허용 목록 관련)
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — SQL 보안)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료 (#33/#34 수정에 포함 — INFORMATION_SCHEMA 문자열 차단 이미 적용)

**현상**: 분석 쿼리 에디터에서 `SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' LIMIT 10` 실행 시 10개 테이블명 반환: `user`, `role`, `role_permission`, `permission`, `user_role`, `dataset_category` 등 전체 내부 테이블 스키마 노출.

**재현**:
1. /analytics/queries/new 진입
2. `SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'` 입력 후 실행
3. DB 내 전체 테이블 목록 반환됨

**연관**: 버그 #33, #70 (public."user" 접근 → bcrypt 해시 조회)의 상위 원인. information_schema 접근 자체를 차단해야 `public."user"` 접근도 방지됨.

**수정 방향**: 허용 스키마 목록에서 `information_schema`, `pg_catalog` 등 시스템 스키마 제외; 또는 `search_path` 설정으로 `public` 스키마만 노출

---

### [#85] QueryEditorPage — pg_catalog.pg_shadow 접근 허용으로 DB 사용자 비밀번호 해시 노출
- **심각도**: Critical
- **컴포넌트**: 백엔드 SQL 실행 서비스 (스키마 접근 제어 미흡)
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — SQL 보안)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료 (#33/#34 수정에 포함 — PG_CATALOG 문자열 차단 이미 적용)
- **증거**: `snapshots/critical-pg-shadow-exposure.png`

**현상**: 분석 쿼리 에디터에서 `SELECT usename, passwd FROM pg_catalog.pg_shadow LIMIT 5` 실행 시 PostgreSQL DB 사용자(`app`, `pipeline_executor`)의 SCRAM-SHA-256 비밀번호 해시 완전 노출.

**재현**:
1. /analytics/queries/new 진입
2. `SELECT usename, passwd FROM pg_catalog.pg_shadow LIMIT 5` 입력 후 실행
3. DB 사용자명과 SCRAM-SHA-256 해시값 반환됨

**영향**: 해시 노출 시 오프라인 사전 공격(dictionary attack)으로 DB 패스워드 복구 가능 → DB 직접 접근 위험.

**연관**: 버그 #84 (information_schema 노출), #70 (application user 비밀번호 해시 노출)의 근본 원인과 동일 — 시스템 카탈로그 접근 미차단.

**수정 방향**:
1. DB 사용자를 `SELECT` 전용 역할로 분리하여 `pg_shadow` 접근 권한 제거 (`GRANT SELECT ON TABLE` 최소화)
2. SQL 실행 전 `set_config('search_path', 'app_schema', false)` 및 시스템 카탈로그 접근 차단
3. jOOQ의 허용 스키마 목록에서 `pg_catalog`, `information_schema` 제외

---

### [#86] QueryEditorPage — pg_read_file()로 서버 파일 읽기 허용 (RFR 취약점)
- **심각도**: Critical
- **컴포넌트**: 백엔드 SQL 실행 서비스 (PostgreSQL 함수 접근 제어 미흡)
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — SQL 보안)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료 (AnalyticsQueryExecutionService + DataTableQueryService에 PG_READ_FILE 문자열 차단 추가)
- **증거**: `snapshots/critical-file-read.png`

**현상**: 분석 쿼리 에디터에서 `SELECT pg_read_file('/etc/passwd')` 실행 시 서버의 `/etc/passwd` 내용 전체 반환. 인증된 사용자라면 서버 파일 시스템 읽기 가능.

**재현**:
1. /analytics/queries/new 진입
2. `SELECT pg_read_file('/etc/passwd')` 입력 후 실행
3. 서버 파일 내용 반환됨 (root, daemon, postgres 등 시스템 계정 정보)

**영향**:
- 서버 파일 읽기 → 환경변수 파일(.env), SSH 키, 인증서 등 민감 파일 노출 가능
- `pg_read_file('/proc/self/environ')` 등으로 애플리케이션 환경변수 추출 가능

**연관**: 버그 #84 (information_schema), #85 (pg_shadow)와 동일 근본 원인 — DB 사용자 권한 과다 부여.

**수정 방향**:
1. 앱 DB 사용자에서 SUPERUSER 권한 제거 (pg_read_file은 SUPERUSER만 가능)
2. 최소 권한 원칙: 앱 사용자에게 `GRANT SELECT, INSERT, UPDATE, DELETE ON app_tables` 만 부여
3. `REVOKE EXECUTE ON FUNCTION pg_read_file FROM PUBLIC` 실행

---

### [#87] DB 사용자 `app` SUPERUSER 권한 부여 — 전체 DB/파일시스템 노출
- **심각도**: Critical
- **컴포넌트**: Docker/배포 설정 (데이터베이스 사용자 권한 설정)
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — SQL 보안)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료 (V56 Flyway migration으로 app 사용자 NOSUPERUSER 적용)

**현상**: 애플리케이션 DB 사용자 `app`이 `rolsuper=true` (PostgreSQL SUPERUSER) 권한으로 실행됨. 버그 #84~#86의 근본 원인.

**재현**:
```sql
SELECT rolname, rolsuper, rolcreaterole, rolcreatedb FROM pg_catalog.pg_roles WHERE rolname = 'app';
-- 결과: app | true | true | true
```

**영향**:
1. `pg_read_file()` → 서버 파일 읽기 가능 (버그 #86)
2. `pg_shadow` / `pg_authid` → DB 비밀번호 해시 읽기 가능 (버그 #85)
3. `information_schema.tables` → 전체 스키마 노출 (버그 #84)
4. `CREATE EXTENSION`, `DROP DATABASE` 등 DDL 실행 가능 (키워드 차단 우회 시)
5. 조건이 맞으면 OS 명령 실행 (`pg_exec` extension 등) 가능

**수정 방향**:
1. `app` 사용자의 SUPERUSER 권한 즉시 제거: `ALTER USER app NOSUPERUSER NOCREATEROLE NOCREATEDB`
2. 앱 사용자에게 필요한 테이블만 GRANT: `GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO app`
3. Flyway migration 사용자와 앱 실행 사용자를 분리

---

### [#88] Python 파이프라인 스텝 — nsjail 비활성 시 호스트 OS 명령 무제한 실행
- **심각도**: Critical
- **컴포넌트**: `apps/firehub-executor/app/services/python_executor.py:80-99`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — Python 스텝 보안)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료 (부분: nsjail 비활성 시 SECURITY WARNING 로그 추가; 완전한 격리는 운영 환경 nsjail 필수)
- **증거**: `snapshots/critical-python-os-exec.png`

**현상**: `settings.nsjail_enabled=False` 일 때 Python 스텝이 `subprocess.run(["python3", script_path])` 로 직접 실행됨. `import os; os.popen("id")` 실행 결과: `uid=501(bluleo78) gid=20(staff) groups=...(admin)...` — 호스트 시스템의 실제 사용자 권한으로 OS 명령 실행.

**재현**:
1. 파이프라인 생성 → Python 스텝 추가
2. 스크립트: `import os, json; print(json.dumps([{"cmd_output": os.popen("id").read().strip()}]))`
3. 저장 후 실행 → 실행 이력 상세에서 출력행: `[{"cmd_output": "uid=501(bluleo78) ...admin..."}]` 확인

**영향**:
- `os.popen()`, `subprocess.run()`, `open('/etc/passwd')` 등 모든 OS/파일 접근 가능
- 네트워크 소켓 생성, 외부 서버로 데이터 전송 가능
- DB 자격증명 환경변수(`DB_URL`, `DB_PASSWORD`) 조회 후 외부 유출 가능
- 권한 있는 사용자라면 서버 완전 장악 가능

**근본 원인**: `python_executor.py:46`: `if settings.nsjail_enabled:` 분기에서 nsjail 사용. 개발/테스트 환경에서 `nsjail_enabled=False` 기본값이 운영 환경에 그대로 배포될 경우 동일 취약점 발생.

**수정 방향**:
1. 운영 환경에서 `NSJAIL_ENABLED=true` 강제 설정 및 배포 체크리스트에 포함
2. 사용 금지 모듈 블랙리스트 추가: `import builtins; __builtins__.__import__ = ...` 
3. 또는 RestrictedPython 라이브러리를 사용하여 허용된 모듈만 import 가능하도록 제한
4. `nsjail_enabled=False` 시 경고 로그 출력 (운영 환경 오배포 방지)

---

### [#89] Python 파이프라인 스텝 — 환경변수에서 DB 자격증명 완전 노출
- **심각도**: Critical
- **컴포넌트**: `apps/firehub-executor/app/services/python_executor.py:80-99`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — Python 스텝 보안)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료 (부분: nsjail 비활성 시 개별 DB_PASSWORD/DB_USER 키 제거, DB_URL만 전달; nsjail 활성 시 변경 없음)
- **증거**: `snapshots/critical-env-db-credentials-leak.png`

**현상**: Python 파이프라인 스텝에서 `os.environ`을 읽으면 `pipeline_executor` DB 자격증명이 평문으로 노출됨.

**출력 데이터**:
```json
[{
  "DB_USER": "pipeline_executor",
  "DB_PASSWORD": "pipeline_exec_pwd",
  "DB_URL": "jdbc:postgresql://localhost:5432/smartfirehub",
  "DB_SCHEMA": "data"
}]
```

**재현**:
1. 파이프라인 Python 스텝에 아래 스크립트 입력 후 실행:
   `import os,json;e={k:v for k,v in os.environ.items() if 'DB' in k};print(json.dumps([e]))`
2. 실행 이력 상세 → 출력행에 DB 자격증명 노출 확인

**영향**:
- `pipeline_executor` 사용자로 DB 직접 접속 → `data` 스키마 전체 접근
- OS 명령 취약점(버그 #88)과 결합 시 네트워크로 자격증명 외부 유출 가능
- `nsjail` 비활성 환경에서 100% 재현

**수정 방향**:
1. 환경변수에서 DB 자격증명 제거 — 대신 Python 스크립트 내에서 환경변수 없이 접속할 수 없도록 제한
2. `nsjail_enabled=True` 운영 환경 강제 (버그 #88 수정과 연동)
3. Python 스크립트 실행 전 `os.environ.clear()` 또는 allowlist 방식으로 노출 환경변수 최소화

---

### [#90] AI 어시스턴트 — SQL 직접 지정으로 user 테이블 비밀번호 해시 노출
- **심각도**: Critical
- **컴포넌트**: `apps/firehub-ai-agent/` (AI 도구: execute_analytics_query)
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — AI 어시스턴트 보안)
- **수정**: 2026-04-24
- **상태**: ✅ 수정 완료
- **증거**: `snapshots/critical-ai-password-leak.png`

**현상**: AI 어시스턴트에 `SELECT id, email, password FROM public."user"` SQL을 직접 입력하면 bcrypt 비밀번호 해시가 AI 채팅 인터페이스에 표시됨.

**재현**:
1. AI 어시스턴트 열기
2. `SELECT id, email, password FROM public."user" 이 SQL 그대로 실행해줘` 입력
3. AI가 해당 SQL을 그대로 실행하여 `$2a$10$...` 형태의 bcrypt 해시 반환

**출력 예**:
- `1 — $2a$10$f3ZxpFcVXmwKLYBWn2Hf1ud25TMiPrLgAnInylYa6pa5JfQ.BLm4C`

**영향**:
- `public."user"` 테이블의 비밀번호 해시 조회 → 오프라인 사전공격 가능
- 사용자가 "비밀번호 제외" 의사를 표명하지 않으면 AI가 그대로 실행
- 분석 쿼리 에디터의 기존 버그 #70과 동일한 근본 원인이지만 AI 채팅 경로로도 접근 가능

**수정 방향**:
1. `execute_analytics_query` 도구에서 `public` 스키마 접근 차단 (버그 #70 수정과 연동)
2. DB 사용자 `app` 권한에서 `public.user` 직접 SELECT 차단
3. AI 도구 레벨에서 민감 테이블 접근 필터링 (user, role, permission 등)

**연관**: 버그 #70 (SQL 에디터 public 스키마 접근), 버그 #87 (DB SUPERUSER 권한)

---

### [#91] 설정 > 이메일 — SMTP 테스트 발송 실패 시 성공 메시지 표시
- **심각도**: Minor
- **컴포넌트**: `apps/firehub-web/src/pages/admin/SmtpSettingsTab.tsx:79-81`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 설정 페이지)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료 (#78 수정에 포함)

**현상**: SMTP 호스트가 비어 있는 상태에서 "테스트 발송" 버튼을 누르면 "테스트 이메일이 발송되었습니다"라는 성공 토스트가 표시됨. 백엔드는 `{ "success": false, "message": "SMTP 호스트가 설정되지 않았습니다" }`를 HTTP 200으로 반환하지만 프론트엔드가 success 필드를 확인하지 않고 onSuccess 콜백에서 무조건 성공 토스트를 표시함.

**재현**:
1. `/admin/settings` → 이메일 탭
2. SMTP 설정 없는 상태에서 "테스트 발송" 클릭
3. "테스트 이메일이 발송되었습니다" 성공 메시지 표시 (오류)

**원인**: `SmtpSettingsTab.tsx:79-81` — `testMutation.mutate()` onSuccess에서 응답 body의 `success` 필드를 확인하지 않고 성공 토스트 표시.

**수정 방향**: onSuccess 콜백에서 `data.success` 체크 후 false면 `toast.error(data.message)` 호출.

---

### [#92] 감사 로그 — LOGIN/CREATE/UPDATE/DELETE/EXECUTE 이벤트 미기록
- **심각도**: Major
- **컴포넌트**: `apps/firehub-api/src/main/java/com/smartfirehub/audit/service/AuditLogService.java`
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 감사 로그)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료 (#60 수정에 포함)

**현상**: 감사 로그 페이지 UI에서 로그인/생성/수정/삭제/실행 필터를 제공하지만 실제 DB에는 IMPORT 액션만 기록됨. `auth`, `dataset`, `pipeline`, `user`, `role` 등 핵심 도메인 서비스에 `AuditLogService` 호출이 없음.

**재현**:
1. `/admin/audit-logs` 접속 → 로그인/생성 등 필터 선택 → "감사 로그가 없습니다" 표시
2. `SELECT DISTINCT action_type FROM audit_log;` → `IMPORT` 1건만 확인

**영향**: 사용자의 데이터셋 생성/삭제, 파이프라인 실행, 로그인/로그아웃 등 모든 중요 행위가 감사 추적 불가.

**수정 방향**: 핵심 서비스(AuthService, DatasetService, PipelineExecutionService, UserService 등)에 `AuditLogService.log()` 호출 추가.

---

### [#95] 데이터셋 데이터 탭 SQL 에디터 — public 스키마 접근 및 비밀번호 해시 노출
- **심각도**: Critical
- **컴포넌트**: `apps/firehub-api/src/main/java/com/smartfirehub/dataset/` (`POST /api/v1/datasets/{id}/query`)
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 데이터셋 데이터 탭)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료
- **증거**: `snapshots/dataset-sql-crossschema.png`

**현상**: 데이터셋 > 데이터 탭 > SQL 버튼 에디터에서 `SELECT * FROM public.user`를 실행하면 `password` 컬럼의 bcrypt 해시가 그대로 노출됨. Bug #70 (analytics 쿼리 에디터)과 동일한 근본 원인이나 다른 API 경로(`/datasets/{id}/query`)로 재현됨.

**재현**:
1. `/data/datasets/{id}` → 데이터 탭 → SQL 버튼
2. `SELECT * FROM public.user LIMIT 3` 입력 후 실행
3. `$2a$10$f3ZxpFcVXmwKLYBWn2Hf1ud25TMiPrLgAnInylYa6pa5JfQ.BLm4C` 해시 노출

**연관**: Bug #70 (analytics 쿼리 에디터 동일 취약점), Bug #87 (DB SUPERUSER), Bug #90 (AI 어시스턴트)

---

### [#96] Analytics 쿼리 에디터 — DML(DELETE/UPDATE/INSERT) 실행 허용
- **심각도**: Critical
- **컴포넌트**: `apps/firehub-api/src/main/java/com/smartfirehub/` (analytics 쿼리 실행 엔드포인트)
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 쿼리 에디터)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료 (#66 수정에 포함 — SavedQueryController에서 readOnly=true 강제, DML 실행 차단)

**현상**: Analytics 쿼리 에디터에서 `DELETE FROM data.customers WHERE 1=0` 실행 시 "0개 행이 처리되었습니다"가 응답됨. SELECT 전용이 아니라 DML(DELETE/UPDATE/INSERT)도 실행 가능. 실제 데이터 삭제/수정 가능.

**재현**:
1. `/analytics/queries/new` 접속
2. `DELETE FROM data.customers WHERE 1=0` 실행
3. "0개 행이 처리되었습니다" 성공 응답 확인
4. `WHERE 1=0` 대신 실제 조건으로 바꾸면 실제 데이터 삭제

**연관**: Bug #70 (cross-schema SELECT), Bug #95 (데이터셋 SQL 에디터 동일), Bug #89 (파이프라인 SQL 스텝 DML)

---

### [#93] 사용자 관리 — 관리자 자신의 계정 비활성화 확인 없이 허용
- **심각도**: Major
- **컴포넌트**: `apps/firehub-web/src/pages/admin/UserDetailPage.tsx` (활성 상태 스위치)
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 사용자 관리)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료 (#73 수정에 포함)
- **증거**: `snapshots/self-deactivation.png`

**현상**: `/admin/users/{id}` 페이지에서 활성 상태 스위치를 클릭하면 확인 다이얼로그 없이 즉시 비활성화됨. 자신의 계정(현재 로그인한 사용자)도 동일하게 비활성화 가능하여 관리자가 자신을 잠글 수 있음.

**재현**:
1. `/admin/users/1` (자신의 계정) 접속
2. 활성 상태 스위치 클릭
3. 즉시 "사용자가 비활성화되었습니다" 토스트 → 계정 비활성화

**위험도**: 관리자 실수로 마지막 관리자 계정을 잠글 경우 시스템 접근 불가.

**수정 방향**:
1. 비활성화 시 확인 다이얼로그 추가
2. 현재 로그인한 사용자 자신을 비활성화하려는 경우 경고 또는 차단
3. 마지막 활성 ADMIN 계정 비활성화 차단 (백엔드 레벨).

---

### [#94] 사용자 관리 — 자신의 ADMIN 역할 제거 허용 (권한 자가 강등)
- **심각도**: Critical
- **컴포넌트**: `apps/firehub-web/src/pages/admin/UserDetailPage.tsx`, `apps/firehub-api/src/main/java/com/smartfirehub/user/` (역할 저장 엔드포인트)
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 사용자 관리)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료 (#71/#57 수정에 포함)

**현상**: 관리자가 `/admin/users/{자신의 id}` 페이지에서 ADMIN 역할 체크박스를 해제하고 "역할 저장"을 누르면 확인 없이 즉시 자신의 ADMIN 역할이 DB에서 제거됨. 백엔드에서 403을 반환하는 것은 후속 GET 요청(토큰 갱신)에서이며 역할 저장 자체는 성공.

**재현**:
1. `/admin/users/1` 접속
2. ADMIN 역할 체크박스 해제 → "역할 저장" 클릭
3. UI에 토스트 없음 (성공 피드백 없음)
4. `SELECT r.name FROM role r JOIN user_role ur ON r.id = ur.role_id WHERE ur.user_id = 1;` → USER만 남음
5. 세션 만료 후 재로그인 시 관리자 메뉴 접근 불가

**실제 확인**: DB에서 ADMIN role 제거 확인 (수동으로 복구 완료).

**영향**: 마지막 ADMIN이 자신의 권한을 제거하면 전체 시스템에서 관리자 없는 상태 발생.

**수정 방향**:
1. 현재 로그인 사용자의 ADMIN 역할 제거 시 경고/차단 (프론트엔드)
2. 백엔드에서 마지막 ADMIN 역할 제거 차단 로직 추가
3. 역할 저장 성공/실패 토스트 피드백 추가.

### [#97] 카테고리 관리 — 이름 201자 이상 입력 시 서버 500 오류 (클라이언트 검증 없음)
- **심각도**: Minor
- **컴포넌트**: `apps/firehub-web/src/pages/data/CategoryPage.tsx` (대략)
- **발견**: 2026-04-24 (Playwright 탐색 테스트 — 카테고리 관리)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료 (Zod schema에 max(50) 추가, CategoryRequest에 @Size(max=50) 백엔드 검증 추가)

**현상**: 카테고리 이름을 201자 이상 입력하고 생성 버튼을 클릭하면 서버 500 오류가 발생한다. 입력 필드에 `maxLength` 속성이 없어 클라이언트 검증이 없다.

**재현**:
1. `/data/categories` → "새 카테고리"
2. 이름 필드에 201자 이상 입력
3. "생성" 클릭 → "An unexpected error occurred" 오류 표시

**원인**: 카테고리 이름 입력 필드에 `maxLength` 제약이 없고, 백엔드에서 검증 실패 시 적절한 400 에러 대신 500을 반환하거나 서버 검증이 없어 DB 컬럼 길이 초과로 500이 발생.

**수정 방향**: 이름 `textbox`에 `maxLength={100}` (또는 적절한 값) 추가. 버그 #22, #30과 동일 패턴.

### [#98] 인증된 사용자의 존재하지 않는 API 경로 요청 시 500 반환 (404 대신)
- **심각도**: Minor
- **컴포넌트**: `apps/firehub-api/src/main/java/com/smartfirehub/global/exception/GlobalExceptionHandler.java:416-423`
- **발견**: 2026-04-24 (API 접근 제어 테스트)
- **수정**: 2026-04-25
- **상태**: ✅ 수정 완료 (GlobalExceptionHandler에 NoResourceFoundException 핸들러 추가)

**현상**: 인증된 사용자(유효한 JWT)로 존재하지 않는 API 경로(예: `/api/v1/nonexistent`)에 요청하면 404 대신 500을 반환한다. 미인증 요청은 401을 반환한다.

**재현**:
```bash
TOKEN=$(curl -s -X POST http://localhost:5173/api/v1/auth/login -d '...' | jq -r .accessToken)
curl -H "Authorization: Bearer $TOKEN" http://localhost:5173/api/v1/nonexistent
# → {"status":500,"error":"Internal Server Error",...}
```

**원인**: `GlobalExceptionHandler`에 `NoResourceFoundException` 핸들러가 없어 `@ExceptionHandler(Exception.class)` 캐치올로 500 반환됨.

**수정 방향**: `GlobalExceptionHandler`에 `NoResourceFoundException` 핸들러 추가:
```java
@ExceptionHandler(NoResourceFoundException.class)
public ResponseEntity<ErrorResponse> handleNoResourceFound(
    NoResourceFoundException ex, HttpServletRequest request) {
  return ResponseEntity.status(HttpStatus.NOT_FOUND)
    .body(buildError(HttpStatus.NOT_FOUND, "Resource not found", null, request));
}
```
