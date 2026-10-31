import { createAuditLog } from '../../factories/admin.factory';
import { setupAdminAuth } from '../../fixtures/admin.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 감사 로그 — AI 자격증명·AI 설정·SMTP 설정·알림 채널·프로필 변경(WD-465)
 * - api 가 새로 남기는 ai_credential·ai_settings·smtp_settings·user_channel_preference 행과 프로필 수정(resource=user)
 *   행이 영문 raw 대신 한글 라벨로 보이는지 검증한다.
 * - 리소스 필터에 서버가 그 값으로 남기는 기록이 없던 「데이터 임포트」(data_import)·「시스템」(system)이 빠지고,
 *   새 리소스가 resource 쿼리로 실리는지 검증한다.
 * - 임베딩 설정 저장(embedding_settings, WD-469) 행도 같은 기준으로 한글 라벨·필터를 검증한다.
 */
const CREDENTIAL_UPDATE = createAuditLog({
  id: 61,
  username: 'credadmin',
  actionType: 'UPDATE',
  resource: 'ai_credential',
  resourceId: 'CHAT',
  description: 'AI 자격증명 수정: 채팅 (바뀐 항목: API 키)',
  metadata: { name: '채팅', slot: 'CHAT', agentType: 'sdk', changedFields: ['apiKey'] },
});
const AI_SETTINGS_UPDATE = createAuditLog({
  id: 62,
  username: 'aiadmin',
  actionType: 'UPDATE',
  resource: 'ai_settings',
  resourceId: 'ai',
  description: 'AI 설정 수정 (바뀐 항목: 모델·시스템 프롬프트)',
  metadata: { name: 'AI 설정', changedFields: ['ai.model', 'ai.system_prompt'] },
});
const SMTP_DELETE = createAuditLog({
  id: 63,
  username: 'smtpadmin',
  actionType: 'DELETE',
  resource: 'smtp_settings',
  resourceId: 'smtp',
  description: 'SMTP 설정 삭제',
  metadata: { name: 'SMTP 설정', changedFields: ['smtp.host', 'smtp.password'] },
});
const EMBEDDING_UPDATE = createAuditLog({
  id: 66,
  username: 'embedadmin',
  actionType: 'UPDATE',
  resource: 'embedding_settings',
  resourceId: 'embedding',
  description: '임베딩 설정 수정 (바뀐 항목: 모델·API 키)',
  metadata: { name: '임베딩 설정', changedFields: ['model', 'apiKey'] },
});
const CHANNEL_OFF = createAuditLog({
  id: 64,
  username: 'channeluser',
  actionType: 'STATUS_CHANGE',
  resource: 'user_channel_preference',
  resourceId: 'EMAIL',
  description: '알림 채널 사용 끄기: 이메일',
  metadata: { name: '이메일', channel: 'EMAIL', changedFields: ['enabled'], enabled: false },
});
const PROFILE_UPDATE = createAuditLog({
  id: 65,
  username: 'profileuser',
  actionType: 'UPDATE',
  resource: 'user',
  resourceId: '9',
  description: '프로필 수정: profileuser (바뀐 항목: 이름)',
  metadata: { name: 'profileuser', changedFields: ['name'] },
});

