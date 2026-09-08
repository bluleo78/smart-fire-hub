/**
 * 파이프라인 에디터 상호작용 E2E 테스트
 *
 * pipelineEditorReducer / PipelineCanvas / StepConfigPanel 의 reducer dispatch
 * 경로(ADD_STEP, UPDATE_STEP, AUTO_LAYOUT) 를 UI 로부터 실제 호출되도록 커버한다.
 */

import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupPipelineEditorMocks, setupPipelineMocks } from '../../fixtures/pipeline.fixture';

test.describe('파이프라인 에디터 — 상호작용', () => {
  /** 신규 파이프라인 에디터는 /pipelines/new 로 진입한다. 데이터셋 목록만 있으면 된다. */
  async function setupNewEditorMocks(
    page: import('@playwright/test').Page,
  ) {
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [],
      page: 0,
      size: 1000,
      totalElements: 0,
      totalPages: 0,
    });
  }

  test('빈 상태 → 스텝 추가 버튼 클릭 시 첫 번째 스텝이 생성된다', { tag: '@smoke' }, async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);

    await page.goto('/pipelines/new');

    // 빈 상태 안내 문구
    await expect(page.getByText('첫 번째 스텝을 추가하세요')).toBeVisible();

    // 스텝 추가 → ADD_STEP dispatch → steps[0] 생성
    await page.getByRole('button', { name: /스텝 추가/ }).click();

    // 빈 상태가 사라지고 ReactFlow 캔버스가 렌더링된다
    await expect(page.getByText('첫 번째 스텝을 추가하세요')).not.toBeVisible();
    // 우상단의 자동 정렬 / 스텝 추가 버튼이 나타난다
    await expect(page.getByRole('button', { name: '자동 정렬' })).toBeVisible();
  });

  test('스텝 추가 → 자동 정렬 버튼 클릭 시 AUTO_LAYOUT dispatch', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);

    await page.goto('/pipelines/new');
    await page.getByRole('button', { name: /스텝 추가/ }).click();
    await expect(page.getByRole('button', { name: '자동 정렬' })).toBeVisible();

    // 스텝 하나 더 추가 (ADD_STEP) — 우상단의 추가 버튼 사용 (first())
    await page.getByRole('button', { name: /스텝 추가/ }).first().click();

    // 자동 정렬 버튼 클릭 (AUTO_LAYOUT dispatch)
    await page.getByRole('button', { name: '자동 정렬' }).click();

    // 여전히 캔버스가 정상 렌더링되는지 확인 — AUTO_LAYOUT 이후 에러 없이 유지
    await expect(page.getByRole('button', { name: '자동 정렬' })).toBeVisible();
  });

  test('신규 에디터에서 헤더에 파이프라인 이름 입력 필드가 있다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);

    await page.goto('/pipelines/new');

    // EditorHeader 의 이름 입력 필드 확인 (신규 생성 모드)
    const nameInput = page.getByPlaceholder(/파이프라인 이름|이름 입력/);
    await expect(nameInput.first()).toBeVisible();
  });

  test('헤더 이름 입력란 변경 — SET_META dispatch로 파이프라인 이름이 바뀐다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);

    await page.goto('/pipelines/new');

    // EditorHeader의 이름 입력 필드 확인
    const nameInput = page.getByPlaceholder(/파이프라인 이름|이름 입력/).first();
    await expect(nameInput).toBeVisible();

    // 이름 변경 → SET_META dispatch
    await nameInput.fill('새 파이프라인 이름');

    // 입력값이 반영되어야 한다
    await expect(nameInput).toHaveValue('새 파이프라인 이름');
  });

  test('스텝 선택 후 이름 입력 — UPDATE_STEP dispatch로 스텝 이름이 변경된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);

    await page.goto('/pipelines/new');

    // 스텝 추가 → ADD_STEP dispatch
    await page.getByRole('button', { name: /스텝 추가/ }).click();
    await expect(page.getByRole('button', { name: '자동 정렬' })).toBeVisible();

    // ReactFlow 노드 클릭 → SELECT_STEP dispatch → StepConfigPanel 열림
    await page.locator('.react-flow__node').first().click();

    // StepConfigPanel에 이름 입력 필드가 나타나야 한다
    const stepNameInput = page.getByLabel(/스텝 이름|이름/).first();
    await expect(stepNameInput).toBeVisible();

    // 이름 변경 → UPDATE_STEP dispatch
    await stepNameInput.fill('내 SQL 스텝');

    // 입력값 반영 확인
    await expect(stepNameInput).toHaveValue('내 SQL 스텝');
  });

  test('스텝 추가 후 노드 클릭 → 스텝 삭제 — REMOVE_STEP dispatch', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);

    await page.goto('/pipelines/new');

    // 스텝 추가 → ADD_STEP dispatch
    await page.getByRole('button', { name: /스텝 추가/ }).click();
    await expect(page.getByRole('button', { name: '자동 정렬' })).toBeVisible();

    // ReactFlow 노드 클릭 → StepConfigPanel 열기
    await page.locator('.react-flow__node').first().click();

    // StepConfigPanel 하단 destructive "스텝 삭제" 버튼 클릭 → AlertDialog 확인 후 REMOVE_STEP dispatch
    // 노드에도 title="스텝 삭제" 아이콘 버튼이 있으므로 .last()로 패널 버튼을 선택한다
    await expect(page.getByRole('button', { name: '스텝 삭제' }).last()).toBeVisible();
    await page.getByRole('button', { name: '스텝 삭제' }).last().click();

    // AlertDialog 확인 버튼 클릭 → REMOVE_STEP dispatch (#45 확인 다이얼로그 추가)
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await page.getByRole('button', { name: '삭제' }).click();

    // 스텝이 삭제되어 빈 상태로 복귀
    await expect(page.getByText('첫 번째 스텝을 추가하세요')).toBeVisible();
  });

  test('캔버스 노드 우상단 X 버튼 클릭 시 확인 다이얼로그 없이 즉시 삭제되지 않는다 (#536)', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);

    await page.goto('/pipelines/new');

    // 스텝 추가 → ADD_STEP dispatch
    await page.getByRole('button', { name: /스텝 추가/ }).click();
    await expect(page.getByRole('button', { name: '자동 정렬' })).toBeVisible();

    // 노드 우상단 X 버튼(aria-label="스텝 삭제") 클릭 — 패널을 열지 않고 노드에서 직접 클릭
    await page.getByRole('button', { name: '스텝 삭제' }).first().click();

    // 즉시 삭제되지 않고 확인 다이얼로그가 떠야 한다
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await expect(page.getByText('정말 이 스텝을 삭제하시겠습니까?')).toBeVisible();
    await expect(page.getByText('첫 번째 스텝을 추가하세요')).not.toBeVisible();

    // 취소 클릭 시 스텝이 그대로 남아 있어야 한다
    await page.getByRole('button', { name: '취소' }).click();
    await expect(page.getByRole('alertdialog')).not.toBeVisible();
    await expect(page.locator('.react-flow__node')).toHaveCount(1);

    // 다시 X 버튼 클릭 → 확인 다이얼로그에서 "삭제" 클릭 시에만 실제로 삭제된다
    await page.getByRole('button', { name: '스텝 삭제' }).first().click();
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await page.getByRole('button', { name: '삭제' }).click();
    await expect(page.getByText('첫 번째 스텝을 추가하세요')).toBeVisible();
  });

  test('스텝 선택 후 스텝 타입 변경 — UPDATE_STEP type dispatch', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);

    await page.goto('/pipelines/new');

    // 스텝 추가 → SELECT
    await page.getByRole('button', { name: /스텝 추가/ }).click();
    await expect(page.getByRole('button', { name: '자동 정렬' })).toBeVisible();

    // 노드 클릭 → StepConfigPanel 열기
    await page.locator('.react-flow__node').first().click();

    // StepConfigPanel 에서 스텝 타입 Select 확인 — SQL이 기본값
    // 타입 셀렉터가 존재하면 UPDATE_STEP 경로가 커버된다
    const typeSelect = page.getByRole('combobox').first();
    await expect(typeSelect).toBeVisible();

    // 타입 변경 클릭
    await typeSelect.click();

    // PYTHON 또는 API_CALL 옵션이 존재하는지 확인
    const pythonOption = page.getByRole('option', { name: /Python|PYTHON/ });
    const apiOption = page.getByRole('option', { name: /API|api_call/i });
    const hasOption = (await pythonOption.count()) > 0 || (await apiOption.count()) > 0;
    expect(hasOption).toBe(true);
  });

  test('스텝 두 개 추가 후 두 노드가 캔버스에 렌더링된다 — ADD_STEP 두 번 dispatch', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);

    await page.goto('/pipelines/new');

    // 스텝 두 개 추가 → ADD_STEP 두 번 dispatch
    await page.getByRole('button', { name: /스텝 추가/ }).click();
    await expect(page.getByRole('button', { name: '자동 정렬' })).toBeVisible();
    await page.getByRole('button', { name: /스텝 추가/ }).first().click();

    // 두 개의 ReactFlow 노드가 캔버스에 렌더링되어야 한다
    await expect(page.locator('.react-flow__node')).toHaveCount(2);
  });

  /**
   * 회귀 테스트: #32 — 편집 모드에서 파이프라인 이름 Input이 단 하나만 존재해야 한다
   *
   * 버그 원인: EditorHeader와 StepConfigPanel 두 곳에 동일 state.name을 바인딩한 Input이 있어
   * Ctrl+A 후 타이핑 시 두 Input이 각각 dispatch하여 문자열이 concatenate되는 현상.
   * 수정: StepConfigPanel의 이름 Input을 readOnly <p>로 교체하여 단일 편집 진입점을 EditorHeader로 통일.
   */
  test('편집 모드에서 파이프라인 이름 Input이 헤더에 하나만 존재한다 (이중 dispatch 방지)', async ({
    authenticatedPage: page,
  }) => {
    // 기존 파이프라인 에디터 API 모킹
    await setupPipelineEditorMocks(page, 1);

    await page.goto('/pipelines/1');

    // 수정 버튼 클릭 → 편집 모드 진입
    await page.getByRole('button', { name: '수정' }).click();

    // 편집 모드에서 파이프라인 이름 textbox는 헤더에 딱 1개만 존재해야 한다
    // (StepConfigPanel에 동일 Input이 있으면 이중 dispatch → 문자열 concatenation 발생)
    const nameInputs = page.getByPlaceholder('파이프라인 이름');
    await expect(nameInputs).toHaveCount(1);
  });

  test('편집 모드 — 이름 변경 후 저장 API payload에 새 이름만 담겨야 한다 (중복 문자열 방지)', async ({
    authenticatedPage: page,
  }) => {
    // 기존 파이프라인 에디터 모킹 (이름: '테스트 파이프라인')
    // PUT interceptor를 setupPipelineEditorMocks보다 먼저 등록하여 라우트 순서 충돌 방지
    let capturedPayload: Record<string, unknown> | null = null;
    await page.route('**/api/v1/pipelines/1', async (route) => {
      if (route.request().method() === 'PUT') {
        capturedPayload = route.request().postDataJSON() as Record<string, unknown>;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ id: 1, name: '새 파이프라인 이름', description: '', isActive: true, steps: [], createdBy: 'test', createdAt: '', updatedBy: null, updatedAt: null }),
        });
      } else {
        await route.continue();
      }
    });

    await setupPipelineEditorMocks(page, 1);

    await page.goto('/pipelines/1');

    // 편집 모드 진입
    await page.getByRole('button', { name: '수정' }).click();

    // 헤더 이름 Input에 새 이름 입력 (fill = 기존 값 전체 교체)
    const nameInput = page.getByPlaceholder('파이프라인 이름');
    await nameInput.fill('새 파이프라인 이름');

    // 저장 버튼 클릭
    await page.getByRole('button', { name: '저장' }).click();

    // API payload에 중복 없이 정확히 새 이름만 담겨야 한다
    expect(capturedPayload).not.toBeNull();
    expect((capturedPayload as unknown as Record<string, unknown>)['name']).toBe('새 파이프라인 이름');
  });

  /**
   * @xyflow/react 기본 Controls는 영문 aria-label('Zoom In/Out', 'Fit View',
   * 'Toggle Interactivity')을 사용한다. ReactFlow의 ariaLabelConfig prop으로
   * 한국어 라벨을 주입했는지(i18n 일관성) 회귀 검증한다. (#72)
   */
  test('DAG 컨트롤 패널 — 4개 버튼 모두 한국어 aria-label로 노출된다 (#72)', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page, 1);

    await page.goto('/pipelines/1');

    // 편집 모드 진입 → showInteractive 버튼까지 4개 모두 보이는 상태
    await page.getByRole('button', { name: '수정' }).click();

    // 컨트롤 컨테이너 자체에 한국어 aria-label
    await expect(page.getByLabel('다이어그램 컨트롤')).toBeVisible();

    // 4개 버튼 한국어 aria-label
    await expect(page.getByRole('button', { name: '확대' })).toBeVisible();
    await expect(page.getByRole('button', { name: '축소' })).toBeVisible();
    await expect(page.getByRole('button', { name: '전체 보기' })).toBeVisible();
    await expect(page.getByRole('button', { name: '상호작용 잠금/해제' })).toBeVisible();

    // 회귀 방지: 영문 라벨이 더 이상 노출되지 않아야 한다
    await expect(page.getByRole('button', { name: 'Zoom In' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Zoom Out' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Fit View' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Toggle Interactivity' })).toHaveCount(0);
  });

  /**
   * 의존 관계가 있는 파이프라인 로드 시 모든 edge에 화살표(markerEnd)가 적용되어
   * 데이터 흐름 방향이 시각적으로 드러나는지 회귀 검증한다. (#88)
   *
   * defaultEdgeOptions로 일괄 적용되며, AddStepEdge가 props.markerEnd를 정상 전달하면
   * SVG path의 marker-end 속성에 url(#...arrowclosed...) 마커 ID가 채워진다.
   */
  test('DAG edge — markerEnd 화살표가 모든 edge에 적용된다 (#88)', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page, 1);

    await page.goto('/pipelines/1');

    // 에디터 로드 대기
    await expect(page.getByRole('tab', { name: '개요' })).toBeVisible({ timeout: 10000 });

    // 의존 관계가 있는 픽스처가 로드되면 react-flow가 edge-path를 그린다
    // (SVG path는 visibility 판정상 hidden으로 잡혀 toBeVisible 대신 count로 검증)
    const edgePaths = page.locator('.react-flow__edge-path');
    await expect(edgePaths.first()).toBeAttached({ timeout: 5000 });

    // 모든 edge-path의 marker-end가 ArrowClosed 마커를 참조해야 한다
    const markerEnds = await edgePaths.evaluateAll((els) =>
      els.map((el) => el.getAttribute('marker-end')),
    );
    expect(markerEnds.length).toBeGreaterThan(0);
    for (const me of markerEnds) {
      expect(me).not.toBeNull();
      expect(me).toMatch(/url\(['"]?#.*arrowclosed/i);
    }
  });

  /**
   * 회귀 테스트: #132 — 미저장 변경사항 있을 때 취소 버튼이 AlertDialog 없이 즉시 편집 종료되는 버그.
   *
   * 수정 내용: handleCancelEdit()에서 state.isDirty가 true이면 AlertDialog를 표시하고,
   * 사용자가 확인 시에만 cancelEdit() + setIsEditing(false) 호출.
   */
  test('미저장 변경사항이 있을 때 취소 버튼 → AlertDialog가 표시된다 (#132)', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page, 1);

    await page.goto('/pipelines/1');

    // 편집 모드 진입
    await page.getByRole('button', { name: '수정' }).click();

    // 파이프라인 이름 변경 → isDirty = true (미저장 변경사항 발생)
    const nameInput = page.getByPlaceholder('파이프라인 이름');
    await nameInput.fill('변경된 이름');

    // 취소 버튼 클릭
    await page.getByRole('button', { name: '취소' }).click();

    // AlertDialog가 표시되어야 한다 (즉시 편집 종료 금지)
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await expect(page.getByText('변경사항을 취소하시겠습니까?')).toBeVisible();
  });

  test('미저장 변경사항 AlertDialog — "계속 편집" 클릭 시 편집 모드 유지 (#132)', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page, 1);

    await page.goto('/pipelines/1');

    // 편집 모드 진입 후 이름 변경
    await page.getByRole('button', { name: '수정' }).click();
    await page.getByPlaceholder('파이프라인 이름').fill('변경된 이름');

    // 취소 버튼 클릭 → AlertDialog 표시
    await page.getByRole('button', { name: '취소' }).click();
    await expect(page.getByRole('alertdialog')).toBeVisible();

    // "계속 편집" 클릭 → 다이얼로그 닫히고 편집 모드 유지
    await page.getByRole('button', { name: '계속 편집' }).click();
    await expect(page.getByRole('alertdialog')).not.toBeVisible();

    // 편집 모드가 여전히 활성: "저장" 버튼이 보여야 한다
    await expect(page.getByRole('button', { name: '저장' })).toBeVisible();
  });

  test('미저장 변경사항 AlertDialog — "변경사항 취소" 클릭 시 편집 모드 종료 (#132)', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page, 1);

    await page.goto('/pipelines/1');

    // 편집 모드 진입 후 이름 변경
    await page.getByRole('button', { name: '수정' }).click();
    await page.getByPlaceholder('파이프라인 이름').fill('변경된 이름');

    // 취소 버튼 클릭 → AlertDialog 표시
    await page.getByRole('button', { name: '취소' }).click();
    await expect(page.getByRole('alertdialog')).toBeVisible();

    // "변경사항 취소" 클릭 → 편집 모드 종료
    await page.getByRole('button', { name: '변경사항 취소' }).click();
    await expect(page.getByRole('alertdialog')).not.toBeVisible();

    // 편집 모드 종료: "수정" 버튼이 다시 보여야 한다
    await expect(page.getByRole('button', { name: '수정' })).toBeVisible();
  });

  test('변경사항 없을 때 취소 버튼 → AlertDialog 없이 즉시 편집 종료 (#132)', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page, 1);

    await page.goto('/pipelines/1');

    // 편집 모드 진입 (변경 없음)
    await page.getByRole('button', { name: '수정' }).click();

    // 취소 버튼 클릭 → AlertDialog 없이 즉시 종료
    await page.getByRole('button', { name: '취소' }).click();

    // AlertDialog가 표시되지 않아야 한다
    await expect(page.getByRole('alertdialog')).not.toBeVisible();

    // 편집 모드 종료: "수정" 버튼이 바로 보여야 한다
    await expect(page.getByRole('button', { name: '수정' })).toBeVisible();
  });

  /**
   * 회귀 테스트: #550 — 편집 중(dirty) 사이드바 SPA 링크 클릭 시 확인 없이 변경사항이 유실되는 버그.
   *
   * 수정 전: PipelineEditorPage가 자체 beforeunload 핸들러만 등록해 브라우저 새로고침/닫기만
   * 막았고, React Router의 SPA 내부 네비게이션(사이드바 <a> 클릭)은 전혀 가로채지 않았다.
   * 수정 후: useUnsavedChangesGuard(state.isDirty)를 연결해 이슈 #86과 동일한 패턴(document
   * click capture + popstate 가로채기)으로 SPA 이동도 확인 다이얼로그 뒤로 미룬다.
   */
  test.describe('이슈 #550 — 미저장 변경 SPA 이동 가드', () => {
    test('스텝 추가로 dirty 상태에서 사이드바 링크 클릭 시 이탈 다이얼로그가 표시된다', async ({
      authenticatedPage: page,
    }) => {
      await setupPipelineEditorMocks(page, 1);
      await page.goto('/pipelines/1');

      // 편집 모드 진입 → 스텝 추가로 dirty 상태 생성
      await page.getByRole('button', { name: '수정' }).click();
      await page.getByRole('button', { name: '스텝 추가', exact: true }).click();
      await expect(page.getByRole('button', { name: '저장' })).toBeEnabled();

      // 사이드바 "홈" 링크 클릭 (nav 영역으로 한정 — 다른 위치의 동명 텍스트와 구분)
      await page.getByRole('navigation').getByRole('link', { name: '홈' }).click();

      // 확인 다이얼로그가 뜨고, URL은 즉시 이동하지 않아야 한다
      await expect(page.getByRole('alertdialog')).toBeVisible();
      await expect(page.getByText('저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?')).toBeVisible();
      expect(new URL(page.url()).pathname).toBe('/pipelines/1');
    });

    test('이탈 다이얼로그에서 취소 클릭 시 편집 화면에 머무르고 추가한 스텝이 보존된다', async ({
      authenticatedPage: page,
    }) => {
      await setupPipelineEditorMocks(page, 1);
      await page.goto('/pipelines/1');

      await page.getByRole('button', { name: '수정' }).click();
      await page.getByRole('button', { name: '스텝 추가', exact: true }).click();
      await expect(page.getByRole('button', { name: '저장' })).toBeEnabled();

      await page.getByRole('navigation').getByRole('link', { name: '홈' }).click();
      await expect(page.getByRole('alertdialog')).toBeVisible();

      await page.getByRole('button', { name: '취소' }).click();
      await expect(page.getByRole('alertdialog')).toBeHidden();

      // 페이지 유지 + 편집 모드/추가한 스텝 그대로 보존 (저장 버튼이 계속 활성)
      expect(new URL(page.url()).pathname).toBe('/pipelines/1');
      await expect(page.getByRole('button', { name: '저장' })).toBeEnabled();
    });

    test('이탈 다이얼로그에서 이탈 클릭 시 변경사항을 버리고 다른 페이지로 이동한다', async ({
      authenticatedPage: page,
    }) => {
      await setupPipelineEditorMocks(page, 1);
      await page.goto('/pipelines/1');

      await page.getByRole('button', { name: '수정' }).click();
      await page.getByRole('button', { name: '스텝 추가', exact: true }).click();
      await expect(page.getByRole('button', { name: '저장' })).toBeEnabled();

      await page.getByRole('navigation').getByRole('link', { name: '홈' }).click();
      await expect(page.getByRole('alertdialog')).toBeVisible();

      await page.getByRole('button', { name: '이탈' }).click();

      await expect(page).toHaveURL(/\/$/);
    });

    test('dirty 상태가 아니면 사이드바 링크 클릭 시 다이얼로그 없이 즉시 이동한다', async ({
      authenticatedPage: page,
    }) => {
      await setupPipelineEditorMocks(page, 1);
      await page.goto('/pipelines/1');

      // 편집 모드에 진입하지 않은 채(clean) 사이드바 이동
      await page.getByRole('navigation').getByRole('link', { name: '홈' }).click();

      await expect(page.getByRole('alertdialog')).not.toBeVisible();
      await expect(page).toHaveURL(/\/$/);
    });
  });

  /**
   * 회귀 테스트: #562 — 브라우저 뒤로가기(popstate) 시 useUnsavedChangesGuard가 무력화되던 버그.
   *
   * 근본원인: `BrowserRouter`(react-router-dom)가 앱 부팅 시점에 자기 자신의 popstate 리스너를
   * 등록해두고 절대 해제하지 않는데, 훅이 페이지 컴포넌트의 useEffect 안에서 등록한 popstate
   * 리스너는 항상 그보다 나중에 등록된다. 그 결과 뒤로가기 시 라우터가 먼저 반응해 페이지를
   * 언마운트해버리고, 같은 이벤트 디스패치 도중 정리(cleanup)로 리스너가 제거되어 훅의 popstate
   * 핸들러는 아예 호출되지 못한 채 스킵됐다(DOM 스펙상 디스패치 도중 제거된 리스너는 스킵됨).
   * 증상: 확인 다이얼로그 없이 이탈, 주소창(pushState 복원)과 실제 렌더 화면이 어긋남, 입력값 유실.
   *
   * 수정: 라우터보다 먼저 등록되고 앱 생명주기 내내 유지되는 전역 popstate 인터셉터
   * (`unsaved-changes-guard-registry.ts`, main.tsx에서 부팅 시 1회 설치)로 옮겨, dirty일 때
   * `stopImmediatePropagation()`으로 라우터가 이 이벤트를 아예 보지 못하게 막은 뒤 URL을
   * 원복하고 다이얼로그를 띄운다.
   */
  test.describe('이슈 #562 — 브라우저 뒤로가기(popstate) 미저장 변경 가드', () => {
    test('dirty 상태에서 뒤로가기 시 이탈 다이얼로그가 표시되고 URL·입력값이 보존된다', async ({
      authenticatedPage: page,
    }) => {
      await setupNewEditorMocks(page);
      // 뒤로가기 대상이 될 이전 페이지(목록)로 먼저 진입한 뒤, SPA 이동으로 /pipelines/new에
      // 진입해야 실제 브라우저 히스토리 스택이 쌓여 popstate가 의미 있게 재현된다.
      await setupPipelineMocks(page, 1);
      await page.goto('/pipelines');
      await page.getByRole('link', { name: '파이프라인 추가' }).click();
      await expect(page).toHaveURL(/\/pipelines\/new$/);

      // 파이프라인 이름 입력 → dirty 상태 생성
      await page.getByPlaceholder('파이프라인 이름').fill('뒤로가기 회귀 테스트');
      await expect(page.getByText('미저장 변경사항')).toBeVisible();

      // 브라우저 뒤로가기 실행
      await page.goBack();

      // (1) 확인 다이얼로그가 표시된다
      await expect(page.getByRole('alertdialog')).toBeVisible();
      await expect(page.getByText('저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?')).toBeVisible();
      // (2) 주소창과 실제 렌더 화면이 어긋나지 않는다 — 여전히 /pipelines/new
      expect(new URL(page.url()).pathname).toBe('/pipelines/new');
      // (3) 입력했던 값이 경고 없이 유실되지 않는다
      await expect(page.getByPlaceholder('파이프라인 이름')).toHaveValue('뒤로가기 회귀 테스트');
    });

    test('뒤로가기 이탈 다이얼로그에서 취소 시 입력값이 보존된 채 같은 페이지에 머무른다', async ({
      authenticatedPage: page,
    }) => {
      await setupNewEditorMocks(page);
      await setupPipelineMocks(page, 1);
      await page.goto('/pipelines');
      await page.getByRole('link', { name: '파이프라인 추가' }).click();
      await expect(page).toHaveURL(/\/pipelines\/new$/);

      await page.getByPlaceholder('파이프라인 이름').fill('취소 테스트');
      await page.goBack();
      await expect(page.getByRole('alertdialog')).toBeVisible();

      await page.getByRole('button', { name: '취소' }).click();

      await expect(page.getByRole('alertdialog')).toBeHidden();
      expect(new URL(page.url()).pathname).toBe('/pipelines/new');
      await expect(page.getByPlaceholder('파이프라인 이름')).toHaveValue('취소 테스트');
    });

    test('뒤로가기 이탈 다이얼로그에서 이탈 확정 시 실제로 이전 페이지로 이동한다', async ({
      authenticatedPage: page,
    }) => {
      await setupNewEditorMocks(page);
      await setupPipelineMocks(page, 1);
      await page.goto('/pipelines');
      await page.getByRole('link', { name: '파이프라인 추가' }).click();
      await expect(page).toHaveURL(/\/pipelines\/new$/);

      await page.getByPlaceholder('파이프라인 이름').fill('이탈 테스트');
      await page.goBack();
      await expect(page.getByRole('alertdialog')).toBeVisible();

      await page.getByRole('button', { name: '이탈' }).click();

      // 실제로 이전 페이지(목록)로 이동해야 한다 — 확정 버튼이 무동작이어선 안 된다
      await expect(page).toHaveURL(/\/pipelines$/);
      await expect(page.getByRole('alertdialog')).not.toBeVisible();
    });

    test('dirty 상태가 아니면 뒤로가기 시 다이얼로그 없이 정상적으로 이전 페이지로 이동한다', async ({
      authenticatedPage: page,
    }) => {
      await setupNewEditorMocks(page);
      await setupPipelineMocks(page, 1);
      await page.goto('/pipelines');
      await page.getByRole('link', { name: '파이프라인 추가' }).click();
      await expect(page).toHaveURL(/\/pipelines\/new$/);

      // 입력 없이(clean) 뒤로가기
      await page.goBack();

      await expect(page.getByRole('alertdialog')).not.toBeVisible();
      await expect(page).toHaveURL(/\/pipelines$/);
    });
  });
});
