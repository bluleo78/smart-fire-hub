/**
 * `SettingsPage` 저장 오케스트레이션 단위 테스트.
 *
 * `AiCredentialFieldset.test.tsx` 는 자격증명 fieldset 자체(라디오·비밀 힌트·모델 4상태)를
 * `cred` prop 주입으로 검증한다. 이 파일은 그 아래 <b>페이지가 직접 소유한</b> 두 결정을
 * 검증한다 — 어느 컴포넌트 테스트로도 닿지 않는 로직이다:
 *
 * 1. <b>저장 순서</b>(동작 설정 6키 → 자격증명). `performSave()` 안의 두 `if` 블록 순서가
 *    바뀌어도(자문이 먼저 제안했다가 `AiCredentialController.validateOpencode` 를 직접 읽고
 *    뒤집은 그 순서) 컴포넌트 트리 모양은 전혀 달라지지 않는다 — 오직 두 API 호출이 일어나는
 *    <b>순서</b>만 문제가 된다.
 * 2. <b>확인 다이얼로그 게이팅</b>. `handleSaveClick` 이 `buildSaveConfirm` 결과가 있으면
 *    다이얼로그를 열고 `performSave` 를 <b>보류</b>해야 한다 — `AiCredentialFieldset.test.tsx`
 *    는 `buildSaveConfirm` 자체(순수 함수)와 라디오 클릭이 `save` 를 안 부르는 것만 봤을 뿐,
 *    "저장" 버튼을 눌렀을 때 실제로 다이얼로그가 막아서는지는 이 파일에서만 검증된다.
 *
 * `useAiCredentialForm`/`useSettingsOverrideForm`/`useSmtpSettingsForm`/
 * `useUnsavedChangesGuard` 를 전부 모킹한다 — 네트워크·Radix Select 팝오버 상호작용 없이
 * 오케스트레이션 로직만 격리해서 본다(이 코드베이스에 `SettingsPage` 전체를 렌더하는 기존
 * 선례가 없어, 모킹 폭을 최소로 유지했다: 이메일/임베딩 탭은 Radix `TabsContent` 가 비활성
 * 탭을 언마운트하므로 `defaultValue="ai"` 에서는 실제로 마운트되지 않는다 — 별도로 모킹하지
 * 않는다).
 */
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { settingsApi } from '../../api/settings';
import type { UseAiCredentialFormResult } from '../../hooks/useAiCredentialForm';
import { useAiCredentialForm } from '../../hooks/useAiCredentialForm';
import { useSettingsOverrideForm } from '../../hooks/useSettingsOverrideForm';
import SettingsPage from './SettingsPage';

vi.mock('../../api/settings', () => ({
  settingsApi: {
    update: vi.fn(),
    verifyAuthStatus: vi.fn(),
  },
}));

vi.mock('../../hooks/useAiCredentialForm', async () => {
  const actual = await vi.importActual<typeof import('../../hooks/useAiCredentialForm')>(
    '../../hooks/useAiCredentialForm',
  );
  return { ...actual, useAiCredentialForm: vi.fn() };
});

vi.mock('../../hooks/useSettingsOverrideForm', () => ({
  useSettingsOverrideForm: vi.fn(),
}));

vi.mock('../../hooks/useSmtpSettingsForm', () => ({
  useSmtpSettingsForm: () => ({ base: { hasChanges: false } }),
}));

vi.mock('../../hooks/useUnsavedChangesGuard', () => ({
  useDirtyAggregator: vi.fn(() => ({ isAnyDirty: false, makeReporter: () => vi.fn() })),
  useUnsavedChangesGuard: vi.fn(() => ({ dialog: null })),
}));

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}));

const mockedUseAiCredentialForm = vi.mocked(useAiCredentialForm);
const mockedUseSettingsOverrideForm = vi.mocked(useSettingsOverrideForm);
const mockedUpdate = vi.mocked(settingsApi.update);
const mockedVerifyAuthStatus = vi.mocked(settingsApi.verifyAuthStatus);

/** `UseAiCredentialFormResult` 조립기 — `AiCredentialFieldset.test.tsx` 의 것과 같은 계약. */
function makeCred(overrides: Partial<UseAiCredentialFormResult> = {}): UseAiCredentialFormResult {
  return {
    isLoading: false,
    loadFailed: false,
    isLocked: false,
    plane: 'tenant',
    tenantOwned: true,
    setPlane: vi.fn(),
    agentType: 'sdk',
    setAgentType: vi.fn(),
    payload: {},
    setPayloadField: vi.fn(),
    secretInputs: {},
    setSecretInput: vi.fn(),
    secretFieldNames: ['apiKey'],
    models: null,
    loadModels: vi.fn(),
    modelsError: null,
    canLoadModels: false,
    hasUnsavedInput: false,
    save: vi.fn(async () => true),
    staleNotice: null,
    reset: vi.fn(),
    ...overrides,
  };
}

