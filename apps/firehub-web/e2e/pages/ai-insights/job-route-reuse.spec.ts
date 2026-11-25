import type { Page } from '@playwright/test';

import type { UpdateProactiveJobRequest } from '@/api/proactive';

import { createJob, createTemplate, createTemplateSection } from '../../factories/ai-insight.factory';
import {
  mockProactivePermissions,
  setupJobDetailMocks,
  setupNewJobMocks,
} from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 스마트 작업 상세 ↔ 새 작업 — 같은 화면(컴포넌트)이 재사용될 때 편집 상태를 다시 잡는지 검증 (WD-996)
 * - 새 작업 폼에서 만든 뒤 상세로 가고, 브라우저 뒤로/앞으로로 오가도 읽기/편집 모드가 경로에 맞아야 한다.
 */
test.beforeEach(async ({ authenticatedPage: page }) => {
  await mockProactivePermissions(page);
});

/** 새 작업 폼에서 필수 칸을 채워 「만들기」 → 상세(id=1) 읽기 모드까지 간다. */
async function createAndLandOnDetail(page: Page) {
  await setupNewJobMocks(page);
  await setupJobDetailMocks(page, 1);
  await mockApi(page, 'POST', '/api/v1/proactive/jobs', createJob({ id: 1 }));

  await page.goto('/ai-insights/jobs/new');
  await page.locator('#job-name').fill('새 작업');
  await page.locator('#mode-manual').click();
  await page.locator('#job-prompt').fill('프롬프트');
  await page.getByRole('button', { name: '만들기', exact: true }).click();
  await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);
  await expect(page.getByRole('button', { name: '편집', exact: true })).toBeVisible();
}

/**
 * 현재 history 스택 뒤에 path 항목을 추가하고(새 항목으로 뒤로 가기가 목적지가 된다) popstate 로
 * 라우터를 깨워 같은 컴포넌트 안에서(재사용 화면) SPA 이동한다 — document 재로딩이 아니라
 * router 이동이어야 이탈 확인 창이 뜨고 컴포넌트 상태(가드 카운터 등)가 유지된다.
 * 앱 안에는 /jobs/1 → /jobs/new 방향의 직접 링크가 없어 history API 로 항목을 만든다(WD-1042).
 * state 모양(usr·key·idx)은 react-router 의 pushState 와 같다 — idx 가 없으면 라우터가
 * 이동 거리를 계산하지 못한다.
 */
async function pushHistoryEntry(page: Page, path: string) {
  await page.evaluate((to) => {
    const idx = ((window.history.state as { idx?: number } | null)?.idx ?? 0) + 1;
    const state = { usr: null, key: `wd1042-${idx}`, idx };
    window.history.pushState(state, '', to);
    window.dispatchEvent(new PopStateEvent('popstate', { state }));
  }, path);
}

