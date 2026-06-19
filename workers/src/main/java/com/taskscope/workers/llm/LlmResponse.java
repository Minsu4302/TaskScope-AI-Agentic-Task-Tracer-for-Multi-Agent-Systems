package com.taskscope.workers.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Claude가 반환하는 JSON 구조화 응답.
 * {"status": "complete|need_context|retry", "reasoning": "...", "result": "...",
 *  "context_request": {"files": ["path/to/file", ...]}}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmResponse(
        String status,
        String reasoning,
        String result,
        @JsonProperty("context_request") ContextRequest contextRequest
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContextRequest(List<String> files) {}

    public boolean isComplete()     { return "complete".equals(status); }
    public boolean isNeedContext()  { return "need_context".equals(status); }
    public boolean isRetry()        { return "retry".equals(status); }

    public List<String> requestedFiles() {
        return (contextRequest != null && contextRequest.files() != null)
                ? contextRequest.files()
                : List.of();
    }

    /** status 필드 누락 시 retry 응답 — complete 오처리 방지 */
    public static LlmResponse missingStatus() {
        return new LlmResponse("retry", "missing_status_field", "", null);
    }
}
