/**
 * 가입 요청은 성공했지만 그 뒤 세션을 여는 단계(me 확정)가 실패했음을 알리는 오류(WD-749).
 *
 * 왜 구분하는가: 가입 요청 자체의 실패와 섞이면 화면이 "계정을 만들지 못했습니다" 라고 말하게 되는데, 이 경우 계정은
 * 이미 만들어졌고 사용자는 로그인만 하면 된다. 원인 오류는 cause 에 보관한다.
 */
export class SignupSessionError extends Error {
  constructor(cause: unknown) {
    super('계정은 만들어졌지만 세션을 여는 데 실패했습니다.', { cause });
    this.name = 'SignupSessionError';
  }
}
