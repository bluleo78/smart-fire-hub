import { createJob, createMessage, createTemplate } from '../../factories/ai-insight.factory';
import {
  mockProactivePermissions,
  setupJobDetailMocks,
  setupJobListMocks,
  setupNotificationPanelMocks,
} from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { expectDialogContained, expectWrapped } from '../../fixtures/long-name-layout';
import { grantPipelinePermissions, mockPipelineDeleteImpact, setupPipelineMocks } from '../../fixtures/pipeline.fixture';

/**
 * 긴 이름 넘침 E2E (WD-632, WD-558 후속).
 *
 * 공백 없는 긴 작업명(서버 상한 200자)·템플릿명(상한 100자)은 줄바꿈 지점이 없어,
 * 그대로 두면 대화상자(grid) 칸을 내용 폭만큼 늘려 「삭제」 버튼을 대화상자·화면 밖으로 밀고,
 * 알림 패널 상세·작업 개요에서는 칸 밖으로 뻗어 잘린다. 확인·상세 화면은 이름을 전부 읽어야 하므로
 * 자르지 않고 칸 안에서 줄바꿈(overflow-wrap — 대화상자는 break-word 상속, 개별 요소는 wrap-anywhere, WD-648)되어야 한다.
 *
 * 줄바꿈 여부는 글자 요소 자체의 scrollWidth <= clientWidth 로 잰다 — 블록 요소의 오른쪽 끝은
 * 내용이 넘쳐도 부모 폭에 머물러, 끝 위치만 보면 넘침을 놓친다.
 */

const VIEWPORT_WIDTH = 1280;
/** 작업명 서버 상한(200자)을 꽉 채운 공백 없는 이름 */
const LONG_JOB_NAME = 'A'.repeat(200);
/** 템플릿명 상한(100자)을 꽉 채운 공백 없는 이름 */
const LONG_TEMPLATE_NAME = 'T'.repeat(100);

test.use({ viewport: { width: VIEWPORT_WIDTH, height: 800 } });

