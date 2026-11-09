package com.smartfirehub.pipeline.service.executor;

/**
 * 메시지를 사용자에게 그대로 보여도 되는 예외에 붙이는 표지(WD-693).
 *
 * <p><b>왜.</b> {@link ApiCallErrorMessages#describe} 는 이미 사용자 문구를 담은 예외(이 패키지의 {@link
 * ApiCallException}·{@link SsrfException}·{@link RetryableApiCallException})는 원문을 그대로 쓰고, 그 밖의 예외는
 * 분류해 문구를 새로 만든다(URL·쿼리 키 가리기 포함). 예전에는 그 목록을 타입마다 나열해, 새 예외를 만들 때 목록에 넣는 것을 잊기 쉬웠다. 이 표지 하나만 보게 한다
 * — 붙이는 쪽이 「메시지에 요청 URL·비밀이 없다」를 보장한다.
 */
public interface SafeUserMessage {}
