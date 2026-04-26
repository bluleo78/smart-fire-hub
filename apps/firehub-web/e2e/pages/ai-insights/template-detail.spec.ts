import { createTemplate, createTemplateSection } from '../../factories/ai-insight.factory';
import { setupTemplateDetailMocks } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 리포트 템플릿 상세 페이지 E2E 테스트
 * - 템플릿 로드, 빌트인/커스텀 구분, 새 템플릿 생성 폼을 검증한다.
 * - TemplateJsonEditor(CodeMirror) 비동기 렌더링으로 인해 데이터 로딩 대기가 필요하다.
 */
test.describe('리포트 템플릿 상세 페이지', () => {
  test('커스텀 템플릿 상세가 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
    // 커스텀 템플릿(builtin: false)으로 모킹
    // createTemplate 기본값 sections: [{key:'summary', label:'요약'}, {key:'details', label:'상세 내용'}]
    const template = createTemplate({ id: 1, name: '기본 리포트 템플릿', builtin: false });
    await mockApi(page, 'GET', '/api/v1/proactive/templates/1', template);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/templates/1');

    // 템플릿 이름이 헤더에 표시되는지 확인 — 네트워크 요청 완료 후 데이터가 렌더링된다
    await expect(page.getByRole('heading', { name: '기본 리포트 템플릿' })).toBeVisible({ timeout: 10000 });

    // "커스텀" 뱃지 확인 (builtin: false)
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '커스텀' })).toBeVisible();

    // 팩토리 sections의 레이블 '요약', '상세 내용'이 JSON 구조 뷰 또는 섹션 미리보기에 표시되는지 확인
    // TemplateJsonEditor(CodeMirror)가 JSON을 렌더링하면 section label이 텍스트로 포함된다
    // 여러 요소에 매칭될 수 있으므로 .first()를 사용해 strict mode 위반을 방지한다
    await expect(page.getByText('요약').first()).toBeVisible({ timeout: 10000 });
    await expect(page.getByText('상세 내용').first()).toBeVisible({ timeout: 10000 });
  });

  test('빌트인 템플릿에는 "기본" 뱃지가 표시되고 편집/삭제 버튼이 없다', async ({ authenticatedPage: page }) => {
    // setupTemplateDetailMocks는 createTemplate({ id: 1 }) 기본값(builtin: true)을 사용한다
    await setupTemplateDetailMocks(page, 1);

    await page.goto('/ai-insights/templates/1');

    // 템플릿 이름이 렌더링될 때까지 대기 (팩토리 기본값: "기본 리포트 템플릿")
    await expect(page.getByRole('heading', { name: '기본 리포트 템플릿' })).toBeVisible({ timeout: 10000 });

    // "기본" 뱃지 확인 (builtin: true)
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '기본' })).toBeVisible();

    // 빌트인 템플릿에는 편집/삭제 버튼이 없어야 한다 (isBuiltin 조건으로 숨겨짐)
    await expect(page.getByRole('button', { name: '편집' })).not.toBeVisible();
    await expect(page.getByRole('button', { name: '삭제' })).not.toBeVisible();
  });

  test('커스텀 템플릿에는 편집 버튼이 표시된다', async ({ authenticatedPage: page }) => {
    // builtin: false 커스텀 템플릿으로 모킹
    const template = createTemplate({ id: 2, name: '커스텀 리포트', builtin: false });
    await mockApi(page, 'GET', '/api/v1/proactive/templates/2', template);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/templates/2');

    // 템플릿 이름이 렌더링될 때까지 대기
    await expect(page.getByRole('heading', { name: '커스텀 리포트' })).toBeVisible({ timeout: 10000 });

    // 커스텀 템플릿에는 편집 버튼이 표시된다
    await expect(page.getByRole('button', { name: '편집' })).toBeVisible();

    // 삭제 버튼도 표시된다
    await expect(page.getByRole('button', { name: '삭제' })).toBeVisible();
  });

  test('새 템플릿 페이지에서 생성 폼이 표시된다', async ({ authenticatedPage: page }) => {
    // 새 템플릿 페이지 API 모킹 (템플릿 목록만 필요)
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/templates/new');

    // "새 템플릿" 헤더 확인
    await expect(page.getByRole('heading', { name: '새 템플릿' })).toBeVisible();

    // 이름 입력 필드 확인
    await expect(page.locator('#tpl-name')).toBeVisible();

    // 설명 입력 필드 확인 (id="tpl-desc")
    await expect(page.locator('#tpl-desc')).toBeVisible();

    // 작성 스타일 입력 필드 확인 (id="tpl-style")
    await expect(page.locator('#tpl-style')).toBeVisible();

    // "생성" 버튼 확인
    await expect(page.getByRole('button', { name: '생성' })).toBeVisible();
  });

  test('읽기 모드에서 섹션 구조 미리보기에 섹션 개수가 표시된다', async ({ authenticatedPage: page }) => {
    // setupTemplateDetailMocks: createTemplate 기본값 — sections 2개 (summary, details)
    await setupTemplateDetailMocks(page, 1);

    await page.goto('/ai-insights/templates/1');

    // 템플릿 이름이 렌더링될 때까지 대기
    await expect(page.getByRole('heading', { name: '기본 리포트 템플릿' })).toBeVisible({ timeout: 10000 });

    // 읽기 모드의 SectionPreview 컴포넌트에서 "개 섹션" 텍스트 확인
    // createTemplate 기본값 sections 2개 → "2개 섹션"
    await expect(page.getByText(/개 섹션/)).toBeVisible({ timeout: 10000 });
  });

  test('미리보기 타입별 플레이스홀더 렌더링', async ({ authenticatedPage: page }) => {
    // 다양한 타입의 섹션을 포함한 템플릿 생성
    // SectionPreview 컴포넌트가 타입별로 다른 플레이스홀더를 렌더링하는지 검증한다
    const template = createTemplate({
      id: 1,
      name: '타입별 섹션 템플릿',
      builtin: true,
      sections: [
        createTemplateSection({ key: 'summary', type: 'text', label: '텍스트 요약', instruction: '현황을 요약하세요.' }),
        createTemplateSection({ key: 'kpi', type: 'cards', label: 'KPI 지표' }),
        createTemplateSection({ key: 'data_table', type: 'table', label: '데이터 테이블' }),
      ],
    });
    await mockApi(page, 'GET', '/api/v1/proactive/templates/1', template);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/templates/1');

    // 템플릿 이름이 렌더링될 때까지 대기
    await expect(page.getByRole('heading', { name: '타입별 섹션 템플릿' })).toBeVisible({ timeout: 10000 });

    // 섹션 구조 미리보기에 섹션 레이블이 표시된다
    await expect(page.getByText('텍스트 요약').first()).toBeVisible({ timeout: 10000 });
    await expect(page.getByText('KPI 지표').first()).toBeVisible({ timeout: 10000 });
    await expect(page.getByText('데이터 테이블').first()).toBeVisible({ timeout: 10000 });

    // cards 타입 — KPI 카드 플레이스홀더('지표 1', '지표 2', '지표 3')가 렌더링된다
    await expect(page.getByText('지표 1')).toBeVisible();
    await expect(page.getByText('지표 2')).toBeVisible();
    await expect(page.getByText('지표 3')).toBeVisible();

    // table 타입 — 컬럼 헤더 플레이스홀더('열 1', '열 2', '열 3')가 렌더링된다
    await expect(page.getByText('열 1')).toBeVisible();
    await expect(page.getByText('열 2')).toBeVisible();
    await expect(page.getByText('열 3')).toBeVisible();

    // instruction이 있는 섹션 — "AI 지시:" 텍스트가 표시된다
    // 텍스트 요약 섹션의 instruction: '현황을 요약하세요.'
    // 여러 섹션에 AI 지시 텍스트가 있을 수 있으므로 .first()로 strict mode 위반을 방지한다
    await expect(page.getByText(/AI 지시:/).first()).toBeVisible();
    await expect(page.getByText('AI 지시: 현황을 요약하세요.', { exact: true })).toBeVisible();

    // 섹션 수 요약 텍스트 확인 — 3개 섹션
    await expect(page.getByText('3개 섹션')).toBeVisible({ timeout: 10000 });
  });

  test('편집 모드에서 빌더 섹션 구조가 표시된다', async ({ authenticatedPage: page }) => {
    // builtin: false 커스텀 템플릿으로 모킹 — 편집 버튼이 표시되어야 한다
    // 섹션 2개(summary: text, details: list)를 포함한다
    const template = createTemplate({
      id: 4,
      name: '편집용 템플릿',
      builtin: false,
      sections: [
        createTemplateSection({ key: 'summary', type: 'text', label: '요약 섹션' }),
        createTemplateSection({ key: 'details', type: 'list', label: '상세 목록' }),
      ],
    });
    await mockApi(page, 'GET', '/api/v1/proactive/templates/4', template);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/templates/4');

    // 템플릿 이름이 렌더링될 때까지 대기
    await expect(page.getByRole('heading', { name: '편집용 템플릿' })).toBeVisible({ timeout: 10000 });

    // 읽기 모드에서 섹션 수 확인 — 2개 섹션
    await expect(page.getByText('2개 섹션')).toBeVisible({ timeout: 10000 });

    // 편집 버튼 클릭으로 편집 모드 진입
    await page.getByRole('button', { name: '편집' }).click();

    // 편집 모드에서 빌더 탭이 표시되는지 확인 (builder/json 탭 전환)
    await expect(page.getByRole('tab', { name: '빌더' })).toBeVisible();
    await expect(page.getByRole('tab', { name: 'JSON' })).toBeVisible();

    // 빌더 탭이 기본으로 활성화되어 있는지 확인
    await expect(page.getByRole('tab', { name: '빌더' })).toHaveAttribute('data-state', 'active');

    // 편집 모드에서 섹션 트리 빌더 내 섹션 레이블이 표시된다
    // SectionTreeBuilder가 섹션 목록을 트리로 렌더링한다
    await expect(page.getByText('요약 섹션').first()).toBeVisible({ timeout: 10000 });
    await expect(page.getByText('상세 목록').first()).toBeVisible({ timeout: 10000 });

    // 취소 버튼으로 편집 모드 종료
    await page.getByRole('button', { name: '취소' }).click();

    // 읽기 모드로 돌아간 후 미리보기에서 섹션 수 재확인
    await expect(page.getByText('2개 섹션')).toBeVisible({ timeout: 10000 });
  });

  test('편집 모드 → JSON 탭으로 전환 시 CodeMirror 에디터가 렌더링된다', async ({ authenticatedPage: page }) => {
    // builtin: false 커스텀 템플릿으로 모킹 — 편집 버튼이 표시되어야 한다
    const template = createTemplate({ id: 5, name: 'JSON 탭 테스트 템플릿', builtin: false });
    await mockApi(page, 'GET', '/api/v1/proactive/templates/5', template);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/templates/5');
    await expect(page.getByRole('heading', { name: 'JSON 탭 테스트 템플릿' })).toBeVisible({ timeout: 10000 });

    // 편집 모드 진입
    await page.getByRole('button', { name: '편집' }).click();

    // JSON 탭 클릭
    await expect(page.getByRole('tab', { name: 'JSON' })).toBeVisible();
    await page.getByRole('tab', { name: 'JSON' }).click();

    // TemplateJsonEditor(CodeMirror)가 렌더링되어야 한다 — cm-editor 클래스 확인
    await expect(page.locator('.cm-editor').first()).toBeVisible({ timeout: 5000 });
  });

  test('편집 모드 JSON 탭 → 빌더 탭 전환 시 섹션이 동기화된다', async ({ authenticatedPage: page }) => {
    // 섹션이 있는 커스텀 템플릿 모킹
    const template = createTemplate({
      id: 6,
      name: '빌더-JSON 동기화 테스트',
      builtin: false,
      sections: [
        createTemplateSection({ key: 'summary', label: '요약 섹션' }),
      ],
    });
    await mockApi(page, 'GET', '/api/v1/proactive/templates/6', template);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/templates/6');
    await expect(page.getByRole('heading', { name: '빌더-JSON 동기화 테스트' })).toBeVisible({ timeout: 10000 });

    // 편집 모드 진입
    await page.getByRole('button', { name: '편집' }).click();

    // JSON 탭으로 전환
    await page.getByRole('tab', { name: 'JSON' }).click();
    await expect(page.locator('.cm-editor').first()).toBeVisible({ timeout: 5000 });

    // 빌더 탭으로 돌아가기 — handleTabChange에서 JSON→builder 동기화 로직 실행
    await page.getByRole('tab', { name: '빌더' }).click();

    // 빌더 탭이 활성화되어야 한다
    await expect(page.getByRole('tab', { name: '빌더' })).toHaveAttribute('data-state', 'active');
    // 섹션 이름이 여전히 표시되어야 한다
    await expect(page.getByText('요약 섹션').first()).toBeVisible({ timeout: 5000 });
  });

  test('JSON 탭에서 섹션 추가 버튼이 표시된다', async ({ authenticatedPage: page }) => {
    // 커스텀 템플릿으로 모킹
    const template = createTemplate({ id: 7, name: '섹션 추가 테스트', builtin: false });
    await mockApi(page, 'GET', '/api/v1/proactive/templates/7', template);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/templates/7');
    await expect(page.getByRole('heading', { name: '섹션 추가 테스트' })).toBeVisible({ timeout: 10000 });

    // 편집 모드 진입 → JSON 탭 이동
    await page.getByRole('button', { name: '편집' }).click();
    await page.getByRole('tab', { name: 'JSON' }).click();
    await expect(page.locator('.cm-editor').first()).toBeVisible({ timeout: 5000 });

    // TemplateJsonEditor 에디터 헤더에 "섹션 추가:" 텍스트와 섹션 타입 버튼이 표시된다
    await expect(page.getByText('섹션 추가:')).toBeVisible();
    // SECTION_TYPES 버튼 레이블은 영문 — Text, Cards, List, Table 등
    // 최소 하나 이상의 섹션 추가 버튼이 있어야 한다
    const sectionAddBtns = page.locator('button').filter({ hasText: /Text|Cards|List|Table/ });
    await expect(sectionAddBtns.first()).toBeVisible({ timeout: 3000 });
  });

  test('복제 버튼 클릭 시 새 템플릿이 생성된다', async ({ authenticatedPage: page }) => {
    // builtin: true 빌트인 템플릿 — 복제 버튼은 빌트인도 표시된다
    await setupTemplateDetailMocks(page, 1);

    // POST /api/v1/proactive/templates 복제 호출 모킹 (사본 반환)
    const cloneCapture = await mockApi(
      page,
      'POST',
      '/api/v1/proactive/templates',
      createTemplate({ id: 10, name: '기본 리포트 템플릿 (사본)', builtin: false }),
      { capture: true },
    );
    // 복제 후 이동할 새 템플릿 상세 페이지 모킹
    await mockApi(page, 'GET', '/api/v1/proactive/templates/10', createTemplate({ id: 10, name: '기본 리포트 템플릿 (사본)', builtin: false }));

    await page.goto('/ai-insights/templates/1');
    await expect(page.getByRole('heading', { name: '기본 리포트 템플릿' })).toBeVisible({ timeout: 10000 });

    // 복제 버튼 클릭
    await page.getByRole('button', { name: '복제' }).click();

    // POST API 호출 확인 — 이름에 "(사본)" 포함
    const req = await cloneCapture.waitForRequest();
    expect(req.payload).toMatchObject({ name: '기본 리포트 템플릿 (사본)' });
  });

  test('존재하지 않는 템플릿 ID 접근 시 toast.error 후 목록으로 이동한다', async ({ authenticatedPage: page }) => {
    // 존재하지 않는 템플릿 — 단건 API 404 반환, 목록도 비어 있음 (#38)
    await mockApi(page, 'GET', '/api/v1/proactive/templates/99999', { message: 'Not Found' }, { status: 404 });
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    // 목록 페이지 렌더링을 위한 모킹
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/templates/99999');

    // toast.error 메시지 확인 — Sonner toast는 [data-sonner-toast] 속성으로 렌더링된다
    await expect(page.locator('[data-sonner-toast]').filter({ hasText: /템플릿/ })).toBeVisible({ timeout: 10000 });

    // 목록 페이지(/ai-insights/templates)로 이동 확인
    await expect(page).toHaveURL(/\/ai-insights\/templates$/, { timeout: 5000 });
  });

  /**
   * 회귀 테스트 — 이슈 #58
   * 템플릿 작성/수정 중 뒤로가기 클릭 시 저장 확인 다이얼로그 없이 변경사항이 소실되는 문제.
   * isDirty 상태 + 뒤로가기/취소 버튼 onClick 인터셉트 + AlertDialog로 이탈 전 사용자 확인.
   */
  test.describe('이슈 #58 — 미저장 변경 이탈 가드', () => {
    test('변경 사항이 없으면 뒤로가기 클릭 시 즉시 목록으로 이동한다', async ({
      authenticatedPage: page,
    }) => {
      // 새 템플릿 페이지 — 입력 없는 상태
      await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
      await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

      await page.goto('/ai-insights/templates/new');
      await expect(page.getByRole('heading', { name: '새 템플릿' })).toBeVisible();

      // 미저장 표시는 없어야 한다
      await expect(page.getByText('미저장 변경사항')).not.toBeVisible();

      // 뒤로가기 클릭 → 다이얼로그 없이 즉시 이동
      await page.getByRole('button', { name: '목록으로' }).click();
      await expect(page).toHaveURL(/\/ai-insights\/templates$/);
    });

    test('이름 입력 후 뒤로가기 클릭 시 이탈 확인 다이얼로그가 표시된다', async ({
      authenticatedPage: page,
    }) => {
      await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
      await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

      await page.goto('/ai-insights/templates/new');
      await expect(page.getByRole('heading', { name: '새 템플릿' })).toBeVisible();

      // 이름 입력 → isDirty=true 트리거
      await page.locator('#tpl-name').fill('테스트 템플릿');

      // 미저장 변경사항 인디케이터 표시
      await expect(page.getByText('미저장 변경사항')).toBeVisible();

      // 뒤로가기 클릭 → AlertDialog 표시
      await page.getByRole('button', { name: '목록으로' }).click();

      await expect(page.getByRole('alertdialog')).toBeVisible();
      await expect(
        page.getByRole('heading', { name: '저장하지 않은 변경사항' }),
      ).toBeVisible();
      await expect(
        page.getByText('저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?'),
      ).toBeVisible();

      // URL은 아직 변경되지 않아야 함
      await expect(page).toHaveURL(/\/ai-insights\/templates\/new$/);
    });

    test('이탈 확인 다이얼로그에서 취소를 누르면 페이지에 머무르고 입력값이 보존된다', async ({
      authenticatedPage: page,
    }) => {
      await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
      await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

      await page.goto('/ai-insights/templates/new');
      await page.locator('#tpl-name').fill('테스트 템플릿');
      await page.getByRole('button', { name: '목록으로' }).click();

      // AlertDialog 내 '취소' 버튼 클릭
      await page.getByRole('alertdialog').getByRole('button', { name: '취소' }).click();

      // 다이얼로그 닫힘 + 페이지 유지 + 입력값 보존 + dirty 표시 유지
      await expect(page.getByRole('alertdialog')).not.toBeVisible();
      await expect(page).toHaveURL(/\/ai-insights\/templates\/new$/);
      await expect(page.locator('#tpl-name')).toHaveValue('테스트 템플릿');
      await expect(page.getByText('미저장 변경사항')).toBeVisible();
    });

    test('이탈 확인 다이얼로그에서 이탈을 누르면 목록으로 이동한다', async ({
      authenticatedPage: page,
    }) => {
      await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
      await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

      await page.goto('/ai-insights/templates/new');
      await page.locator('#tpl-name').fill('테스트 템플릿');
      await page.getByRole('button', { name: '목록으로' }).click();

      // '이탈' 버튼 클릭 → 변경사항 버리고 목록으로 이동
      await page.getByRole('alertdialog').getByRole('button', { name: '이탈' }).click();

      await expect(page).toHaveURL(/\/ai-insights\/templates$/);
    });

    test('템플릿 저장 성공 후 미저장 표시가 사라진다', async ({ authenticatedPage: page }) => {
      // 새 템플릿 생성 모킹 — 저장 후 상세 페이지로 이동한다
      const created = createTemplate({ id: 100, name: '신규 템플릿', builtin: false });
      await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
      await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
      await mockApi(page, 'POST', '/api/v1/proactive/templates', created);
      await mockApi(page, 'GET', '/api/v1/proactive/templates/100', created);

      await page.goto('/ai-insights/templates/new');
      await page.locator('#tpl-name').fill('신규 템플릿');

      // 저장 전 dirty 표시 확인
      await expect(page.getByText('미저장 변경사항')).toBeVisible();

      // 생성 버튼 클릭
      await page.getByRole('button', { name: '생성' }).click();

      // 저장 후 신규 템플릿 상세로 이동
      await expect(page).toHaveURL(/\/ai-insights\/templates\/100$/);

      // 저장 성공 → dirty 해제 → 미저장 표시 사라짐
      await expect(page.getByText('미저장 변경사항')).not.toBeVisible();
    });

    test('편집 모드에서 변경 후 취소 버튼 클릭 시 이탈 확인 다이얼로그가 표시된다', async ({
      authenticatedPage: page,
    }) => {
      // 커스텀 템플릿 모킹 — 편집 모드 진입 가능
      const template = createTemplate({ id: 50, name: '편집 대상 템플릿', builtin: false });
      await mockApi(page, 'GET', '/api/v1/proactive/templates/50', template);
      await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
      await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

      await page.goto('/ai-insights/templates/50');
      await expect(page.getByRole('heading', { name: '편집 대상 템플릿' })).toBeVisible({ timeout: 10000 });

      // 편집 모드 진입
      await page.getByRole('button', { name: '편집' }).click();

      // 이름 변경 → dirty 트리거
      await page.locator('#tpl-name').fill('수정된 이름');
      await expect(page.getByText('미저장 변경사항')).toBeVisible();

      // 취소 버튼 클릭 → AlertDialog 표시 (페이지 이탈이 아닌 편집 취소도 가드)
      await page.getByRole('button', { name: '취소' }).click();

      await expect(page.getByRole('alertdialog')).toBeVisible();
      await expect(
        page.getByRole('heading', { name: '저장하지 않은 변경사항' }),
      ).toBeVisible();
    });
  });

  test('삭제 버튼 클릭 시 삭제 확인 다이얼로그가 열린다', async ({ authenticatedPage: page }) => {
    // builtin: false 커스텀 템플릿으로 모킹
    const template = createTemplate({ id: 3, name: '삭제 대상 템플릿', builtin: false });
    await mockApi(page, 'GET', '/api/v1/proactive/templates/3', template);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    // DELETE API 캡처 — 다이얼로그 확인 버튼 클릭 시 호출되는지 검증
    const deleteCapture = await mockApi(page, 'DELETE', '/api/v1/proactive/templates/3', {}, { capture: true });

    await page.goto('/ai-insights/templates/3');

    // 삭제 버튼이 나타날 때까지 대기 (템플릿 데이터 로드 후 표시)
    await expect(page.getByRole('button', { name: '삭제' })).toBeVisible({ timeout: 10000 });

    // 삭제 버튼 클릭
    await page.getByRole('button', { name: '삭제' }).click();

    // 삭제 확인 다이얼로그 확인
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByText('템플릿 삭제')).toBeVisible();

    // 다이얼로그 내 확인(삭제) 버튼 클릭 — 역할/텍스트 기반으로 탐색
    const confirmButton = page.getByRole('dialog').getByRole('button', { name: /삭제|확인/ }).last();
    await confirmButton.click();

    // DELETE /api/v1/proactive/templates/3 가 실제로 호출되었는지 확인
    const deletedReq = await deleteCapture.waitForRequest();
    expect(deletedReq).toBeDefined();
  });
});
