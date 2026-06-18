package com.taskscope.dispatcher.controller;

import com.taskscope.dispatcher.model.TaskRequest;
import com.taskscope.dispatcher.model.TaskResponse;
import com.taskscope.dispatcher.service.TaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public ResponseEntity<TaskResponse> dispatch(@RequestBody TaskRequest request) {
        return ResponseEntity.accepted().body(taskService.dispatch(request));
    }
}
