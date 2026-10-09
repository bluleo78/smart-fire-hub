/**
 * `SettingsPage` 저장 오케스트레이션 단위 테스트.
 *
 * `AiCredentialFieldset.test.tsx` 는 자격증명 fieldset 자체(미설정 안내·비밀 힌트·모델 4상태)를
 * `cred` prop 주입으로 검증한다. 이 파일은 그 아래 <b>페이지가 직접 소유한</b> 두 결정을
 * 검증한다 — 어느 컴포넌트 테스트로도 닿지 않는 로직이다:
 *
 * 1. <b>저장 순서</b>(동작 설정 6키 → 자격증명). `performSave()` 안의 두 `if` 블록 순서가
 *    바뀌어도(자문이 먼저 제안했다가 `AiCredentialController.validateOpencode` 를 직접 읽고
 *    뒤집은 그 순서) 컴포넌트 트리 모양은 전혀 달라지지 않는다 — 오직 두 API 호출이 일어나는
 *    <b>순서</b>만 문제가 된다.
 * 2. <b>확인 다이얼로그 게이팅</b>. `handleSaveClick` 이 `cred.typeChanged` 면
 *    다이얼로그를 열고 `performSave` 를 <b>보류</b>해야 한다 — `AiCredentialFieldset.test.tsx`
 *    는 `typeChangeConfirmDescription` 자체(순수 함수)만 봤을 뿐,
 *    "저장" 버튼을 눌렀을 때 실제로 다이얼로그가 막아서는지는 이 파일에서만 검증된다.
 *
 * `useAiCredentialForm`/`useSettingsOverrideForm`/`useSmtpSettingsForm`/
 * `useUnsavedChangesGuard` 를 전부 모킹한다 — 네트워크·Radix Select 팝오버 상호작용 없이
 * 오케스트레이션 로직만 격리해서 본다(이 코드베이스에 `SettingsPage` 전체를 렌더하는 기존
 * 선례가 없어, 모킹 폭을 최소로 유지했다). 이메일/임베딩 탭 폼은 `useSmtpSettingsForm`/
 * `useEmbeddingSettingsForm` 을 최소 계약(`hasChanges`)으로만 모킹한다 — 두 훅 모두
 * `defaultValue="ai"` 여부와 무관하게 <b>페이지 레벨에서 항상 호출</b>되므로(탭 전환에 편집이
 * 죽지 않도록 이슈 #86 재발 방지, dbdaf75a), 모킹하지 않으면 실제 API·react-query 훅이 돌아
 * `QueryClientProvider` 없이 렌더가 죽는다.
 */
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { toast } from 'sonner';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { settingsApi } from '../../api/settings';
import type { UseAiClassifyFormResult } from '../../hooks/useAiClassifyForm';
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

// AI 분류 탭(#707)은 이 파일의 관심사가 아니다 — 실제 훅이 `aiClassifyCredentialApi` 를 부르지
// 않도록(위 `api/settings` mock 에는 그 객체가 없다) 최소 계약만 흉내 낸다.
vi.mock('../../hooks/useAiClassifyForm', () => ({
  // `cred` 까지 갖춘 완전한 계약을 돌려준다 — 빠뜨리면 이 파일에서 "AI 분류" 탭을 여는 테스트가
  // 렌더 중 undefined 접근으로 죽는다(mock 은 훅 호출 시점에 평가되므로 makeCred 를 써도 된다).
  useAiClassifyForm: (): UseAiClassifyFormResult => ({
    cred: makeCred({ configured: false }),
    activated: false,
    activate: vi.fn(),
    editing: false,
    startEditing: vi.fn(),
    cancelEditing: vi.fn(),
    revert: vi.fn(),
    model: '',
    setModel: vi.fn(),
    modelError: null,
    hasUnsavedInput: false,
    isSaving: false,
    isClearing: false,
    save: vi.fn(async () => true),
    clear: vi.fn(async () => {}),
  }),
}));

vi.mock('../../hooks/useSettingsOverrideForm', () => ({
  useSettingsOverrideForm: vi.fn(),
}));

vi.mock('../../hooks/useSmtpSettingsForm', () => ({
  useSmtpSettingsForm: () => ({ hasChanges: false }),
}));

// 임베딩 탭도 SMTP 탭과 같은 이유로 페이지가 소유한다(#713 리뷰 fix round 1, dbdaf75a) —
// react-query 의 useEmbeddingConfig 를 실제로 부르면 QueryClientProvider 가 없어 렌더가 죽는다.
vi.mock('../../hooks/useEmbeddingSettingsForm', () => ({
  useEmbeddingSettingsForm: () => ({ hasChanges: false }),
}));