/** 동작 설정 6키가 전부 유효한(NUMBER_RULES·system_prompt 통과) `useSettingsOverrideForm` 반환값. */
function makeBehavior(overrides: Partial<ReturnType<typeof useSettingsOverrideForm>> = {}) {
  const base = {
    isLoading: false,
    loadFailed: false,
    isClearing: false,
    setIsClearing: vi.fn(),
    settings: {},
    form: {
      'ai.model': 'claude-sonnet-5',
      'ai.max_turns': '10',
      'ai.system_prompt': '너는 유능한 어시스턴트다.',
      'ai.temperature': '0.5',
      'ai.max_tokens': '4096',
      'ai.session_max_tokens': '50000',
    },
    original: {},
    resyncFromServer: vi.fn(),
    errors: {},
    setErrors: vi.fn(),
    fieldState: vi.fn(() => 'inherited' as const),
    effectiveState: vi.fn(() => 'inherited' as const),
    isEditable: vi.fn(() => true),
    hasChanges: false,
    updateField: vi.fn(),
    handleReset: vi.fn(),
    handleClearOverride: vi.fn(),
    buildChangedPayload: vi.fn(() => ({ payload: {}, droppedChangedKeys: [] })),
    commitSaved: vi.fn(),
    refreshMeta: vi.fn().mockResolvedValue({}),
    retryInitialLoad: vi.fn(),
  };
  return { ...base, ...overrides } as unknown as ReturnType<typeof useSettingsOverrideForm>;
}

beforeEach(() => {
  vi.clearAllMocks();
  mockedVerifyAuthStatus.mockResolvedValue({ data: { valid: false } } as never);
  mockedUpdate.mockResolvedValue({} as never);
});

describe('SettingsPage — 저장 확인 다이얼로그 게이팅', () => {
  /**
   * <b>변종: `handleSaveClick` 이 `buildSaveConfirm` 결과를 무시하고 항상 `performSave()` 를
   * 바로 부른다</b>(`if (confirm) setSaveConfirm(confirm) else void performSave()` 를
   * `void performSave()` 로 바꾸는 것과 동치). plane==='platform' && tenantOwned===true 는
   * 저장이 DELETE 를 일으키는 유일한 경우이므로, 다이얼로그 없이 곧장 `cred.save()` 가 불리면
   * 이 테스트가 잡는다.
   */
  it('플랫폼으로 되돌리는 저장은 다이얼로그를 먼저 열고, 확인 전에는 save 를 부르지 않는다', async () => {
    const user = userEvent.setup();
    const cred = makeCred({ plane: 'platform', tenantOwned: true, hasUnsavedInput: false });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(makeBehavior({ hasChanges: false }));

    render(<SettingsPage />);

    await user.click(screen.getByRole('button', { name: '저장' }));

    expect(cred.save).not.toHaveBeenCalled();
    expect(screen.getByText('플랫폼 설정으로 되돌릴까요?')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '되돌리기' }));

    await waitFor(() => expect(cred.save).toHaveBeenCalledOnce());
  });

  it('파괴적 전환이 없으면 다이얼로그 없이 곧장 저장한다', async () => {
    const user = userEvent.setup();
    const cred = makeCred({ plane: 'tenant', tenantOwned: true, hasUnsavedInput: true });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(makeBehavior({ hasChanges: false }));

    render(<SettingsPage />);

    await user.click(screen.getByRole('button', { name: '저장' }));

    expect(screen.queryByText('플랫폼 설정으로 되돌릴까요?')).not.toBeInTheDocument();
    await waitFor(() => expect(cred.save).toHaveBeenCalledOnce());
  });
});

