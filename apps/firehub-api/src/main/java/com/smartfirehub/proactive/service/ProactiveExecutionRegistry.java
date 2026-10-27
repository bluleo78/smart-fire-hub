package com.smartfirehub.proactive.service;

import com.smartfirehub.global.execution.ExecutionRegistry;
import org.springframework.stereotype.Component;

/** 이 JVM이 쥔 프로액티브 실행만 보관한다. 파이프라인 등록부와 상태를 공유하면 ID 충돌로 정리 판정이 틀어진다. */
@Component
public class ProactiveExecutionRegistry extends ExecutionRegistry {}