test.describe('감사 로그 — 설정·프로필 변경(WD-465)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse([
        CREDENTIAL_UPDATE,
        AI_SETTINGS_UPDATE,
        SMTP_DELETE,
        EMBEDDING_UPDATE,
        CHANNEL_OFF,
        PROFILE_UPDATE,
      ]),
    );
  });

  test('설정 변경 행의 액션·리소스가 한글 라벨로 보인다', async ({ authenticatedPage: page }) => {
    // 미등록 액션·리소스는 console.warn 을 남긴다 — 경고가 없어야 모두 등록된 것이다.
    const warnings: string[] = [];
    page.on('console', (m) => {
      if (m.type() === 'warning' && m.text().startsWith('[AuditLog]')) warnings.push(m.text());
    });
    await page.goto('/admin/audit-logs');
    const row = (name: string) => page.getByRole('row').filter({ hasText: name });
    await expect(row('credadmin').getByRole('cell', { name: '수정', exact: true })).toBeVisible();
    await expect(row('credadmin').getByRole('cell', { name: 'AI 자격증명', exact: true })).toBeVisible();
    await expect(row('aiadmin').getByRole('cell', { name: 'AI 설정', exact: true })).toBeVisible();
    await expect(row('smtpadmin').getByRole('cell', { name: '삭제', exact: true })).toBeVisible();
    await expect(row('smtpadmin').getByRole('cell', { name: 'SMTP 설정', exact: true })).toBeVisible();
    await expect(row('embedadmin').getByRole('cell', { name: '임베딩 설정', exact: true })).toBeVisible();
    await expect(row('channeluser').getByRole('cell', { name: '상태 변경', exact: true })).toBeVisible();
    await expect(row('channeluser').getByRole('cell', { name: '알림 채널 설정', exact: true })).toBeVisible();
    await expect(row('profileuser').getByRole('cell', { name: '사용자', exact: true })).toBeVisible();
    // 영문 raw 리소스 값은 보이지 않는다
    for (const raw of ['ai_credential', 'ai_settings', 'smtp_settings', 'embedding_settings', 'user_channel_preference']) {
      await expect(page.getByRole('cell', { name: raw, exact: true })).toHaveCount(0);
    }
    expect(warnings).toEqual([]);
  });

  test('리소스 필터에 기록 없는 「데이터 임포트」·「시스템」이 없고 새 리소스가 resource 쿼리로 실린다', async ({
    authenticatedPage: page,
  }) => {
    await page.goto('/admin/audit-logs');
    await expect(page.getByRole('row').filter({ hasText: 'credadmin' })).toBeVisible();
    const filter = page.getByRole('combobox', { name: '리소스 필터' });

    await filter.click();
    // 서버가 그 값으로 남기는 기록이 없어 고르면 항상 빈 목록이던 선택지 — 빠져 있어야 한다.
    await expect(page.getByRole('option', { name: '데이터 임포트', exact: true })).toHaveCount(0);
    await expect(page.getByRole('option', { name: '시스템', exact: true })).toHaveCount(0);
    // 실제 기록되는 리소스는 그대로 있다(임포트는 데이터셋 리소스로 남는다).
    await expect(page.getByRole('option', { name: '데이터셋', exact: true })).toBeVisible();

    const credentialReq = page.waitForRequest(
      (r) =>
        new URL(r.url()).pathname === '/api/v1/admin/audit-logs' &&
        new URL(r.url()).searchParams.get('resource') === 'ai_credential',
    );
    await page.getByRole('option', { name: 'AI 자격증명', exact: true }).click();
    await credentialReq;
    await expect(filter).toHaveText('AI 자격증명');

    for (const [label, value] of [
      ['AI 설정', 'ai_settings'],
      ['SMTP 설정', 'smtp_settings'],
      ['임베딩 설정', 'embedding_settings'],
      ['알림 채널 설정', 'user_channel_preference'],
    ] as const) {
      const req = page.waitForRequest(
        (r) =>
          new URL(r.url()).pathname === '/api/v1/admin/audit-logs' &&
          new URL(r.url()).searchParams.get('resource') === value,
      );
      await filter.click();
      await page.getByRole('option', { name: label, exact: true }).click();
      await req;
      await expect(filter).toHaveText(label);
    }
  });

  test('상세에 설명·리소스 라벨과 바뀐 항목 이름이 보인다', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/audit-logs');
    const dialog = page.getByRole('dialog');

    await page.getByRole('row').filter({ hasText: 'credadmin' }).click();
    await expect(dialog.getByText('AI 자격증명 수정: 채팅 (바뀐 항목: API 키)', { exact: true })).toBeVisible();
    await expect(dialog.getByText('AI 자격증명 (CHAT)', { exact: true })).toBeVisible();
    await expect(dialog.getByText(/"changedFields": \[/)).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();

    // 임베딩 설정(WD-469) — 바뀐 항목 이름만 있고 키 값은 없다.
    await page.getByRole('row').filter({ hasText: 'embedadmin' }).click();
    await expect(dialog.getByText('임베딩 설정 수정 (바뀐 항목: 모델·API 키)', { exact: true })).toBeVisible();
    await expect(dialog.getByText('임베딩 설정 (embedding)', { exact: true })).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();

    await page.getByRole('row').filter({ hasText: 'channeluser' }).click();
    await expect(dialog.getByText('알림 채널 사용 끄기: 이메일', { exact: true })).toBeVisible();
    await expect(dialog.getByText('알림 채널 설정 (EMAIL)', { exact: true })).toBeVisible();
  });
});