test.describe('스마트 작업 — 상세 ↔ 새 작업 화면 재사용 (WD-996)', () => {
  test('상세에서 뒤로 가 같은 화면 안 /jobs/new 로 오면 만들기 버튼과 빈 편집 칸이 보인다', async ({
    authenticatedPage: page,
  }) => {
    await createAndLandOnDetail(page);

    await page.goBack();

    await expect(page).toHaveURL(/\/ai-insights\/jobs\/new$/);
    await expect(page.getByRole('button', { name: '만들기', exact: true })).toBeVisible();
    const name = page.locator('#job-name');
    await expect(name).toBeEditable();
    await expect(name).toHaveValue('');
  });

  test('새 작업 폼에서 앞으로 가 상세로 오면 읽기 모드다', async ({ authenticatedPage: page }) => {
    await createAndLandOnDetail(page);
    await page.goBack();
    await expect(page.getByRole('button', { name: '만들기', exact: true })).toBeVisible();

    await page.locator('#job-name').fill('쓰다 만 이름');
    // 양성 단언: 이름을 입력하면 미저장 표시가 실제로 보여야 아래 0개 단언이 의미가 있다
    await expect(page.getByText('미저장 변경사항')).toBeVisible();
    await page.goForward();
    // 입력한 게 있어 가드의 이탈 확인 창이 뜬다 — 「이탈」로 확정해 상세로 이동한다
    await page.getByRole('alertdialog').getByRole('button', { name: '이탈' }).click();

    await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);
    await expect(page.getByRole('button', { name: '편집', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '만들기', exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '저장', exact: true })).toHaveCount(0);
    await expect(page.getByText('미저장 변경사항')).toHaveCount(0);
  });

  /** 복제 시험용 목 — 원본(1)과 복사본(2) 상세를 준비한다. */
  async function setupCloneMocks(page: Page) {
    const copy = createJob({ id: 2, name: '매일 현황 리포트 (복사본)', enabled: false });
    await setupJobDetailMocks(page, 1);
    await mockApi(page, 'POST', '/api/v1/proactive/jobs', copy);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/2', copy);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/2/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/2/anomaly-events', []);
  }

  test('복제 뒤 새 작업 상세는 편집 모드다', async ({ authenticatedPage: page }) => {
    await setupCloneMocks(page);

    await page.goto('/ai-insights/jobs/1');
    await page.getByRole('button', { name: '복제' }).click();

    await expect(page).toHaveURL(/\/ai-insights\/jobs\/2\?tab=overview$/);
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeVisible();
  });

  test('복제 뒤 뒤로 가면 원본 작업은 읽기 모드다', async ({ authenticatedPage: page }) => {
    await setupCloneMocks(page);

    await page.goto('/ai-insights/jobs/1');
    await page.getByRole('button', { name: '복제' }).click();
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeVisible();

    await page.goBack();

    await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);
    await expect(page.getByRole('button', { name: '편집', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '저장', exact: true })).toHaveCount(0);
  });

  test('목표 기반 모드로 바꿔 만든 뒤 뒤로 가 새 작업으로 오면 기본 모드로 돌아온다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewJobMocks(page);
    await setupJobDetailMocks(page, 1);
    await mockApi(page, 'POST', '/api/v1/proactive/templates', createTemplate({ id: 77 }));
    // 템플릿 자동 생성 API — 정해진 섹션 구성으로 응답한다
    await mockApi(page, 'POST', '/api/v1/proactive/templates/generate', { sections: [createTemplateSection()] });
    const unmocked: string[] = [];
    // 모킹 안 된 /api 요청은 dev 서버 프록시가 5xx 로 돌려준다 — 그런 응답이 없는지 끝에서 확인한다
    page.on('response', (res) => {
      if (res.url().includes('/api/') && res.status() >= 500) unmocked.push(res.url());
    });
    await mockApi(page, 'POST', '/api/v1/proactive/jobs', createJob({ id: 1 }));
    await page.goto('/ai-insights/jobs/new');
    await page.locator('#job-name').fill('새 작업');
    await page.locator('#mode-goal').click();
    await expect(page.locator('#mode-goal')).toBeChecked();
    await page.locator('#business-question').fill('이번 주 화재 현황은 어떤가요');
    await page.getByRole('button', { name: '템플릿 자동 생성' }).click();
    await page.getByRole('button', { name: '만들기', exact: true }).click();
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);
    // 상세가 실제로 그려진 뒤에 뒤로 간다 — URL 만 바뀐 채 곧바로 돌아가면 화면은 상세를 거치지 않아 재사용 상황이 아니다
    await expect(page.getByRole('button', { name: '편집', exact: true })).toBeVisible();

    await page.goBack();

    await expect(page).toHaveURL(/\/ai-insights\/jobs\/new$/);
    await expect(page.locator('#mode-manual')).toBeChecked();
    expect(unmocked).toEqual([]);
  });

  test('복제 뒤 취소로 읽기 모드가 된 다음 뒤로·앞으로 가도 복사본은 읽기 모드다', async ({
    authenticatedPage: page,
  }) => {
    await setupCloneMocks(page);

    await page.goto('/ai-insights/jobs/1');
    await page.getByRole('button', { name: '복제' }).click();
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeVisible();
    await page.getByRole('button', { name: '취소', exact: true }).click();
    await expect(page.getByRole('button', { name: '편집', exact: true })).toBeVisible();

    await page.goBack();
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);
    await page.goForward();

    await expect(page).toHaveURL(/\/ai-insights\/jobs\/2/);
    await expect(page.getByRole('button', { name: '편집', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '저장', exact: true })).toHaveCount(0);
  });
  test('저장 요청 중 뒤로 가 /jobs/new 로 오면 늦게 온 저장 응답이 새 작업 폼을 읽기 모드로 바꾸지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await createAndLandOnDetail(page);
    // 저장(PUT) 응답을 붙잡아 두었다가 화면을 옮긴 뒤 돌려준다
    let releaseSave: () => void = () => {};
    const saveHeld = new Promise<void>((resolve) => {
      releaseSave = resolve;
    });
    await page.route('**/api/v1/proactive/jobs/1', async (route) => {
      if (route.request().method() !== 'PUT') return route.fallback();
      await saveHeld;
      await route.fulfill({ json: createJob({ id: 1, name: '바꾼 이름' }) });
    });

    await page.getByRole('button', { name: '편집', exact: true }).click();
    await page.locator('#job-name').fill('바꾼 이름');
    await page.getByRole('button', { name: '저장', exact: true }).click();
    await page.goBack();
    // 이 시점에는 폼이 바뀌어 이탈 확인 창이 반드시 뜬다 — 기다려 눌러야 느린 환경에서도 결정적이다
    const leave = page.getByRole('alertdialog').getByRole('button', { name: '이탈' });
    await leave.click();
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/new$/);
    // 바뀐 화면이 /jobs/new 임을 먼저 확정한다 — 버튼 단언은 아래 회수·토스트 앵커 뒤에 둔다 (WD-1042)
    await expect(page.getByRole('heading', { name: '새 스마트 작업' })).toBeVisible();

    releaseSave();
    await expect(page.getByText('작업이 저장되었습니다.')).toBeVisible();

    await expect(page.getByRole('button', { name: '만들기', exact: true })).toBeVisible();
    await expect(page.locator('#job-name')).toBeEditable();
  });

  test('다른 작업 저장 중에도 /jobs/new 의 만들기 버튼이 잠기지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await createAndLandOnDetail(page);
    // 저장(PUT) 응답을 붙잡아 두었다가 화면을 옮긴 뒤 돌려준다
    let releaseSave: () => void = () => {};
    const saveHeld = new Promise<void>((resolve) => {
      releaseSave = resolve;
    });
    await page.route('**/api/v1/proactive/jobs/1', async (route) => {
      if (route.request().method() !== 'PUT') return route.fallback();
      await saveHeld;
      await route.fulfill({ json: createJob({ id: 1, name: '바꾼 이름' }) });
    });

    await page.getByRole('button', { name: '편집', exact: true }).click();
    await page.locator('#job-name').fill('바꾼 이름');
    await page.getByRole('button', { name: '저장', exact: true }).click();
    await page.goBack();
    // 이 시점에는 폼이 바뀌어 이탈 확인 창이 반드시 뜬다 — 기다려 눌러야 느린 환경에서도 결정적이다
    const leave = page.getByRole('alertdialog').getByRole('button', { name: '이탈' });
    await leave.click();
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/new$/);
    await expect(page.getByRole('heading', { name: '새 스마트 작업' })).toBeVisible();

    // 필수 칸을 채워 폼을 유효하게 만든다 — 빈 칸은 isSaving 과 무관한 #52 규칙으로 버튼을 따로 잠그므로,
    // 잠금 판정은 유효한 폼에서만 의미가 있다
    await page.locator('#job-name').fill('새 작업');
    await page.locator('#job-prompt').fill('프롬프트');

    // 라벨이 「만들기」든 「만드는 중...」이든 같은 버튼을 잡아 — 잠겼는지(비활성)와 라벨을 둘 다 본다
    const createButton = page.getByRole('button', { name: /만들기|만드는 중/ });
    await expect(createButton).toHaveCount(1);
    // jobs/1 의 저장이 진행 중이어도 새 작업 화면의 「만들기」은 잠기지 않는다
    await expect(createButton).toBeEnabled();
    await expect(createButton).toHaveText('만들기');

    releaseSave();
    // 회수가 실제로 일어났다는 확신 — 토스트는 onSuccess 가 가드와 무관하게 띄우기 때문이다
    await expect(page.getByText('작업이 저장되었습니다.')).toBeVisible();

    // 회수 후에도 그대로 — 늦게 온 응답이 /jobs/new 의 만들기 버튼을 잠그지 않는다
    await expect(createButton).toBeEnabled();
    await expect(createButton).toHaveText('만들기');
  });

  test('저장 중 다른 화면을 거쳐 같은 작업으로 돌아와 다시 편집한 내용이 늦은 응답으로 사라지지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await createAndLandOnDetail(page);
    // 저장(PUT) 응답을 붙잡아 두었다가 같은 작업으로 왕복한 뒤 돌려준다
    let releaseSave: () => void = () => {};
    const saveHeld = new Promise<void>((resolve) => {
      releaseSave = resolve;
    });
    // 저장을 반영한 조회(저장한 이름)가 돌아와도 폼은 다시 채우지 않아야 한다 — 재조회가 실제로 새 이름을 줄 때도
    // 다시 채운 내용이 유지되는지 확인한다. 라이브 백엔드는 저장을 반영해 같은 경로를 걷는다(WD-1042 완료 기준 1)
    // 서버는 버전을 가진다 — 저장할 때마다 버전이 오르고, 옛 버전을 든 저장은 실제 서버처럼 409 로 거부된다.
    // 왕복으로 늦은 응답이 가드에 걸려 시드·기준이 옛 버전에 멈춰 있으면, 재저장은 첫 저장 이전 버전을 든다 —
    // 왕복 후 폼은 늘 첫 저장 이전 내용이라 그 재저장의 409 는 맞는 충돌이다(WD-1042 반려 3).
    let serverName = createJob({ id: 1 }).name;
    let version = createJob({ id: 1 }).updatedAt;
    const beforeFirstSave = createJob({ id: 1 }).updatedAt;
    let seq = 1;
    const puts: UpdateProactiveJobRequest[] = [];
    await page.route('**/api/v1/proactive/jobs/1', async (route) => {
      const method = route.request().method();
      if (method === 'PUT') {
        const body = route.request().postDataJSON() as UpdateProactiveJobRequest;
        puts.push(body);
        // 붙잡은 첫 저장도 서버 처리 시점 기준으로 본다 — 두 번째 저장은 이미 풀려 즉시 처리된다
        await saveHeld;
        if ('expectedUpdatedAt' in body && body.expectedUpdatedAt !== version) {
          await route.fulfill({
            status: 409,
            contentType: 'application/json',
            body: JSON.stringify({
              status: 409,
              code: 'EDIT_CONFLICT',
              message: '이 작업은 편집을 시작한 뒤 다른 곳에서 변경되었습니다. 다시 불러온 뒤 저장하세요.',
            }),
          });
          return;
        }
        seq += 1;
        version = `2026-10-30T10:00:0${seq}.111111`;
        serverName = body.name ?? serverName;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createJob({ id: 1, name: serverName, updatedAt: version })),
        });
        return;
      }
      if (method === 'GET') {
        await route.fulfill({ json: createJob({ id: 1, name: serverName, updatedAt: version }) });
        return;
      }
      return route.fallback();
    });

    await page.getByRole('button', { name: '편집', exact: true }).click();
    await page.locator('#job-name').fill('바꾼 이름');
    await page.getByRole('button', { name: '저장', exact: true }).click();
    await page.goBack();
    // 이 시점에는 폼이 바뀌어 이탈 확인 창이 반드시 뜬다 — 기다려 눌러야 느린 환경에서도 결정적이다
    const leave = page.getByRole('alertdialog').getByRole('button', { name: '이탈' });
    await leave.click();
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/new$/);

    // /jobs/new 에서 다른 이름을 채워 편집 상태를 만든다 — 같은 작업으로 되돌아가는 경로(A → new → A)를 만들기 위함이다
    await page.locator('#job-name').fill('새로 채운 이름');
    await expect(page.getByText('미저장 변경사항')).toBeVisible();

    await page.goForward();
    const leaveAgain = page.getByRole('alertdialog').getByRole('button', { name: '이탈' });
    await leaveAgain.click();
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);
    // 돌아오면 라우트가 다시 바뀌어 읽기 모드로 잡힌다 — 편집을 다시 시작해 지금 내용을 다시 채운다
    await page.getByRole('button', { name: '편집', exact: true }).click();
    await page.locator('#job-name').fill('되돌아와 쓴 이름');
    await expect(page.getByText('미저장 변경사항')).toBeVisible();

    // 저장(PUT)이 아직 진행 중이라도 상세의 저장 버튼은 잠겨 있다 — 시작 카운터와 비교해 잠금을 끊으면
    // 두 번째 PUT 이 나가 내 저장끼리 409 충돌 창을 낸다(고치기 전엔 잠겨 있던 것, WD-1042 반려 1)
    await expect(page.getByRole('button', { name: '저장 중...', exact: true })).toBeDisabled();

    releaseSave();
    // 응답 처리와 가드 판정을 먼저 확정한다 — 토스트는 onSuccess 가 띄우고 가드 바로 앞이라, 이게 보여야 늦은 응답이 도착해 판정이 끝난 상태다
    await expect(page.getByText('작업이 저장되었습니다.')).toBeVisible();

    // 늦은 저장 응답이 돌아와도 지금 편집 중인 내용·미저장 표시·편집 모드가 유지돼야 한다.
    // 재조회가 반영됐는지는 제목(h1 = 조회 job, 실시간)으로 확인한다 — 응답이 서버에서 나온 시점만 보면
    // 클라이언트가 아직 안 받아 아무 결과나 통과해, 가드가 없어도 초록이 되기 때문이다(WD-1042 완료 기준 1)
    await expect(page.getByRole('heading', { level: 1, name: '바꾼 이름' })).toBeVisible();
    await expect(page.locator('#job-name')).toHaveValue('되돌아와 쓴 이름');
    await expect(page.getByText('미저장 변경사항')).toBeVisible();
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeVisible();

    // 왕복 후 재저장은 첫 저장 이전 버전을 든다 — 왕복 뒤 폼은 늘 첫 저장 이전 내용이라 그 재저장은
    // 서버(첫 저장 반영)와 버전이 달라야 한다. 버전만 앞당기면 재저장이 첫 저장 내용을 충돌 없이
    // 되돌려 버린다(WD-1042 반려 3). 그래서 여기서는 409 충돌 창으로 가고, 「다시 불러오기」로
    // 첫 저장 내용을 다시 받아 확인한다.
    await page.getByRole('button', { name: '저장', exact: true }).click();
    const conflict = page.getByRole('alertdialog', { name: '다른 곳에서 먼저 변경되었습니다' });
    await expect(conflict).toBeVisible();
    // 충돌 창은 두 번째 PUT 을 서버가 받은 뒤에야 뜬다 — 요청이 수집된 뒤에 단언해 레이스를 없앤다
    await expect.poll(() => puts.length).toBe(2);
    expect(puts[1].expectedUpdatedAt).toBe(beforeFirstSave);

    await conflict.getByRole('button', { name: '다시 불러오기' }).click();
    await expect(conflict).toHaveCount(0);
    // 다시 불러온 내용은 첫 저장이 서버에 반영된 결과다 — 왕복 뒤 남아 있던 되돌아와 쓴 이름은 버려진다.
    // 편집 모드는 그대로이고(내 변경을 버렸을 뿐) 미저장 표시는 없어진다
    await expect(page.locator('#job-name')).toHaveValue('바꾼 이름');
    await expect(page.getByRole('heading', { level: 1, name: '바꾼 이름' })).toBeVisible();
    await expect(page.getByText('미저장 변경사항')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeVisible();
  });

  test('만들기 요청 중 뒤로 가 작업 상세로 오면 늦은 성공 응답이 그 화면을 다른 상세로 끌지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewJobMocks(page);
    await setupJobDetailMocks(page, 1);
    // 만들기(POST) 성공 응답(id=2)을 붙잡아 두었다가 화면을 옮긴 뒤 돌려준다 —
    // 가드가 없으면 이 응답이 지금 화면(작업 1)을 /jobs/2 로 끌고 간다
    let releaseSave: () => void = () => {};
    const saveHeld = new Promise<void>((resolve) => {
      releaseSave = resolve;
    });
    await page.route('**/api/v1/proactive/jobs', async (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      await saveHeld;
      await route.fulfill({ status: 201, json: createJob({ id: 2 }) });
    });

    await page.goto('/ai-insights/jobs/1');
    await expect(page.getByRole('button', { name: '편집', exact: true })).toBeVisible();

    // history 를 /jobs/1 → /jobs/new 로 만든다 — 앱 안에는 이 방향의 직접 링크가 없어
    // history 스택에 새 작업 항목을 추가한 뒤 popstate 로 라우터를 깬다.
    // 목록을 뒤로 가기 목표로 두면 컴포넌트가 언마운트돼 늦은 응답의 콜백 자체가
    // 실행되지 않아(TanStack Query 가 구독자 없이 콜백을 부르지 않는다) 가드를 시험할 수 없다.
    await pushHistoryEntry(page, '/ai-insights/jobs/new');
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/new$/);
    await expect(page.getByRole('button', { name: '만들기', exact: true })).toBeVisible();

    await page.locator('#job-name').fill('새 작업');
    await page.locator('#mode-manual').click();
    await page.locator('#job-prompt').fill('프롬프트');
    await page.getByRole('button', { name: '만들기', exact: true }).click();

    await page.goBack();
    // 이 시점에는 폼이 바뀌어 이탈 확인 창이 반드시 뜬다 — 기다려 눌러야 느린 환경에서도 결정적이다
    const leave = page.getByRole('alertdialog').getByRole('button', { name: '이탈' });
    await leave.click();
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);

    releaseSave();
    // 응답 처리와 가드 판정을 먼저 확정한다 — 토스트는 onSuccess 가 가드보다 앞에서 띄우기 때문이다
    await expect(page.getByText('작업이 생성되었습니다.')).toBeVisible();

    // 늦은 성공 응답에도 작업 1 상세에 그대로 남아 있어야 한다 — id=2 상세로 끌려가지 않음
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);
    await expect(page.getByRole('button', { name: '편집', exact: true })).toBeVisible();
  });

  test('만들기 요청 중 뒤로 가 작업 상세로 오면 늦은 이름 중복 오류가 그 폼에 붙지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewJobMocks(page);
    await setupJobDetailMocks(page, 1);
    // 만들기(POST)를 409 이름 중복으로 붙잡아 두었다가 화면을 옮긴 뒤 돌려준다 — 실패 경로를 보기 때문
    let releaseSave: () => void = () => {};
    const saveHeld = new Promise<void>((resolve) => {
      releaseSave = resolve;
    });
    await page.route('**/api/v1/proactive/jobs', async (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      await saveHeld;
      await route.fulfill({
        status: 409,
        json: { code: 'DUPLICATE_NAME', message: '이미 사용 중인 이름입니다.' },
      });
    });

    await page.goto('/ai-insights/jobs/1');
    await expect(page.getByRole('button', { name: '편집', exact: true })).toBeVisible();

    await pushHistoryEntry(page, '/ai-insights/jobs/new');
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/new$/);
    await expect(page.getByRole('button', { name: '만들기', exact: true })).toBeVisible();

    await page.locator('#job-name').fill('새 작업');
    await page.locator('#mode-manual').click();
    await page.locator('#job-prompt').fill('프롬프트');
    await page.getByRole('button', { name: '만들기', exact: true }).click();

    await page.goBack();
    // 이 시점에는 폼이 바뀌어 이탈 확인 창이 반드시 뜬다 — 기다려 눌러야 느린 환경에서도 결정적이다
    const leave = page.getByRole('alertdialog').getByRole('button', { name: '이탈' });
    await leave.click();
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/1$/);
    // 늦은 409 가 붙을 자리를 만들기 위해 상세의 편집 폼을 연다
    await page.getByRole('button', { name: '편집', exact: true }).click();
    // 만들기(POST)가 아직 진행 중이어도 작업 상세의 저장 버튼은 잠기지 않는다 — 남의 만들기 진행이 「저장 중...」을
    // 만들지 않는지(만들기 잠금은 isNew 일 때만 센다) 먼저 본다(WD-1042 반려 3)
    const saveButton = page.getByRole('button', { name: '저장', exact: true });
    await expect(saveButton).toHaveText('저장');
    await expect(saveButton).toBeEnabled();
    // 전제 확인 — 늦은 409 가 아직 도착하지 않았다(POST 가 아직 붙잡혀 있음). 위 두 단언이 진행 중인 요청 위에서
    // 선 것임을 이 자리에서만 본다(끝의 긍정 대조가 멀리 있어 여기로는 안 보인다)
    await expect(page.getByText(/작업 생성에 실패했습니다|이미 사용 중인 이름입니다|작업을 만들지 못했습니다/)).toHaveCount(0);

    releaseSave();
    // 응답 처리 완료 앵커 — 떠난 뒤 이름 중복은 서버 문구 대신 보낸 이름을 넣은 토스트로 알린다(WD-1153).
    // 이 토스트가 뜬 뒤에 아래 단언을 해야 「아직 응답 전」에 0개로 통과하는 레이스가 없다
    await expect(
      page.getByText('「새 작업」 작업을 만들지 못했습니다 — 같은 이름의 작업이 이미 있습니다.'),
    ).toBeVisible();
    // 서버 문구는 떠난 칸을 가리키는 말이라 다른 화면에 남지 않는다
    await expect(page.getByText('이미 사용 중인 이름입니다.')).toHaveCount(0);
    // 가드를 지나면 다른 폼(작업 1)에는 이름 「칸」 오류가 붙지 않아야 한다
    await expect(page.locator('#job-name-error')).toHaveCount(0);
    await expect(page.locator('#job-name')).toHaveValue('매일 현황 리포트');

    // 긍정 대조: 같은 409 를 새 작업 폼에서 실제로 받으면 오류가 붙는다 — 위 0개 단언이 무의미하지 않다는 증거
    await page.goForward();
    await expect(page).toHaveURL(/\/ai-insights\/jobs\/new$/);
    await page.locator('#job-name').fill('새 작업');
    await page.locator('#mode-manual').click();
    await page.locator('#job-prompt').fill('프롬프트');
    await page.getByRole('button', { name: '만들기', exact: true }).click();

    // 긍정 대조의 목적은 「이름 칸 오류가 실제로 붙는다」는 것이므로 칸 오류(#job-name-error)의 본문으로 좁힌다.
    // 토스트 본문으로 세면 위 464행에서 띄운 서버 토스트(WD-1109)와 겹쳐 strict mode 위반이 난다.
    await expect(page.locator('#job-name-error')).toHaveText('이미 사용 중인 이름입니다.');
  });
});
