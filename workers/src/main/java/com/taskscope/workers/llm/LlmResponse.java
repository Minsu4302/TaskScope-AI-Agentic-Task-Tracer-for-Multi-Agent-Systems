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

    /** JSON 파싱 실패 시 fail-safe 응답 */
    public static LlmResponse parseError(String rawText) {
        return new LlmResponse("complete", "json_parse_error", rawText, null);
    }
}
