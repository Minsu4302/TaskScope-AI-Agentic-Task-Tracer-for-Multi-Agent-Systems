package com.taskscope.dispatcher.service;

import com.taskscope.dispatcher.config.RabbitMQConfig;
import com.taskscope.dispatcher.github.CommitSampler;
import com.taskscope.dispatcher.github.CommitSampler.SampledCommit;
import com.taskscope.dispatcher.github.GitHubClient;
import com.taskscope.dispatcher.github.GitHubCommit;
import com.taskscope.dispatcher.model.CommitTaskRequest;
import com.taskscope.dispatcher.model.CommitTaskResponse;
import com.taskscope.dispatcher.model.CommitTaskResponse.DispatchedCommit;
import com.taskscope.shared.SpanAttributes;
import com.taskscope.shared.TaskMessage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CommitTaskService {

    private static final Logger log = LoggerFactory.getLogger(CommitTaskService.class);

    private static final Map<String, String> TASK_TYPE_TO_QUEUE = Map.of(
            "code_review", RabbitMQConfig.QUEUE_CODE_REVIEW,
            "security",    RabbitMQConfig.QUEUE_SECURITY,
            "test_gen",    RabbitMQConfig.QUEUE_TEST_GEN
    );

    private final GitHubClient gitHubClient;
    private final CommitSampler commitSampler;
    private final ComplexityRouter complexityRouter;
    private final CostGuardrailService costGuardrailService;
    private final AmqpTemplate amqpTemplate;
    private final Tracer tracer;

    public CommitTaskService(GitHubClient gitHubClient, CommitSampler commitSampler,
                             ComplexityRouter complexityRouter, CostGuardrailService costGuardrailService,
                             AmqpTemplate amqpTemplate, Tracer tracer) {
        this.gitHubClient = gitHubClient;
        this.commitSampler = commitSampler;
        this.complexityRouter = complexityRouter;
        this.costGuardrailService = costGuardrailService;
        this.amqpTemplate = amqpTemplate;
        this.tracer = tracer;
    }

    public CommitTaskResponse dispatch(CommitTaskRequest request) {
        List<SampledCommit> candidates = fetchCandidates(request);
        log.info("Fetched {} commit candidates across {} repos", candidates.size(), request.repos().size());

        List<SampledCommit> sampled = commitSampler.sample(candidates, request.effectiveSampleSize());
        log.info("Sampled {} commits (SMALL={}, MEDIUM={}, LARGE={})",
                sampled.size(),
                countBySize(sampled, CommitSampler.Size.SMALL),
                countBySize(sampled, CommitSampler.Size.MEDIUM),
                countBySize(sampled, CommitSampler.Size.LARGE));

        return "commit_group".equals(request.effectiveTaskUnit())
                ? dispatchGroups(sampled, request)
                : dispatchSingle(sampled, request);
    }

    private List<SampledCommit> fetchCandidates(CommitTaskRequest request) {
        List<SampledCommit> candidates = new ArrayList<>();
        for (String repoFullName : request.repos()) {
            String[] parts = repoFullName.split("/", 2);
            String owner = parts[0], repo = parts[1];

            List<GitHubCommit> summaries;
            try {
                summaries = gitHubClient.listCommits(owner, repo, request.effectiveCommitsPerRepo());
            } catch (Exception e) {
                log.error("Failed to list commits for {}: {}", repoFullName, e.getMessage());
                continue;
            }

            for (GitHubCommit summary : summaries) {
                try {
                    GitHubCommit full = gitHubClient.getCommit(owner, repo, summary.sha());
                    if (full.diffLines() == 0) continue;  // 빈 커밋(merge commit 등) 제외
                    candidates.add(new SampledCommit(
                            repoFullName, full.sha(), firstLine(full.message()),
                            full.diffLines(), full.buildDiff(),
                            CommitSampler.sizeOf(full.diffLines())
                    ));
                } catch (Exception e) {
                    log.warn("Skipping commit {} in {}: {}", summary.sha(), repoFullName, e.getMessage());
                }
            }
        }
        return candidates;
    }

    private CommitTaskResponse dispatchSingle(List<SampledCommit> sampled, CommitTaskRequest request) {
        List<DispatchedCommit> dispatched = new ArrayList<>();
        List<String> taskTypes = request.effectiveTaskTypes();

        for (SampledCommit commit : sampled) {
            String taskId = UUID.randomUUID().toString();
            String grade = complexityRouter.route(commit.diffLines());

            Span taskSpan = tracer.spanBuilder("task.dispatch")
                    .setAttribute(SpanAttributes.TASK_ID, taskId)
                    .setAttribute(SpanAttributes.TASK_COMPLEXITY, grade)
                    .setAttribute(SpanAttributes.COMMIT_SHA, commit.sha())
                    .setAttribute(SpanAttributes.TASK_UNIT, "single_commit")
                    .startSpan();

            try (Scope ignored = taskSpan.makeCurrent()) {
                for (String taskType : taskTypes) {
                    String queue = TASK_TYPE_TO_QUEUE.get(taskType);
                    if (queue == null) continue;

                    CostGuardrailService.GuardrailResult guardrail =
                            costGuardrailService.apply(grade, taskType);
                    String effectiveGrade = guardrail.effectiveGrade();

                    taskSpan.setAttribute(SpanAttributes.TASK_TYPE, taskType);
                    taskSpan.setAttribute(SpanAttributes.LLM_MODEL, complexityRouter.modelFor(effectiveGrade));
                    if (guardrail.downgraded()) {
                        taskSpan.setAttribute(SpanAttributes.GUARDRAIL_ACTION, "model_downgrade");
                        taskSpan.setAttribute(SpanAttributes.GUARDRAIL_REASON, guardrail.reason());
                    }

                    amqpTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_NAME, queue, new TaskMessage(
                            taskId, taskType, commit.repoFullName(),
                            commit.sha(), commit.diff(), commit.diffLines(),
                            effectiveGrade, "single_commit"
                    ));
                }
            } finally {
                taskSpan.end();
            }

            dispatched.add(new DispatchedCommit(
                    taskId, commit.repoFullName(), commit.sha(), commit.message(),
                    commit.diffLines(), commit.size().name(),
                    complexityRouter.route(commit.diffLines()), taskTypes
            ));
        }

        return new CommitTaskResponse(dispatched, dispatched.size() * taskTypes.size(), sampled.size());
    }

    private CommitTaskResponse dispatchGroups(List<SampledCommit> sampled, CommitTaskRequest request) {
        List<DispatchedCommit> dispatched = new ArrayList<>();
        List<String> taskTypes = request.effectiveTaskTypes();
        int groupSize = request.effectiveCommitGroupSize();

        for (int i = 0; i < sampled.size(); i += groupSize) {
            List<SampledCommit> group = sampled.subList(i, Math.min(i + groupSize, sampled.size()));
            if (group.isEmpty()) continue;

            String taskId = UUID.randomUUID().toString();
            int totalLines = group.stream().mapToInt(SampledCommit::diffLines).sum();
            String grade = complexityRouter.route(totalLines);
            String combinedSha = group.stream().map(SampledCommit::sha).reduce((a, b) -> a + "," + b).orElse("");
            String combinedDiff = group.stream().map(SampledCommit::diff).reduce("", (a, b) -> a + "\n" + b);
            String primaryRepo = group.get(0).repoFullName();

            Span taskSpan = tracer.spanBuilder("task.dispatch")
                    .setAttribute(SpanAttributes.TASK_ID, taskId)
                    .setAttribute(SpanAttributes.TASK_COMPLEXITY, grade)
                    .setAttribute(SpanAttributes.COMMIT_SHA, combinedSha)
                    .setAttribute(SpanAttributes.TASK_UNIT, "commit_group")
                    .startSpan();

            try (Scope ignored = taskSpan.makeCurrent()) {
                for (String taskType : taskTypes) {
                    String queue = TASK_TYPE_TO_QUEUE.get(taskType);
                    if (queue == null) continue;

                    CostGuardrailService.GuardrailResult guardrail =
                            costGuardrailService.apply(grade, taskType);
                    String effectiveGrade = guardrail.effectiveGrade();

                    if (guardrail.downgraded()) {
                        taskSpan.setAttribute(SpanAttributes.GUARDRAIL_ACTION, "model_downgrade");
                        taskSpan.setAttribute(SpanAttributes.GUARDRAIL_REASON, guardrail.reason());
                    }

                    amqpTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_NAME, queue, new TaskMessage(
                            taskId, taskType, primaryRepo,
                            combinedSha, combinedDiff, totalLines,
                            effectiveGrade, "commit_group"
                    ));
                }
            } finally {
                taskSpan.end();
            }

            dispatched.add(new DispatchedCommit(
                    taskId, primaryRepo, combinedSha,
                    "Group of " + group.size() + " commits", totalLines,
                    CommitSampler.sizeOf(totalLines).name(), grade, taskTypes
            ));
        }

        return new CommitTaskResponse(dispatched, dispatched.size() * taskTypes.size(), sampled.size());
    }

    private static long countBySize(List<SampledCommit> list, CommitSampler.Size size) {
        return list.stream().filter(c -> c.size() == size).count();
    }

    private static String firstLine(String message) {
        if (message == null) return "";
        int nl = message.indexOf('\n');
        return nl > 0 ? message.substring(0, nl) : message;
    }
}
