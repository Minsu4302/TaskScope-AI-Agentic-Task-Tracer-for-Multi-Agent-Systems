package com.taskscope.dispatcher.service;

import org.springframework.stereotype.Component;

@Component
public class ComplexityRouter {

    private static final int PREMIUM_THRESHOLD = 200;

    // diff 라인 수 기반 단순 휴리스틱 — 이후 과거 trace 집계 데이터로 보완 예정
    public String route(int diffLines) {
        return diffLines >= PREMIUM_THRESHOLD ? "premium" : "standard";
    }

    public String modelFor(String modelGrade) {
        return "premium".equals(modelGrade) ? "claude-opus-4-8" : "claude-haiku-4-5-20251001";
    }
}
