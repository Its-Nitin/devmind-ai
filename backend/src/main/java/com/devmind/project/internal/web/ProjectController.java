package com.devmind.project.internal.web;

import com.devmind.identity.CurrentUser;
import com.devmind.project.ProjectId;
import com.devmind.project.internal.application.NewProject;
import com.devmind.project.internal.application.ProjectService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/** The signed-in user's projects (ARCH-9: validate, call one service method, map). */
@RestController
@RequestMapping(ProjectController.BASE_PATH)
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
    ResponseEntity<ProjectResponse> create(@Valid @RequestBody CreateProjectRequest request) {
        ProjectResponse created = ProjectResponse.of(projects.create(
                currentUser.id(), new NewProject(request.name(), request.sourceType(), request.sourceLocation())));
        URI location = UriComponentsBuilder.fromPath(BASE_PATH + "/{projectId}")
                .buildAndExpand(created.projectId())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** Pages are zero-based; JSON shape is Spring Data's stable {@link PagedModel} ({@code content} + {@code page}). */
    @GetMapping
    PagedModel<ProjectResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @RequestParam(defaultValue = ProjectSort.DEFAULT) @Pattern(regexp = ProjectSort.PATTERN) String sort) {
        PageRequest pageRequest = PageRequest.of(page, size, ProjectSort.parse(sort));
        return new PagedModel<>(projects.listOwn(currentUser.id(), pageRequest).map(ProjectResponse::of));
    }

    @GetMapping("/{projectId}")
    ProjectResponse get(@PathVariable UUID projectId) {
        return ProjectResponse.of(projects.get(currentUser.id(), new ProjectId(projectId)));
    }

    @DeleteMapping("/{projectId}")
    ResponseEntity<Void> delete(@PathVariable UUID projectId) {
        projects.delete(currentUser.id(), new ProjectId(projectId));
        return ResponseEntity.noContent().build();
    }
}