describe('SettingsPage — 저장 순서(동작 설정 → 자격증명)', () => {
  /**
   * <b>변종: `performSave` 안의 두 `if` 블록 순서를 바꾼다(자격증명 먼저, 동작 설정 다음)</b>.
   * `AiCredentialController.validateOpencode` 가 <b>지금 DB 에 저장된 `ai.model`</b> 을 새
   * `providerId` 와 비교하므로(코드 확인, 보고서 참고), 자격증명을 먼저 저장하면 opencode
   * 공급자 전환 + 모델 재선택을 한 저장에서 같이 할 때 거짓 400 이 난다 — 동작 설정을 먼저
   * 끝내야 이 비교가 항상 최신 상태를 본다.
   */
  it('저장 시 settingsApi.update(동작 설정) 가 cred.save() 보다 먼저 호출된다', async () => {
    const user = userEvent.setup();
    const order: string[] = [];

    const cred = makeCred({ plane: 'tenant', tenantOwned: true, hasUnsavedInput: true });
    cred.save = vi.fn(async () => {
      order.push('cred.save');
      return true;
    });
    mockedUpdate.mockImplementation(async () => {
      order.push('settingsApi.update');
      return {} as never;
    });

    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(
      makeBehavior({
        hasChanges: true,
        buildChangedPayload: vi.fn(() => ({ payload: { 'ai.model': 'openai/gpt-4o' }, droppedChangedKeys: [] })),
      }),
    );

    render(<SettingsPage />);
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(cred.save).toHaveBeenCalledOnce());
    expect(order).toEqual(['settingsApi.update', 'cred.save']);
  });

  it('동작 설정에 변경이 없으면 settingsApi.update 를 부르지 않고 자격증명만 저장한다', async () => {
    const user = userEvent.setup();
    const cred = makeCred({ plane: 'tenant', tenantOwned: true, hasUnsavedInput: true });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(makeBehavior({ hasChanges: false }));

    render(<SettingsPage />);
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(cred.save).toHaveBeenCalledOnce());
    expect(mockedUpdate).not.toHaveBeenCalled();
  });
});

describe('SettingsPage — 유형 전환 확인 다이얼로그(§193, fix round 1 item 3)', () => {
  /**
   * <b>변종: `handleSaveClick` 이 `typeChanged` 를 항상 `false` 로 넘긴다</b>(`SettingsPage.tsx`
   * 의 `typeChanged: cred.plane === 'tenant' && hasTypeChangedFromSaved(...)` 를 `typeChanged:
   * false` 로 바꾸는 것과 동치). 기존 mutant #12(willDelete 게이팅 제거)는 이 경로를 전혀
   * 지나지 않는다 — `willDelete` 는 여기서 처음부터 `false` 다. 유형만 바뀌었을 때(플랫폼
   * 삭제가 아님) 다이얼로그 없이 곧장 `cred.save()` 가 불리면 이 테스트가 잡는다.
   *
   * `useSavedAgentType` 은 실제 훅(모킹 안 함)이라, "서버와 동기화된 유형"을 realistic 하게
   * 재구성하려면 첫 렌더는 동기화 상태(hasUnsavedInput=false)로 opencode 를 저장된 값으로
   * 굳히고, 이어서 같은 mock 을 sdk 로 바꾼 뒤 dirty 상태로 rerender 해야 한다 — 그래야
   * savedAgentType 이 'opencode' 로 고정된 채 cred.agentType 만 'sdk' 로 갈라진다.
   */
  it('유형만 바뀌면(플랫폼 삭제 아님) 다이얼로그를 먼저 열고, 확인 전에는 save 를 부르지 않는다', async () => {
    const user = userEvent.setup();
    const synced = makeCred({
      plane: 'tenant',
      tenantOwned: true,
      agentType: 'opencode',
      hasUnsavedInput: false,
    });
    mockedUseAiCredentialForm.mockReturnValue(synced);
    mockedUseSettingsOverrideForm.mockReturnValue(makeBehavior({ hasChanges: false }));

    const { rerender } = render(<SettingsPage />);

    // 저장된 유형을 'opencode' 로 동기화시킨 뒤 — 유형만 로컬에서 'sdk' 로 바꾼다(아직 저장 전).
    const dirty = makeCred({
      plane: 'tenant',
      tenantOwned: true,
      agentType: 'sdk',
      hasUnsavedInput: true,
      save: synced.save,
    });
    mockedUseAiCredentialForm.mockReturnValue(dirty);
    rerender(<SettingsPage />);

    await user.click(screen.getByRole('button', { name: '저장' }));

    expect(dirty.save).not.toHaveBeenCalled();
    const dialog = await screen.findByRole('alertdialog');
    expect(within(dialog).getByText('자격증명 유형을 바꿀까요?')).toBeInTheDocument();
    expect(
      within(dialog).getByText(/유형을 OpenCode에서 Claude Agent SDK\(으\)로/),
    ).toBeInTheDocument();

    // 확인 버튼 라벨도 "저장"이라 — 페이지 메인 저장 버튼과 헷갈리지 않도록 다이얼로그 안에서만 찾는다.
    await user.click(within(dialog).getByRole('button', { name: '저장' }));

    await waitFor(() => expect(dirty.save).toHaveBeenCalledOnce());
  });
});

