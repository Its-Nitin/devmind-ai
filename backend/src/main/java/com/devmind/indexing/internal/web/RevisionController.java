package com.devmind.indexing.internal.web;

import com.devmind.identity.CurrentUser;
import com.devmind.indexing.SymbolKind;
import com.devmind.indexing.internal.application.RevisionReadService;
import com.devmind.indexing.internal.web.RevisionResponses.DirectoryResponse;
import com.devmind.indexing.internal.web.RevisionResponses.FileSliceResponse;
import com.devmind.indexing.internal.web.RevisionResponses.RevisionResponse;
import com.devmind.indexing.internal.web.RevisionResponses.SymbolDetailResponse;
import com.devmind.indexing.internal.web.RevisionResponses.SymbolSearchResponse;
import com.devmind.project.ProjectId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Indexed revisions: listing, file tree, source and symbols (M2 step 8; ARCH-9: validate, call one service method,
 * map). Only READY revisions can be read; other users' projects answer 404 (API-8).
 */
@RestController
@RequestMapping(RevisionController.BASE_PATH)
@Tag(name = "Revisions")
class RevisionController {

    static final String BASE_PATH = "/api/projects/{projectId}/revisions";
    static final int MAX_PAGE_SIZE = 100;

    /** A full commit SHA, or a prefix of at least 7 characters. */
    private static final String SHA = "^[0-9a-f]{7,40}$";

    private static final String NOT_READY = "Not READY yet (code: revision-not-ready), or the prefix matches more "
            + "than one commit (code: revision-ambiguous)";

    private final RevisionReadService revisions;
    private final CurrentUser currentUser;

    RevisionController(RevisionReadService revisions, CurrentUser currentUser) {
        this.revisions = revisions;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "List the project's revisions, newest first")
    PagedModel<RevisionResponse> listRevisions(
            @PathVariable UUID projectId,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return new PagedModel<>(revisions
                .listRevisions(currentUser.id(), new ProjectId(projectId), PageRequest.of(page, size))
                .map(RevisionResponse::of));
    }

    @GetMapping("/{sha}/files")
    @Operation(summary = "List a directory of a revision")
    @ApiResponse(responseCode = "409", description = NOT_READY)
    DirectoryResponse listFiles(
            @PathVariable UUID projectId,
            @Parameter(description = "Commit SHA, or a unique prefix of at least 7 characters")
                    @PathVariable
                    @Pattern(regexp = SHA)
                    String sha,
            @Parameter(description = "Directory, e.g. src/main; empty for the root")
                    @RequestParam(defaultValue = "")
                    @Size(max = 1000)
                    String path) {
        return DirectoryResponse.of(revisions.listDirectory(currentUser.id(), new ProjectId(projectId), sha, path));
    }

    @GetMapping("/{sha}/file")
    @Operation(
            summary = "Read lines of a file of a revision",
            description = "At most " + RevisionReadService.MAX_LINES + " lines per call; read longer files in ranges.")
    @ApiResponse(responseCode = "409", description = NOT_READY)
    @ApiResponse(
            responseCode = "422",
            description = "The range is past the end, reversed, or too long (code: line-range-invalid)")
    FileSliceResponse readFile(
            @PathVariable UUID projectId,
            @PathVariable @Pattern(regexp = SHA) String sha,
            @Parameter(description = "File path, e.g. src/main/java/App.java") @RequestParam @NotBlank @Size(max = 1000)
                    String path,
            @Parameter(description = "First line, 1-based; default 1") @RequestParam(required = false) @Min(1) @Nullable
                    Integer from,
            @Parameter(description = "Last line, inclusive; default the end, within the per-call limit")
                    @RequestParam(required = false)
                    @Min(1)
                    @Nullable
                    Integer to) {
        return FileSliceResponse.of(
                revisions.readFile(currentUser.id(), new ProjectId(projectId), sha, path, from, to));
    }

    @GetMapping("/{sha}/symbols")
    @Operation(
            summary = "Search a revision's symbols by name",
            description = "Exact matches of the name or qualified name first, then similar names, most similar first.")
    @ApiResponse(responseCode = "409", description = NOT_READY)
    SymbolSearchResponse searchSymbols(
            @PathVariable UUID projectId,
            @PathVariable @Pattern(regexp = SHA) String sha,
            @Parameter(description = "Name or qualified name, e.g. OwnerService")
                    @RequestParam
                    @NotBlank
                    @Size(max = 200)
                    String q,
            @RequestParam(required = false) @Nullable SymbolKind kind,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return SymbolSearchResponse.of(revisions.searchSymbols(
                currentUser.id(), new ProjectId(projectId), sha, q, kind, PageRequest.of(page, size)));
    }

    @GetMapping("/{sha}/symbols/{symbolId}")
    @Operation(summary = "Get a symbol of a revision, with its direct members")
    @ApiResponse(responseCode = "409", description = NOT_READY)
    SymbolDetailResponse getSymbol(
            @PathVariable UUID projectId,
            @PathVariable @Pattern(regexp = SHA) String sha,
            @PathVariable UUID symbolId) {
        return SymbolDetailResponse.of(revisions.symbol(currentUser.id(), new ProjectId(projectId), sha, symbolId));
    }
}
