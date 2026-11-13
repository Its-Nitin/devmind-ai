package com.devmind.project.internal.web;

import com.devmind.identity.CurrentUser;
import com.devmind.project.ProjectId;
import com.devmind.project.internal.application.NewProject;
import com.devmind.project.internal.application.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The signed-in user's projects (ARCH-9: validate, call one service method, map). Method names are the OpenAPI
 * operation IDs. Other users' projects answer 404, never 403 (API-8).
 */
@RestController
@RequestMapping(ProjectController.BASE_PATH)
@Tag(name = "Projects")
class ProjectController {

    static final String BASE_PATH = "/api/projects";
    static final int MAX_PAGE_SIZE = 100;

    private final ProjectService projects;
    private final CurrentUser currentUser;

    ProjectController(ProjectService projects, CurrentUser currentUser) {
        this.projects = projects;
        this.currentUser = currentUser;
    }

    @PostMapping
    @Operation(summary = "Register a project")
    @ApiResponse(
            responseCode = "201",
            description = "Created",
            headers =
                    @Header(
                            name = "Location",
                            description = "Path of the new project",
                            schema = @Schema(type = "string", format = "uri-reference")))
    @ApiResponse(
            responseCode = "409",
            description = "You already have a project with this name (code: project-name-taken)")
    ResponseEntity<ProjectResponse> createProject(@Valid @RequestBody CreateProjectRequest request) {
        ProjectResponse created = ProjectResponse.of(projects.create(
                currentUser.id(), new NewProject(request.name(), request.sourceType(), request.sourceLocation())));
        URI location = UriComponentsBuilder.fromPath(BASE_PATH + "/{projectId}")
                .buildAndExpand(created.projectId())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** Pages are zero-based; JSON shape is Spring Data's stable {@link PagedModel} ({@code content} + {@code page}). */
    @GetMapping
    @Operation(summary = "List your projects")
    PagedModel<ProjectResponse> listProjects(
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @Parameter(description = "`name` or `createdAt`, optionally followed by `,asc` or `,desc`")
                    @RequestParam(defaultValue = ProjectSort.DEFAULT)
                    @Pattern(regexp = ProjectSort.PATTERN)
                    String sort) {
        PageRequest pageRequest = PageRequest.of(page, size, ProjectSort.parse(sort));
        return new PagedModel<>(projects.listOwn(currentUser.id(), pageRequest).map(ProjectResponse::of));
    }

    @GetMapping("/{projectId}")
    @Operation(summary = "Get one of your projects")
    ProjectResponse getProject(@PathVariable UUID projectId) {
        return ProjectResponse.of(projects.get(currentUser.id(), new ProjectId(projectId)));
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete one of your projects")
    void deleteProject(@PathVariable UUID projectId) {
        projects.delete(currentUser.id(), new ProjectId(projectId));
    }
}
