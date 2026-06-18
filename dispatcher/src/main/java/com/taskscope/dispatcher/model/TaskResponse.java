package com.taskscope.dispatcher.model;

import java.util.List;

public record TaskResponse(
        String taskId,
        String status,
        String modelGrade,
        List<String> queuedTaskTypes
) {}
