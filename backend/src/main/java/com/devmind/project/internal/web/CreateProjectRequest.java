package com.devmind.project.internal.web;

import com.devmind.project.SourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/projects} (API-2). {@code sourceLocation} only gets a syntax check here; real path and URL
 * validation happens in ingestion (M2, SEC-2/3).
 */
record CreateProjectRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull SourceType sourceType,
        @NotBlank @Size(max = 500) String sourceLocation) {}