describe('SettingsPage — 되돌리기는 두 자원을 함께 되돌린다(Ruling #41, fix round 1 item 5)', () => {
  /**
   * <b>변종: 되돌리기 버튼이 `handleReset()` 만 부르고 `cred.reset()` 은 안 부른다</b>(원래
   * 코드 그대로 되돌리는 것과 동치). 화면엔 버튼이 하나뿐이라, 자격증명 쪽 편집이 남아 있으면
   * 사용자에게 "일부만 되돌아갔다"는 설명 안 되는 결과가 남는다.
   */
  it('되돌리기 클릭은 handleReset 과 cred.reset 을 모두 부른다', async () => {
    const user = userEvent.setup();
    const cred = makeCred({ plane: 'tenant', tenantOwned: true, hasUnsavedInput: true });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    const behavior = makeBehavior({ hasChanges: true });
    mockedUseSettingsOverrideForm.mockReturnValue(behavior);

    render(<SettingsPage />);
    await user.click(screen.getByRole('button', { name: '되돌리기' }));

    expect(behavior.handleReset).toHaveBeenCalledOnce();
    expect(cred.reset).toHaveBeenCalledOnce();
  });

  /**
   * <b>변종: 되돌리기 버튼의 `disabled` 조건이 `credDirty` 를 무시한다</b>(`!behaviorHasChanges`
   * 로 되돌리는 것과 동치). 동작 설정은 안 건드리고 자격증명(라디오·입력)만 건드린 상태에서
   * 버튼이 비활성이면, 사용자가 되돌릴 방법이 아예 없다.
   */
  it('동작 설정은 안 바뀌고 자격증명만 dirty 여도 되돌리기 버튼이 활성이다', () => {
    const cred = makeCred({ plane: 'tenant', tenantOwned: true, hasUnsavedInput: true });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(makeBehavior({ hasChanges: false }));

    render(<SettingsPage />);
    expect(screen.getByRole('button', { name: '되돌리기' })).toBeEnabled();
  });
});

describe('SettingsPage — 플랫폼 정의 목록에 해석된 ai.model 을 내려준다(Ruling #43, fix round 1 item 7)', () => {
  /**
   * <b>변종: `resolvedModel` 을 항상 빈 문자열로 내린다</b>(`behaviorOriginal['ai.model']` 을
   * `''` 로 바꾸는 것과 동치). `AiCredentialFieldset.test.tsx` 는 그 prop 이 넘어오면 잘
   * 그리는지만 본다 — 페이지가 <b>실제로 해석된 값을 골라 넘기는지</b>는 여기서만 검증된다.
   */
  it('플랫폼 정의 목록의 "모델" 행이 behaviorOriginal[\'ai.model\'] 값을 그대로 보여준다', () => {
    const cred = makeCred({
      plane: 'platform',
      tenantOwned: false,
      agentType: 'opencode',
      payload: { providerId: 'openai', baseURL: 'https://api.openai.com/v1' },
      secretFieldNames: ['apiKey'],
    });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(
      makeBehavior({ hasChanges: false, original: { 'ai.model': 'openai/gpt-4o' } }),
    );

    render(<SettingsPage />);
    // "모델"은 동작 설정 카드의 필드 라벨로도 나오므로(별개 자원), 정의 목록의 <dt> 로 좁힌다.
    expect(screen.getByText('모델', { selector: 'dt' })).toBeInTheDocument();
    expect(screen.getByText('openai/gpt-4o')).toBeInTheDocument();
  });
});

describe('SettingsPage — verifyAuth 는 자격증명 저장이 실제로 성공했을 때만(Ruling #42, fix round 1 item 6)', () => {
  /**
   * <b>변종: `performSave` 가 `cred.save()` 의 반환값을 무시하고 항상 `verifyAuth()` 를
   * 부른다</b>(`if (!saved) {} else if (...) verifyAuth()` 를 무조건 `verifyAuth()` 로 바꾸는
   * 것과 동치). 쓰기가 실패했는데도 인증 확인을 돌리면 낡은 자격증명을 검사해 사용자를
   * 혼란스럽게 한다.
   */
  it('cred.save() 가 false 를 돌려주면 verifyAuthStatus 를 부르지 않는다', async () => {
    const user = userEvent.setup();
    const cred = makeCred({
      plane: 'tenant',
      tenantOwned: true,
      agentType: 'sdk',
      hasUnsavedInput: true,
      save: vi.fn(async () => false),
    });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(makeBehavior({ hasChanges: false }));

    render(<SettingsPage />);
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(cred.save).toHaveBeenCalledOnce());
    expect(mockedVerifyAuthStatus).not.toHaveBeenCalled();
  });

  it('cred.save() 가 true 를 돌려주면(sdk 유형) verifyAuthStatus 를 부른다', async () => {
    const user = userEvent.setup();
    const cred = makeCred({
      plane: 'tenant',
      tenantOwned: true,
      agentType: 'sdk',
      hasUnsavedInput: true,
      save: vi.fn(async () => true),
    });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(makeBehavior({ hasChanges: false }));

    render(<SettingsPage />);
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(mockedVerifyAuthStatus).toHaveBeenCalledOnce());
  });
});
