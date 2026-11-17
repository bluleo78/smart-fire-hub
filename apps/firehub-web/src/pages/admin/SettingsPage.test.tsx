/**
 * `SettingsPage` 저장 오케스트레이션 단위 테스트.
 *
 * `AiCredentialFieldset.test.tsx` 는 자격증명 fieldset 자체(미설정 안내·비밀 힌트·모델 4상태)를
 * `cred` prop 주입으로 검증한다. 이 파일은 그 아래 <b>페이지가 직접 소유한</b> 두 결정을
 * 검증한다 — 어느 컴포넌트 테스트로도 닿지 않는 로직이다:
 *
 * 1. <b>저장 갈래</b>(WD-778). 자격증명과 모델을 함께 바꾸면 자격증명 PUT 하나에 `model` 을 실어 보내고
 *    `PUT /settings` 로는 모델을 보내지 않는다. 모델만 바꾸면 `PUT /settings`, 자격증명만 바꾸면 모델 없는
 *    자격증명 PUT 이다. 컴포넌트 트리 모양은 갈래와 무관하므로 <b>어느 API 가 어떤 인자로</b> 불렸는지만이
 *    이 결정을 드러낸다.
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
    savedHosting: 'EXTERNAL',
    secretInputs: {},
    setSecretInput: vi.fn(),
    secretFieldNames: ['apiKey'],
    models: null,
    loadModels: vi.fn(),
    modelsError: null,
    baseUrlError: null,
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
    commitSavedKey: vi.fn(),
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

describe('SettingsPage — 저장 갈래: 자격증명과 모델은 한 요청으로(WD-778)', () => {
  /** 저장 전 모델이 `claude-sonnet-5` 인 동작 설정 폼 — `payload` 가 이번 저장에서 바뀐 키다. */
  const behaviorChanging = (payload: Record<string, string>) =>
    makeBehavior({
      hasChanges: true,
      original: { 'ai.model': 'claude-sonnet-5' } as never,
      buildChangedPayload: vi.fn(() => ({ payload, droppedChangedKeys: [] })),
    });

  /**
   * <b>변종: 모델을 `PUT /settings` 로 따로 보낸다(수정 전 동작).</b> 그러면 어느 순서든 중간 상태가 옛 짝으로
   * 검사돼 막히거나 검사를 건너뛴다. 단언: 자격증명 저장이 `{ model }` 을 받고, `PUT /settings` 는 나가지 않는다.
   */
  it('자격증명과 모델을 함께 바꾸면 cred.save({ model }) 한 번만 보내고 PUT /settings 는 보내지 않는다', async () => {
    const user = userEvent.setup();
    const cred = makeCred({ agentType: 'opencode', hasUnsavedInput: true });
    const behavior = behaviorChanging({ 'ai.model': 'openai/gpt-4o' });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(behavior);

    render(<SettingsPage />);
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(cred.save).toHaveBeenCalledOnce());
    expect(cred.save).toHaveBeenCalledWith({ model: 'openai/gpt-4o' });
    expect(mockedUpdate).not.toHaveBeenCalled();
    // 모델은 서버에 저장됐다 — 그 키의 저장 기준값만 확정한다.
    expect(behavior.commitSavedKey).toHaveBeenCalledWith('ai.model', 'openai/gpt-4o');
    expect(vi.mocked(toast.success)).toHaveBeenCalledWith('설정이 저장되었습니다.');
  });

  /** 모델 말고 다른 동작 설정도 바꿨으면 묶음 저장 뒤 그 나머지 키만 `PUT /settings` 로 보낸다(모델은 싣지 않는다). */
  it('다른 동작 설정 키도 바꿨으면 묶음 저장 뒤 모델을 뺀 나머지만 PUT /settings 로 보낸다', async () => {
    const user = userEvent.setup();
    const order: string[] = [];
    const cred = makeCred({ agentType: 'opencode', hasUnsavedInput: true });
    cred.save = vi.fn(async () => {
      order.push('cred.save');
      return true;
    });
    mockedUpdate.mockImplementation(async () => {
      order.push('settingsApi.update');
      return {} as never;
    });
    const behavior = behaviorChanging({ 'ai.model': 'openai/gpt-4o', 'ai.max_turns': '20' });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(behavior);

    render(<SettingsPage />);
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(mockedUpdate).toHaveBeenCalledOnce());
    expect(order).toEqual(['cred.save', 'settingsApi.update']);
    expect(cred.save).toHaveBeenCalledWith({ model: 'openai/gpt-4o' });
    expect(mockedUpdate.mock.calls[0][0]).toEqual({ settings: { 'ai.max_turns': '20' } });
    await waitFor(() => expect(behavior.commitSaved).toHaveBeenCalledOnce());
  });

  /**
   * <b>변종: 묶음이 거부된 뒤에도 나머지 동작 설정을 보내거나, 모델을 되돌리는 보상 PUT 을 보낸다.</b> 서버는 한
   * 트랜잭션이라 아무것도 저장하지 않았으므로 되돌릴 것이 없다 — 더 보내지 않고 멈춘다.
   */
  it('묶음 저장이 거부되면 PUT /settings 도 보상 PUT 도 보내지 않고 성공을 알리지 않는다', async () => {
    const user = userEvent.setup();
    const cred = makeCred({ agentType: 'opencode', hasUnsavedInput: true, save: vi.fn(async () => false) });
    const behavior = behaviorChanging({ 'ai.model': 'openai/gpt-4o', 'ai.max_turns': '20' });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(behavior);

    render(<SettingsPage />);
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(cred.save).toHaveBeenCalledOnce());
    expect(mockedUpdate).not.toHaveBeenCalled();
    expect(behavior.commitSavedKey).not.toHaveBeenCalled();
    expect(behavior.commitSaved).not.toHaveBeenCalled();
    expect(mockedVerifyAuthStatus).not.toHaveBeenCalled();
    expect(vi.mocked(toast.success)).not.toHaveBeenCalled();
  });

  it('모델만 바꾸면(자격증명 변경 없음) PUT /settings 만 보낸다', async () => {
    const user = userEvent.setup();
    const cred = makeCred({ hasUnsavedInput: false });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(behaviorChanging({ 'ai.model': 'claude-opus-4' }));

    render(<SettingsPage />);
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(mockedUpdate).toHaveBeenCalledOnce());
    expect(mockedUpdate.mock.calls[0][0]).toEqual({ settings: { 'ai.model': 'claude-opus-4' } });
    expect(cred.save).not.toHaveBeenCalled();
  });

  it('자격증명과 모델 아닌 키를 바꾸면 PUT /settings 와 모델 없는 자격증명 저장을 따로 보낸다', async () => {
    const user = userEvent.setup();
    const cred = makeCred({ hasUnsavedInput: true });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(behaviorChanging({ 'ai.max_turns': '20' }));

    render(<SettingsPage />);
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(cred.save).toHaveBeenCalledOnce());
    expect(cred.save).toHaveBeenCalledWith();
    expect(mockedUpdate).toHaveBeenCalledOnce();
    expect(mockedUpdate.mock.calls[0][0]).toEqual({ settings: { 'ai.max_turns': '20' } });
  });

  it('동작 설정에 변경이 없으면 settingsApi.update 를 부르지 않고 자격증명만(모델 없이) 저장한다', async () => {
    const user = userEvent.setup();
    const cred = makeCred({ hasUnsavedInput: true });
    mockedUseAiCredentialForm.mockReturnValue(cred);
    mockedUseSettingsOverrideForm.mockReturnValue(makeBehavior({ hasChanges: false }));

    render(<SettingsPage />);
    await user.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(cred.save).toHaveBeenCalledOnce());
    expect(cred.save).toHaveBeenCalledWith();
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

/**
 * WD-59 — 최대 턴 수·Temperature·최대 응답 토큰은 공급자별로 실제 반영 여부가 다르다(AI_BEHAVIOR_SETTING_SUPPORT).
 * 반영되지 않는 칸은 비활성화하고 안내를 붙인다. 값 자체는 그대로 둔다(공급자를 바꿔도 보존).
 */
describe('SettingsPage — 공급자별로 반영되지 않는 실행 설정은 비활성화한다(WD-59)', () => {
  const UNSUPPORTED = '현재 공급자에서는 적용되지 않습니다';
  const row = (label: string) => screen.getByLabelText(label).closest('div.space-y-2') as HTMLElement;

  it('opencode 면 최대 턴 수·Temperature·최대 응답 토큰 모두 활성이고 안내가 없다', () => {
    mockedUseAiCredentialForm.mockReturnValue(makeCred({ agentType: 'opencode' }));
    mockedUseSettingsOverrideForm.mockReturnValue(makeBehavior());

    render(<SettingsPage />);

    for (const label of ['최대 턴 수', 'Temperature', '최대 응답 토큰']) {
      expect(screen.getByLabelText(label)).toBeEnabled();
      expect(within(row(label)).queryByText(UNSUPPORTED)).not.toBeInTheDocument();
    }
  });

  it.each(['sdk', 'cli', 'cli-api'] as const)(
    '%s 면 최대 턴 수만 활성이고 Temperature·최대 응답 토큰은 비활성 + 안내(값은 유지)',
    (agentType) => {
      mockedUseAiCredentialForm.mockReturnValue(makeCred({ agentType }));
      mockedUseSettingsOverrideForm.mockReturnValue(makeBehavior());

      render(<SettingsPage />);

      expect(screen.getByLabelText('최대 턴 수')).toBeEnabled();
      expect(within(row('최대 턴 수')).queryByText(UNSUPPORTED)).not.toBeInTheDocument();
      for (const label of ['Temperature', '최대 응답 토큰']) {
        expect(screen.getByLabelText(label)).toBeDisabled();
        expect(within(row(label)).getByText(UNSUPPORTED)).toBeInTheDocument();
      }
      // 저장된 값은 비활성 상태에서도 그대로 보인다 — 공급자를 다시 바꾸면 그 값이 쓰인다.
      expect(screen.getByLabelText('Temperature')).toHaveValue(0.5);
      expect(screen.getByLabelText('최대 응답 토큰')).toHaveValue(4096);
    },
  );

  // 리뷰 반영: 다른 유형에서 남은 잘못된 값이 잠긴 칸에 있으면 저장이 막히고 고칠 수 없다 — 오류가 붙은 칸은 열어 둔다.
  it('반영 안 되는 칸이라도 검증 오류가 붙어 있으면 편집할 수 있게 열어 둔다', () => {
    mockedUseAiCredentialForm.mockReturnValue(makeCred({ agentType: 'sdk' }));
    mockedUseSettingsOverrideForm.mockReturnValue(
      makeBehavior({ errors: { 'ai.temperature': '0.0~1.0 사이의 값을 입력하세요' } } as never),
    );

    render(<SettingsPage />);

    expect(screen.getByLabelText('Temperature')).toBeEnabled();
    expect(screen.getByLabelText('최대 응답 토큰')).toBeDisabled();
  });

  // 자격증명 조회 실패·403 이면 실제 유형을 모른다(agentType 은 초기값 sdk) — 잠그거나 "적용되지 않습니다"를 띄우지 않는다.
  it.each([{ loadFailed: true }, { isLocked: true }])('유형을 모르면(%o) 잠그지 않는다', (state) => {
    mockedUseAiCredentialForm.mockReturnValue(makeCred({ agentType: 'sdk', ...state }));
    mockedUseSettingsOverrideForm.mockReturnValue(makeBehavior());

    render(<SettingsPage />);

    for (const label of ['Temperature', '최대 응답 토큰']) {
      expect(screen.getByLabelText(label)).toBeEnabled();
      expect(within(row(label)).queryByText(UNSUPPORTED)).not.toBeInTheDocument();
    }
  });
});

describe('SettingsPage — 시스템 프롬프트 길이 상한(WD-536)', () => {
  /** 시스템 프롬프트만 바꾼 동작 설정 반환값. 나머지 5키는 유효하므로 검증 결과는 프롬프트 길이에만 달린다. */
  const behaviorWithPrompt = (prompt: string) => {
    const base = makeBehavior();
    return makeBehavior({
      hasChanges: true,
      form: { ...base.form, 'ai.system_prompt': prompt },
      buildChangedPayload: vi.fn(() => ({ payload: { 'ai.system_prompt': prompt }, droppedChangedKeys: [] })),
    });
  };

  it('20,001자면 그 칸에 상한 오류를 붙이고 저장 요청을 보내지 않는다', async () => {
    const user = userEvent.setup();
    const cred = makeCred();
    mockedUseAiCredentialForm.mockReturnValue(cred);
    const behavior = behaviorWithPrompt('a'.repeat(20_001));
    mockedUseSettingsOverrideForm.mockReturnValue(behavior);

    render(<SettingsPage />);
    // 상한을 넘은 상태가 저장 전에도 글자 수로 드러난다
    expect(screen.getByTestId('ai-system-prompt-count')).toHaveTextContent('20,001 / 20,000자');

    await user.click(screen.getByRole('button', { name: '저장' }));

    expect(behavior.setErrors).toHaveBeenCalledWith({
      'ai.system_prompt': '시스템 프롬프트는 20,000자 이하로 입력하세요',
    });
    // WD-643: 여기서는 setErrors 가 모킹이라 오류가 화면에 그려지지 않아 포커스할 오류 칸이 없다 — 그때만 토스트로
    // 알리는 대체 경로다(실제 화면에서 오류 칸으로 포커스가 가면 토스트는 없다, settings.spec.ts 가 지킨다).
    expect(toast.error).toHaveBeenCalledWith('입력값을 확인하세요.');
    expect(mockedUpdate).not.toHaveBeenCalled();
    expect(cred.save).not.toHaveBeenCalled();
  });

  it('정확히 20,000자는 검증을 통과해 저장 요청을 보낸다', async () => {
    const user = userEvent.setup();
    mockedUseAiCredentialForm.mockReturnValue(makeCred());
    const prompt = 'a'.repeat(20_000);
    const behavior = behaviorWithPrompt(prompt);
    mockedUseSettingsOverrideForm.mockReturnValue(behavior);

    render(<SettingsPage />);
    await user.click(screen.getByRole('button', { name: '저장' }));

    expect(behavior.setErrors).toHaveBeenCalledWith({});
    await waitFor(() => expect(mockedUpdate).toHaveBeenCalledOnce());
    expect(mockedUpdate.mock.calls[0][0]).toEqual({ settings: { 'ai.system_prompt': prompt } });
  });
});