test.describe('긴 이름 넘침 (WD-632)', () => {
  test('작업 삭제 확인 — 200자 작업명이 줄바꿈되고 삭제 버튼이 화면 안에 남는다', async ({
    authenticatedPage: page,
  }) => {
    await mockProactivePermissions(page);
    await setupJobDetailMocks(page, 1);
    // 상세 조회만 긴 이름으로 덮어쓴다(나중에 등록한 route 가 우선)
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', createJob({ id: 1, name: LONG_JOB_NAME }));

    await page.goto('/ai-insights/jobs/1');
    await page.getByRole('button', { name: '삭제', exact: true }).click();

    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toBeVisible();
    // 이름은 잘리지 않고 확인 문구에 전부 들어 있으며, 문구 안에서 줄바꿈된다
    const description = dialog.getByText(LONG_JOB_NAME, { exact: false });
    await expect(description).toContainText(LONG_JOB_NAME);
    await expectWrapped(description);
    await expectDialogContained(dialog, dialog.getByRole('button', { name: '삭제', exact: true }));
  });

  test('삭제 확인 details 영역 — 긴 이름이 든 영향 안내도 창 안에서 줄바꿈된다', async ({ authenticatedPage: page }) => {
    // 공용 DeleteConfirmDialog 의 details 칸(사용처마다 다른 영향 안내)도 AlertDialogContent 칸 고정으로 함께 막히는지 본다.
    await grantPipelinePermissions(page);
    await setupPipelineMocks(page, 1);
    await mockPipelineDeleteImpact(page, 0, 200, false, 'NONE', [
      { triggerId: 10, triggerName: '연쇄 트리거', pipelineId: 100, pipelineName: LONG_JOB_NAME },
    ]);

    await page.goto('/pipelines');
    const impactRequest = page.waitForRequest((req) => new URL(req.url()).pathname === '/api/v1/pipelines/1/delete-impact');
    await page.getByRole('button', { name: '삭제' }).first().click();
    await impactRequest;

    const dialog = page.getByRole('alertdialog');
    const item = dialog.getByTestId('pipeline-delete-chain-impact').getByRole('listitem');
    await expect(item).toContainText(LONG_JOB_NAME);
    await expectWrapped(item);
    await expectDialogContained(dialog, dialog.getByRole('button', { name: '삭제', exact: true }));
  });

  test('알림 패널 상세 — 200자 작업명이 패널 폭 안에서 줄바꿈된다', async ({ authenticatedPage: page }) => {
    await setupNotificationPanelMocks(page, [
      createMessage({ jobName: LONG_JOB_NAME, title: '긴 작업 리포트', read: false }),
    ]);
    await setupJobListMocks(page);

    await page.goto('/ai-insights/jobs');
    await page.getByRole('button', { name: /AI 인사이트 알림/ }).click();
    const panel = page.getByRole('dialog', { name: 'AI 인사이트 알림' });
    await expect(panel).toBeVisible();
    await panel.getByRole('button', { name: /긴 작업 리포트/ }).click();

    // 상세 화면의 작업명 — 전부 읽히고(자르지 않음) 글자 요소 안에서 줄바꿈된다
    const jobName = panel.getByText(LONG_JOB_NAME, { exact: true });
    await expect(jobName).toHaveText(LONG_JOB_NAME);
    await expectWrapped(jobName);
  });

  test('작업 개요 — 기본 정보의 200자 작업명이 칸 안에서 줄바꿈된다', async ({ authenticatedPage: page }) => {
    await mockProactivePermissions(page);
    await setupJobDetailMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', createJob({ id: 1, name: LONG_JOB_NAME }));

    await page.goto('/ai-insights/jobs/1');
    // 기본 정보 카드의 「작업명」 값 — 머리글 제목과 구분하려 라벨 바로 다음 값 요소를 잡는다
    const value = page.getByText('작업명', { exact: true }).locator('xpath=following-sibling::div[1]');
    await expect(value).toHaveText(LONG_JOB_NAME);
    await expectWrapped(value);
  });

  test('템플릿 삭제 확인 — 100자 템플릿명이 줄바꿈되고, 영향 작업 이름이 글머리표와 같은 줄에서 시작한다', async ({
    authenticatedPage: page,
  }) => {
    await mockProactivePermissions(page);
    const template = createTemplate({ id: 67, name: LONG_TEMPLATE_NAME, builtin: false });
    await mockApi(page, 'GET', '/api/v1/proactive/templates/67', template);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [
      createJob({ id: 106, templateId: 67, templateName: LONG_TEMPLATE_NAME, name: LONG_JOB_NAME }),
    ]);

    await page.goto('/ai-insights/templates/67');
    await expect(page.getByRole('heading', { name: LONG_TEMPLATE_NAME })).toBeVisible({ timeout: 10000 });
    await page.getByRole('button', { name: '삭제', exact: true }).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    // 확인 문구의 템플릿명이 문구 안에서 줄바꿈된다
    const question = dialog.getByText(`"${LONG_TEMPLATE_NAME}" 템플릿을 삭제하시겠습니까?`, { exact: false });
    await expect(question).toBeVisible();
    await expectWrapped(question);
    await expectDialogContained(dialog, dialog.getByRole('button', { name: '삭제', exact: true }));

    // 영향 작업 이름은 글머리표 다음 줄로 떨어지지 않고 같은 줄에서 시작한다 — 버튼 윗변이 항목 윗변과 같다
    const item = page.getByTestId('template-delete-impact').getByRole('listitem');
    const jobButton = item.getByRole('button', { name: LONG_JOB_NAME });
    const itemBox = await item.boundingBox();
    const buttonBox = await jobButton.boundingBox();
    expect(itemBox).not.toBeNull();
    expect(buttonBox).not.toBeNull();
    expect(Math.abs(buttonBox!.y - itemBox!.y)).toBeLessThanOrEqual(2);
  });
});
