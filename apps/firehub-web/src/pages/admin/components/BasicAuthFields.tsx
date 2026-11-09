import { FormField } from '@/components/ui/form-field';
import { Input } from '@/components/ui/input';

interface BasicAuthFieldsProps {
  /** 아이디·비밀번호 입력의 id — 화면이 첫 오류 칸 포커스(focusFirstError)에 같은 id 를 쓴다. */
  usernameId: string;
  passwordId: string;
  username: string;
  password: string;
  usernameError?: string;
  passwordError?: string;
  onUsernameChange: (value: string) => void;
  onPasswordChange: (value: string) => void;
  /** 비밀번호 칸 안내 문구 — 상세 편집은 「새 비밀번호」 로 바꿔 보인다. */
  passwordPlaceholder?: string;
  /**
   * 저장된 비밀번호를 유지할 수 있을 때의 안내 문구 id. 주면 비밀번호 칸이 필수가 아니게 되고,
   * 「비워 두면 저장된 비밀번호를 그대로 사용합니다.」 안내를 그려 입력의 aria-describedby 로 잇는다.
   */
  keepPasswordHintId?: string;
}

/**
 * Basic 인증 아이디·비밀번호 입력 칸 묶음(WD-681).
 *
 * API 연결 만들기 대화상자(ApiConnectionListPage)와 상세 인증 편집(ApiConnectionDetailPage)이 같은 마크업을 따로 복사해 두면
 * 라벨·placeholder·autocomplete 가 한쪽만 바뀌어 두 화면이 갈리므로 한 곳에 둔다. 값·오류 상태와 오류 지우기는 화면마다
 * 다르게 들고 있어(만들기는 clearError, 상세는 인증 오류 묶음) 콜백으로 받는다.
 * 아이디는 비밀이 아니라 평문 입력, 비밀번호는 다른 비밀처럼 가린 입력이다(브라우저 자동 채움을 막으려 new-password).
 */
export function BasicAuthFields({
  usernameId,
  passwordId,
  username,
  password,
  usernameError,
  passwordError,
  onUsernameChange,
  onPasswordChange,
  passwordPlaceholder = '비밀번호를 입력하세요',
  keepPasswordHintId,
}: BasicAuthFieldsProps) {
  return (
    <>
      <FormField label="아이디" htmlFor={usernameId} error={usernameError} required>
        <Input
          id={usernameId}
          autoComplete="off"
          placeholder="아이디를 입력하세요"
          value={username}
          onChange={(e) => onUsernameChange(e.target.value)}
        />
      </FormField>
      <FormField label="비밀번호" htmlFor={passwordId} error={passwordError} required={!keepPasswordHintId}>
        <Input
          id={passwordId}
          type="password"
          autoComplete="new-password"
          placeholder={passwordPlaceholder}
          aria-describedby={keepPasswordHintId}
          value={password}
          onChange={(e) => onPasswordChange(e.target.value)}
        />
        {keepPasswordHintId && (
          <p id={keepPasswordHintId} className="text-xs text-muted-foreground">
            비워 두면 저장된 비밀번호를 그대로 사용합니다.
          </p>
        )}
      </FormField>
    </>
  );
}