vi.mock('../../hooks/useUnsavedChangesGuard', () => ({
  useDirtyAggregator: vi.fn(() => ({ isAnyDirty: false, makeReporter: () => vi.fn() })),
  useUnsavedChangesGuard: vi.fn(() => ({ dialog: null })),
}));

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}));

// 설정 라우트 진입점이 ADMIN 여부로 전체 설정/보안 전용 화면을 가른다(데이터셋 보안 등급 S1).
// 이 파일은 ADMIN 전체 설정의 저장 오케스트레이션을 보므로 ADMIN 으로 고정하고,
// 탭 노출용 권한 조회(react-query)는 QueryClientProvider 없이 돌도록 빈 권한으로 모킹한다.
vi.mock('../../hooks/useAuth', () => ({
  useAuth: () => ({ isAdmin: true }),
}));

vi.mock('../../hooks/queries/useMyPermissions', () => ({
  useMyPermissions: () => ({ permissions: new Set<string>(), isLoading: false }),
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
    configured: true,
    agentType: 'sdk',
    setAgentType: vi.fn(),
    payload: {},
    setPayloadField: vi.fn(),
    hostingDemoted: false,
    canKeepSavedSelfHosted: false,
    secretInputs: {},
    setSecretInput: vi.fn(),
    secretFieldNames: ['apiKey'],
    models: null,
    loadModels: vi.fn(),
    modelsError: null,
    canLoadModels: false,
    isLoadingModels: false,
    hasUnsavedInput: false,
    save: vi.fn(async () => true),
    staleNotice: null,
    reset: vi.fn(),
    reload: vi.fn(async () => {}),
    savedAgentType: 'sdk',
    typeChanged: false,
    ...overrides,
  };
}

/** 동작 설정 6키가 전부 유효한(NUMBER_RULES·system_prompt 통과) `useSettingsOverrideForm` 반환값. */
function makeBehavior(overrides: Partial<ReturnType<typeof useSettingsOverrideForm>> = {}) {
  const base = {
    isLoading: false,
    loadFailed: false,
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
    errors: {},
    setErrors: vi.fn(),
    hasChanges: false,
    updateField: vi.fn(),
    handleReset: vi.fn(),
    buildChangedPayload: vi.fn(() => ({ payload: {}, droppedChangedKeys: [] })),
    commitSaved: vi.fn(),
    restoreOriginal: vi.fn(),
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
  it('파괴적 전환이 없으면 다이얼로그 없이 곧장 저장한다', async () => {
    const user = userEvent.setup();
    const cred = makeCred({ hasUnsavedInput: true });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(makeBehavior({ hasChanges: false }));

    render(<SettingsPage />);

    await user.click(screen.getByRole('button', { name: '저장' }));

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
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

    const cred = makeCred({ hasUnsavedInput: true });
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
    const cred = makeCred({ hasUnsavedInput: true });
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
   * <b>변종: `handleSaveClick` 이 `cred.typeChanged` 를 무시하고 곧장 `performSave()` 를 부른다.</b>
   * 유형이 바뀌었을 때 다이얼로그 없이 `cred.save()` 가 불리면 이 테스트가 잡는다.
   */
  it('유형이 바뀌면 다이얼로그를 먼저 열고, 확인 전에는 save 를 부르지 않는다', async () => {
    const user = userEvent.setup();
    const dirty = makeCred({
      agentType: 'sdk',
      savedAgentType: 'opencode',
      typeChanged: true,
      hasUnsavedInput: true,
    });
    mockedUseAiCredentialForm.mockReturnValue(dirty);
    mockedUseSettingsOverrideForm.mockReturnValue(makeBehavior({ hasChanges: false }));

    render(<SettingsPage />);

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
    const cred = makeCred({ hasUnsavedInput: true });
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
   * 로 되돌리는 것과 동치). 동작 설정은 안 건드리고 자격증명 입력만 건드린 상태에서
   * 버튼이 비활성이면, 사용자가 되돌릴 방법이 아예 없다.
   */
  it('동작 설정은 안 바뀌고 자격증명만 dirty 여도 되돌리기 버튼이 활성이다', () => {
    const cred = makeCred({ hasUnsavedInput: true });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(makeBehavior({ hasChanges: false }));

    render(<SettingsPage />);
    expect(screen.getByRole('button', { name: '되돌리기' })).toBeEnabled();
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

describe('SettingsPage — 자격증명 저장이 거부되면 함께 저장한 모델을 되돌린다(#720)', () => {
  /** 모델을 `claude-sonnet-5` → `openai/gpt-4o` 로 바꾼 동작 설정 폼(저장 전 값은 `original`). */
  const behaviorWithModelChange = (payload: Record<string, string>) =>
    makeBehavior({
      hasChanges: true,
      original: { 'ai.model': 'claude-sonnet-5' } as never,
      buildChangedPayload: vi.fn(() => ({ payload, droppedChangedKeys: [] })),
    });

  /**
   * <b>변종: `performSave` 가 `cred.save()` 실패 뒤 보상 PUT 을 보내지 않는다(수정 전 동작).</b>
   * 그러면 서버에는 새 유형 형식의 모델(`openai/gpt-4o`)과 옛 유형(Claude) 자격증명이 함께 남아
   * AI 채팅이 즉시 깨진다. 단언은 "두 번째 PUT 의 본문이 저장 전 모델인가"다 — 토스트만 보면
   * 서버 상태가 복구됐는지 알 수 없다.
   */
  it('cred.save() 가 false 면 ai.model 만 저장 전 값으로 되돌리는 PUT 을 보내고 original 을 되돌린다', async () => {
    const user = userEvent.setup();
    const cred = makeCred({ agentType: 'opencode', hasUnsavedInput: true, save: vi.fn(async () => false) });
    const behavior = behaviorWithModelChange({ 'ai.model': 'openai/gpt-4o', 'ai.max_turns': '20' });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(behavior);

    render(<SettingsPage />);
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(mockedUpdate).toHaveBeenCalledTimes(2));
    expect(mockedUpdate.mock.calls[0][0]).toEqual({
      settings: { 'ai.model': 'openai/gpt-4o', 'ai.max_turns': '20' },
    });
    // 보상은 모델 한 키만 — 함께 저장한 다른 키(max_turns)는 독립 편집이라 되돌리지 않는다.
    expect(mockedUpdate.mock.calls[1][0]).toEqual({ settings: { 'ai.model': 'claude-sonnet-5' } });
    await waitFor(() =>
      expect(behavior.restoreOriginal).toHaveBeenCalledWith('ai.model', 'claude-sonnet-5'),
    );
    // 곧 되돌린 저장을 "저장되었습니다" 로 알리지 않는다.
    expect(vi.mocked(toast.success)).not.toHaveBeenCalledWith('설정이 저장되었습니다.');
    expect(vi.mocked(toast.success)).toHaveBeenCalledWith('모델을 제외한 설정이 저장되었습니다.');
  });

  it('cred.save() 가 true 면 보상 PUT 없이 저장 성공을 알린다', async () => {
    const user = userEvent.setup();
    const cred = makeCred({ agentType: 'opencode', hasUnsavedInput: true, save: vi.fn(async () => true) });
    const behavior = behaviorWithModelChange({ 'ai.model': 'openai/gpt-4o' });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(behavior);

    render(<SettingsPage />);
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() =>
      expect(vi.mocked(toast.success)).toHaveBeenCalledWith('설정이 저장되었습니다.'),
    );
    expect(mockedUpdate).toHaveBeenCalledOnce();
    expect(behavior.restoreOriginal).not.toHaveBeenCalled();
  });

  it('이번 저장에 ai.model 이 없으면 자격증명이 거부돼도 보상 PUT 을 보내지 않는다', async () => {
    const user = userEvent.setup();
    const cred = makeCred({ hasUnsavedInput: true, save: vi.fn(async () => false) });
    const behavior = behaviorWithModelChange({ 'ai.max_turns': '20' });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(behavior);

    render(<SettingsPage />);
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(cred.save).toHaveBeenCalledOnce());
    expect(mockedUpdate).toHaveBeenCalledOnce();
    expect(behavior.restoreOriginal).not.toHaveBeenCalled();
    expect(vi.mocked(toast.success)).toHaveBeenCalledWith('설정이 저장되었습니다.');
  });

  /**
   * <b>변종: 보상 PUT 실패를 삼킨다.</b> 되돌리지 못했으면 서버에는 어긋난 모델이 남는다 — 토스트가
   * 사라진 뒤에도 그 사실이 화면에 남아야 하고, `original` 을 이전 값으로 돌려서는 안 된다(서버
   * 값은 새 모델이므로, 돌리면 dirty 판정이 서버와 어긋난다).
   */
  it('보상 PUT 이 실패하면 지속 배너로 어긋난 모델을 알리고 original 은 건드리지 않는다', async () => {
    const user = userEvent.setup();
    const cred = makeCred({ agentType: 'opencode', hasUnsavedInput: true, save: vi.fn(async () => false) });
    const behavior = behaviorWithModelChange({ 'ai.model': 'openai/gpt-4o' });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(behavior);
    mockedUpdate.mockResolvedValueOnce({} as never).mockRejectedValueOnce(new Error('network'));

    render(<SettingsPage />);
    await user.click(screen.getByRole('button', { name: '저장' }));

    expect(await screen.findByText(/모델 변경을 되돌리지 못했습니다.*openai\/gpt-4o/)).toBeInTheDocument();
    expect(behavior.restoreOriginal).not.toHaveBeenCalled();
  });
});

describe('SettingsPage — Claude 유형 모델 Select 는 목록 밖 저장값을 보존 표시한다(#720)', () => {
  /**
   * <b>변종: Select 옵션에서 `withPreservedValue` 를 뺀다(수정 전 동작).</b> 저장된 모델이
   * `openai/gpt-4o` 처럼 Claude 목록 밖이면 Select 가 빈 칸이 되어 무엇이 저장됐는지 알 수 없다.
   */
  it('저장된 모델이 목록 밖이면 그 값을 그대로 보여 주고 다시 선택하라고 안내한다', () => {
    mockedUseAiCredentialForm.mockReturnValue(makeCred({ agentType: 'sdk' }));
    const base = makeBehavior();
    mockedUseSettingsOverrideForm.mockReturnValue(
      makeBehavior({ form: { ...base.form, 'ai.model': 'openai/gpt-4o' } as never }),
    );

    render(<SettingsPage />);

    expect(screen.getByRole('combobox', { name: '모델' })).toHaveTextContent('openai/gpt-4o');
    expect(screen.getByText(/Claude 모델 목록에 없는 값입니다/)).toBeInTheDocument();
  });

  it('목록 안의 모델이면 안내를 띄우지 않는다', () => {
    mockedUseAiCredentialForm.mockReturnValue(makeCred({ agentType: 'sdk' }));
    mockedUseSettingsOverrideForm.mockReturnValue(makeBehavior());

    render(<SettingsPage />);

    expect(screen.queryByText(/Claude 모델 목록에 없는 값입니다/)).not.toBeInTheDocument();
  });
});

describe('SettingsPage — AI 동작 설정은 테넌트 전용 평면 설정으로 그린다', () => {
  /** 서버 응답 1건(새 계약: description·updatedAt 은 null, tenantEditable 은 항상 true). */
  const resolved = (key: string, value: string, overridden: boolean) => ({
    key,
    value,
    description: null,
    updatedAt: null,
    overridden,
    tenantEditable: true,
  });

  /**
   * <b>변종: 옛 상태 배지·재정의 해제 버튼을 되살린다.</b> AI 설정은 플랫폼 값을 물려받지 않으므로
   * "플랫폼 값 사용 중"/"우리 조직 값 적용 중"/"재정의 해제" 는 존재하지 않는 개념을 약속한다.
   * 저장 안 한 필드에만 "기본값" 힌트가 붙어야 하며, 저장된 필드에는 붙지 않아야 한다.
   */
  it('저장 안 한 필드에만 "기본값" 힌트가 붙고, 플랫폼/재정의 문구·해제 버튼은 없다', () => {
    mockedUseAiCredentialForm.mockReturnValue(makeCred());
    mockedUseSettingsOverrideForm.mockReturnValue(
      makeBehavior({
        settings: {
          'ai.model': resolved('ai.model', 'claude-sonnet-5', false),
          'ai.max_turns': resolved('ai.max_turns', '25', true),
          'ai.system_prompt': resolved('ai.system_prompt', '너는 유능한 어시스턴트다.', false),
          'ai.temperature': resolved('ai.temperature', '0.5', true),
          'ai.max_tokens': resolved('ai.max_tokens', '4096', true),
          'ai.session_max_tokens': resolved('ai.session_max_tokens', '50000', true),
        },
      }),
    );

    render(<SettingsPage />);

    // 저장 안 한 키는 ai.model·ai.system_prompt 둘 — 힌트도 정확히 둘이어야 한다.
    expect(screen.getAllByText('기본값')).toHaveLength(2);
    const maxTurnsRow = screen.getByLabelText('최대 턴 수').closest('div.space-y-2') as HTMLElement;
    expect(within(maxTurnsRow).queryByText('기본값')).not.toBeInTheDocument();
    const modelRow = screen.getByText('모델', { selector: 'label' }).closest('div.space-y-2') as HTMLElement;
    expect(within(modelRow).getByText('기본값')).toBeInTheDocument();

    expect(screen.queryByText(/재정의|플랫폼|오버라이드|상속/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /재정의 해제/ })).not.toBeInTheDocument();
    // 잠금 개념도 없다 — 모든 입력이 편집 가능하다.
    expect(screen.getByLabelText('최대 턴 수')).toBeEnabled();
    expect(screen.getByLabelText('세션 최대 토큰')).toBeEnabled();
  });
});
