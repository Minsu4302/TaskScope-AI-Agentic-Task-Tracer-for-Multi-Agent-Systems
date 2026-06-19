package com.taskscope.dispatcher.controller;

import com.taskscope.dispatcher.model.CommitTaskRequest;
import com.taskscope.dispatcher.model.CommitTaskResponse;
import com.taskscope.dispatcher.model.TaskRequest;
import com.taskscope.dispatcher.model.TaskResponse;
import com.taskscope.dispatcher.service.CommitTaskService;
import com.taskscope.dispatcher.service.TaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/tasks")
public class TaskController {

    private final TaskService taskService;
    private final CommitTaskService commitTaskService;

    public TaskController(TaskService taskService, CommitTaskService commitTaskService) {
        this.taskService = taskService;
        this.commitTaskService = commitTaskService;
    }

    /** 기존 엔드포인트 — 수동 테스트 / 하위 호환용 (prNumber를 commitSha로 재사용). */
    @PostMapping
    public ResponseEntity<TaskResponse> dispatch(@RequestBody TaskRequest request) {
        return ResponseEntity.accepted().body(taskService.dispatch(request));
    }

    /** GitHub 커밋 diff 기반 task 디스패치 엔드포인트. */
    @PostMapping("/commits")
    public ResponseEntity<CommitTaskResponse> dispatchCommits(@RequestBody CommitTaskRequest request) {
        return ResponseEntity.accepted().body(commitTaskService.dispatch(request));
    }
}
