/**
 * useSmtpSettingsForm 단위 테스트 — 워크스페이스 전용 SMTP 6키 폼(#712).
 *
 * 이 훅의 계약 중 틀리면 조용한 사고가 되는 것들:
 * - "설정됨"은 <b>서버 응답</b>의 호스트로만 판정한다(입력 중인 폼 값으로 판정하면 저장 전에 해제
 *   버튼이 뜬다).
 * - 저장은 6키를 한 벌로 보낸다(빠진 키는 저장되지 않아 발송 코드의 폴백에 기대게 된다).
 *   예외는 비밀번호 하나: 저장된 비밀번호가 있고 칸이 비었으면 키를 빼서 서버가 유지하게 한다.
 * - 서버 마스크(`****…`)는 절대 편집 가능한 입력에 들어가지 않는다(덧붙이면 진짜 비밀번호로 저장된다).
 * - 해제는 `DELETE /settings/smtp` 한 번이고, 성공했을 때만 화면을 미설정으로 바꾼다.
 */
import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { settingsApi } from '../api/settings';
import type { ResolvedSettingResponse } from '../types/settings';
import { useSmtpSettingsForm } from './useSmtpSettingsForm';

vi.mock('../api/settings', () => ({
  settingsApi: {
    getByPrefix: vi.fn(),
    update: vi.fn(),
    clearSmtp: vi.fn(),
  },
}));

// 토스트는 이 훅의 계약이 아니다 — jsdom 에서 sonner 가 DOM 을 건드리지 않게 막는다.
vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}));

const saved = (key: string, value: string): ResolvedSettingResponse => ({
  key,
  value,
  description: null,
  updatedAt: null,
  overridden: true,
  tenantEditable: true,
});

const CONFIGURED = [
  saved('smtp.host', 'smtp.gmail.com'),
  saved('smtp.port', '2525'),
  saved('smtp.username', 'user@example.com'),
  saved('smtp.password', '****3f2a'),
  saved('smtp.starttls', 'false'),
  saved('smtp.from_address', 'noreply@example.com'),
];

const mockedGet = vi.mocked(settingsApi.getByPrefix);
const mockedUpdate = vi.mocked(settingsApi.update);
const mockedClear = vi.mocked(settingsApi.clearSmtp);

async function renderLoaded() {
  const hook = renderHook(() => useSmtpSettingsForm());
  await waitFor(() => expect(hook.result.current.isLoading).toBe(false));
  return hook;
}

beforeEach(() => {
  vi.clearAllMocks();
  mockedGet.mockResolvedValue({ data: CONFIGURED } as never);
  mockedUpdate.mockResolvedValue({} as never);
  mockedClear.mockResolvedValue({} as never);
});

