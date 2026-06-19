package com.taskscope.shared;

public final class SpanAttributes {

    private SpanAttributes() {}

    // task 식별
    public static final String TASK_ID = "task.id";
    public static final String TASK_TYPE = "task.type";
    public static final String TASK_COMPLEXITY = "task.complexity";

    // LLM 호출 계측
    public static final String LLM_MODEL = "llm.model";
    public static final String LLM_INPUT_TOKENS = "llm.input_tokens";
    public static final String LLM_OUTPUT_TOKENS = "llm.output_tokens";
    public static final String LLM_COST_USD = "llm.cost_usd";

    // 에이전트 루프
    public static final String AGENT_LOOP_ITERATION = "agent.loop_iteration";
    public static final String AGENT_LOOP_CAP_HIT = "agent.loop_cap_hit";
    public static final String AGENT_LOOP_REASON = "agent.loop_reason";  // "complete" | "need_context" | "retry"

    // 커밋 기반 task
    public static final String COMMIT_SHA = "commit.sha";
    public static final String TASK_UNIT = "task.unit";  // "single_commit" | "commit_group"

    // trace propagation 이상 감지
    public static final String TRACE_PROPAGATION_BROKEN = "trace.propagation_broken";

    // 가드레일
    public static final String GUARDRAIL_ACTION = "guardrail.action";
    public static final String GUARDRAIL_REASON = "guardrail.reason";
}
