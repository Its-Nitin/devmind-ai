package com.devmind.project.internal.application;

import com.devmind.project.SourceType;

/** Input for creating a project, already validated at the web boundary (API-2). */
public record NewProject(String name, SourceType sourceType, String sourceLocation) {}
