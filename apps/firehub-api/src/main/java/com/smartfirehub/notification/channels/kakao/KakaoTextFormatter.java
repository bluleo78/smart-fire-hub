package com.smartfirehub.notification.channels.kakao;

import com.smartfirehub.notification.Payload;
import org.springframework.stereotype.Component;

/**
 * 카카오 나에게 보내기용 텍스트 포맷터.
 *
 * <p>StandardPayload(title + summary + sections)를 1000자 제한에 맞춰 텍스트로 렌더한다.
 * 초과 시 절단 후 "…"을 붙이고, 끝에 안내 문구(FOOTER)를 항상 append한다.
 */
@Component
public class KakaoTextFormatter {

    /** 카카오 text 템플릿 최대 허용 길이 (1000자). 안내문구 여유분을 빼고 본문 한도 설정. */
    private static final int MAX_LEN = 990;

    /** 메시지 끝에 항상 붙는 안내 문구. 사용자가 답장 경로를 알 수 있도록 안내. */
    private static final String FOOTER = "\n\n답장은 Smart Fire Hub 웹/Slack에서";

    /**
     * Payload를 카카오 전송용 텍스트로 렌더링.
     *
     * <p>구성 순서: title → summary → sections(heading + bodyMd) 순으로 이어붙인다.
     * MAX_LEN 초과 시 절단 + "…" 처리 후 FOOTER를 append한다.
     *
     * @param payload 발송 페이로드
     * @return 카카오 text 템플릿에 넣을 완성 문자열
     */
    public String render(Payload payload) {
        StringBuilder sb = new StringBuilder();

        // title
        if (payload.title() != null && !payload.title().isBlank()) {
            sb.append(payload.title());
        }

        // summary
        if (payload.summary() != null && !payload.summary().isBlank()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(payload.summary());
        }

        // sections: 각 section의 heading + bodyMd
        if (payload.sections() != null) {
            for (Payload.Section section : payload.sections()) {
                if (sb.length() > 0) sb.append("\n");
                if (section.heading() != null && !section.heading().isBlank()) {
                    sb.append(section.heading());
                }
                if (section.bodyMd() != null && !section.bodyMd().isBlank()) {
                    if (section.heading() != null && !section.heading().isBlank()) {
                        sb.append("\n");
                    }
                    sb.append(section.bodyMd());
                }
            }
        }

        String body = sb.toString();

        // MAX_LEN 초과 시 절단 + 말줄임표
        if (body.length() > MAX_LEN) {
            body = body.substring(0, MAX_LEN) + "…";
        }

        return body + FOOTER;
    }
}