describe('useSmtpSettingsForm', () => {
  it('빈 응답이면 미설정이고, 폼은 빈 값 + 포트 587 + STARTTLS 켜짐으로 시작하며 dirty 가 아니다', async () => {
    mockedGet.mockResolvedValue({ data: [] } as never);
    const { result } = await renderLoaded();

    expect(mockedGet).toHaveBeenCalledWith('smtp');
    expect(result.current.configured).toBe(false);
    expect(result.current.form).toEqual({
      'smtp.host': '',
      'smtp.port': '587',
      'smtp.username': '',
      'smtp.password': '',
      'smtp.starttls': 'true',
      'smtp.from_address': '',
    });
    expect(result.current.hasChanges).toBe(false);
    expect(result.current.testNotice).toContain('저장된 SMTP 설정이 없어');
  });

  it('저장된 응답은 값 그대로 시드되지만 비밀번호 마스크는 시드하지 않는다', async () => {
    const { result } = await renderLoaded();

    expect(result.current.configured).toBe(true);
    expect(result.current.form['smtp.port']).toBe('2525');
    // 마스크 대신 빈 칸 + "저장됨" 플래그다.
    expect(result.current.form['smtp.password']).toBe('');
    expect(result.current.passwordSaved).toBe(true);
    expect(result.current.form['smtp.starttls']).toBe('false');
    expect(result.current.testNotice).toBeNull();
  });

  it('설정됨 판정은 폼이 아니라 서버 응답을 본다 — 호스트를 입력해도 저장 전에는 미설정이다', async () => {
    mockedGet.mockResolvedValue({ data: [] } as never);
    const { result } = await renderLoaded();

    act(() => result.current.updateField('smtp.host', 'smtp.ourcompany.com'));
    expect(result.current.hasChanges).toBe(true);
    expect(result.current.configured).toBe(false);
  });

  it('저장은 손대지 않은 키까지 6키를 한 벌로 보내고, 재조회로 설정됨·새 마스크를 반영한다', async () => {
    mockedGet.mockResolvedValueOnce({ data: [] } as never);
    const { result } = await renderLoaded();

    act(() => {
      result.current.updateField('smtp.host', 'smtp.ourcompany.com');
      result.current.updateField('smtp.password', 'app-secret');
      result.current.updateField('smtp.from_address', 'noreply@ourcompany.com');
    });
    mockedGet.mockResolvedValue({
      data: [
        saved('smtp.host', 'smtp.ourcompany.com'),
        saved('smtp.port', '587'),
        saved('smtp.username', ''),
        saved('smtp.password', '****cret'),
        saved('smtp.starttls', 'true'),
        saved('smtp.from_address', 'noreply@ourcompany.com'),
      ],
    } as never);
    await act(async () => {
      await result.current.handleSave();
    });

    expect(mockedUpdate).toHaveBeenCalledWith({
      settings: {
        'smtp.host': 'smtp.ourcompany.com',
        'smtp.port': '587',
        'smtp.username': '',
        'smtp.password': 'app-secret',
        'smtp.starttls': 'true',
        'smtp.from_address': 'noreply@ourcompany.com',
      },
    });
    expect(result.current.configured).toBe(true);
    // 평문은 폼에 남지 않는다 — 칸이 비워지고 "저장됨"으로 바뀌며 dirty 도 풀린다.
    expect(result.current.form['smtp.password']).toBe('');
    expect(result.current.passwordSaved).toBe(true);
    expect(result.current.hasChanges).toBe(false);
  });

  it('저장된 비밀번호가 있을 때 칸을 비워 두면 페이로드에서 smtp.password 를 뺀다(서버가 유지)', async () => {
    const { result } = await renderLoaded();

    act(() => result.current.updateField('smtp.host', 'smtp.ourcompany.com'));
    await act(async () => {
      await result.current.handleSave();
    });

    const sent = mockedUpdate.mock.calls[0][0].settings;
    expect(sent).not.toHaveProperty('smtp.password');
    // 마스크도, 빈 문자열도 실리지 않는다 — 나머지 5키는 그대로 간다.
    expect(Object.keys(sent).sort()).toEqual([
      'smtp.from_address',
      'smtp.host',
      'smtp.port',
      'smtp.starttls',
      'smtp.username',
    ]);
    for (const value of Object.values(sent)) expect(value.startsWith('****')).toBe(false);
  });

  it('저장된 비밀번호가 있어도 새 값을 입력하면 그 값을 보낸다', async () => {
    const { result } = await renderLoaded();

    act(() => result.current.updateField('smtp.password', 'new-secret'));
    await act(async () => {
      await result.current.handleSave();
    });

    expect(mockedUpdate.mock.calls[0][0].settings['smtp.password']).toBe('new-secret');
  });

  it('저장된 비밀번호가 없으면 빈 비밀번호를 빈 문자열로 보낸다 — 인증 없는 릴레이', async () => {
    mockedGet.mockResolvedValue({
      data: CONFIGURED.map((r) => (r.key === 'smtp.password' ? { ...r, value: '' } : r)),
    } as never);
    const { result } = await renderLoaded();
    expect(result.current.passwordSaved).toBe(false);

    act(() => result.current.updateField('smtp.host', 'relay.internal'));
    await act(async () => {
      await result.current.handleSave();
    });

    expect(mockedUpdate.mock.calls[0][0].settings['smtp.password']).toBe('');
  });

  it('필수 키(호스트·포트·발신자 주소)가 비면 PUT 을 보내지 않고 필드 오류를 단다', async () => {
    const { result } = await renderLoaded();

    act(() => {
      result.current.updateField('smtp.host', '  ');
      result.current.updateField('smtp.port', '');
      result.current.updateField('smtp.from_address', '');
      // 양성 대조군: 비워도 되는 키는 오류가 없다.
      result.current.updateField('smtp.username', '');
    });
    await act(async () => {
      await result.current.handleSave();
    });

    expect(mockedUpdate).not.toHaveBeenCalled();
    expect(Object.keys(result.current.errors).sort()).toEqual([
      'smtp.from_address',
      'smtp.host',
      'smtp.port',
    ]);
    // 조사는 라벨의 받침에 맞는다 — "포트을(를)" 같은 병기 표기가 아니다(#728 겸사 수정).
    expect(result.current.errors['smtp.host']).toBe('SMTP 호스트를 입력하세요');
    expect(result.current.errors['smtp.port']).toBe('포트를 입력하세요');
    expect(result.current.errors['smtp.from_address']).toBe('발신자 주소를 입력하세요');
  });

  it('발신자 주소가 이메일 형식이 아니면 PUT 을 보내지 않고 그 칸에 오류를 단다(#728)', async () => {
    const { result } = await renderLoaded();

    act(() => result.current.updateField('smtp.from_address', 'not-an-email'));
    await act(async () => {
      await result.current.handleSave();
    });

    expect(mockedUpdate).not.toHaveBeenCalled();
    expect(result.current.errors).toEqual({
      'smtp.from_address': '올바른 이메일 주소를 입력하세요 (예: noreply@example.com 또는 표시명 <noreply@example.com>)',
    });
  });

  it('"표시명 <주소>" 형태의 발신자는 그대로 저장된다 — 발송 코드가 받는 형태다(#728)', async () => {
    const { result } = await renderLoaded();

    act(() => result.current.updateField('smtp.from_address', 'Fire Hub <noreply@example.com>'));
    await act(async () => {
      await result.current.handleSave();
    });

    expect(mockedUpdate.mock.calls[0][0].settings['smtp.from_address']).toBe(
      'Fire Hub <noreply@example.com>',
    );
  });

  it('호스트 중간에 공백이 있으면 PUT 을 보내지 않는다(#728)', async () => {
    const { result } = await renderLoaded();

    act(() => result.current.updateField('smtp.host', 'smtp example.com'));
    await act(async () => {
      await result.current.handleSave();
    });

    expect(mockedUpdate).not.toHaveBeenCalled();
    expect(result.current.errors['smtp.host']).toBe('SMTP 호스트에는 공백을 넣을 수 없습니다');
  });

  it('호스트·발신자 주소는 앞뒤 공백을 떼어 보내고, 사용자 이름은 입력 그대로 보낸다(#728)', async () => {
    const { result } = await renderLoaded();

    act(() => {
      result.current.updateField('smtp.host', '  smtp.ourcompany.com ');
      result.current.updateField('smtp.from_address', ' noreply@ourcompany.com  ');
      // 음성 대조군: 선택 칸의 빈 값 의미(#661)를 건드리지 않으려고 다듬지 않는다.
      result.current.updateField('smtp.username', ' mailer ');
    });
    // 재조회가 실패해도(서버 값으로 덮어 주지 않아도) 칸과 원본이 보낸 값으로 맞아야 한다 —
    // 다듬기 전 문자열이 칸에 남으면 저장 직후에도 dirty 로 남는다.
    mockedGet.mockRejectedValue(new Error('boom'));
    await act(async () => {
      await result.current.handleSave();
    });

    const sent = mockedUpdate.mock.calls[0][0].settings;
    expect(sent['smtp.host']).toBe('smtp.ourcompany.com');
    expect(sent['smtp.from_address']).toBe('noreply@ourcompany.com');
    expect(sent['smtp.username']).toBe(' mailer ');
    expect(result.current.form['smtp.host']).toBe('smtp.ourcompany.com');
    expect(result.current.form['smtp.from_address']).toBe('noreply@ourcompany.com');
    expect(result.current.hasChanges).toBe(false);
  });

  it('포트가 범위를 벗어나면 PUT 을 보내지 않는다', async () => {
    const { result } = await renderLoaded();

    act(() => result.current.updateField('smtp.port', '0'));
    await act(async () => {
      await result.current.handleSave();
    });

    expect(mockedUpdate).not.toHaveBeenCalled();
    expect(result.current.errors['smtp.port']).toBe('1~65535 사이의 정수를 입력하세요');
  });

  it('저장 후 재조회가 실패하면 보낸 값을 확정하고(dirty 해제) 낡음 안내를 세운다', async () => {
    const { result } = await renderLoaded();

    act(() => result.current.updateField('smtp.host', 'smtp.ourcompany.com'));
    mockedGet.mockRejectedValue(new Error('boom'));
    await act(async () => {
      await result.current.handleSave();
    });

    expect(mockedUpdate).toHaveBeenCalledTimes(1);
    expect(result.current.hasChanges).toBe(false);
    expect(result.current.form['smtp.host']).toBe('smtp.ourcompany.com');
    expect(result.current.staleNotice).toContain('화면을 다시 읽지 못했습니다');
  });

  it('해제는 clearSmtp 한 번으로 6키를 지우고 미설정 빈 폼으로 돌아간다(미저장 편집 포함)', async () => {
    const { result } = await renderLoaded();

    act(() => result.current.updateField('smtp.username', 'someone-else'));
    await act(async () => {
      await result.current.handleClear();
    });

    expect(mockedClear).toHaveBeenCalledTimes(1);
    expect(result.current.configured).toBe(false);
    expect(result.current.form['smtp.host']).toBe('');
    expect(result.current.form['smtp.username']).toBe('');
    expect(result.current.form['smtp.password']).toBe('');
    expect(result.current.hasChanges).toBe(false);
  });

  it('해제가 실패하면 설정된 상태를 그대로 둔다', async () => {
    mockedClear.mockRejectedValue(new Error('boom'));
    const { result } = await renderLoaded();

    await act(async () => {
      await result.current.handleClear();
    });

    expect(result.current.configured).toBe(true);
    expect(result.current.form['smtp.host']).toBe('smtp.gmail.com');
    expect(result.current.passwordSaved).toBe(true);
    expect(result.current.isClearing).toBe(false);
  });

  it('최초 조회가 실패하면 loadFailed 를 세우고 재시도로 복구된다', async () => {
    mockedGet.mockRejectedValueOnce(new Error('boom'));
    const { result } = await renderLoaded();
    expect(result.current.loadFailed).toBe(true);

    await act(async () => {
      await result.current.retryInitialLoad();
    });
    expect(result.current.loadFailed).toBe(false);
    expect(result.current.form['smtp.host']).toBe('smtp.gmail.com');
  });
});
