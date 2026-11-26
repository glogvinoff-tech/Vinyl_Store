package ru.mirea.vinylstore.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.mirea.vinylstore.dto.RecordDtos.RecordRequest;
import ru.mirea.vinylstore.dto.RecordDtos.RecordResponse;
import ru.mirea.vinylstore.service.RecordService;

import java.util.List;
import java.util.UUID;

/** REST-контур пластинок. */
@RestController
@RequestMapping("/api/records")
@SecurityRequirement(name = "bearerAuth")
public class RecordController {
    private final RecordService records;

    public RecordController(RecordService records) { this.records = records; }

    @GetMapping
    @PreAuthorize("hasAnyRole('VIEWER','MANAGER','ADMIN')")
    public List<RecordResponse> list(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return records.list(activeOnly);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RecordResponse> create(@Valid @RequestBody RecordRequest request) {
        return ResponseEntity.status(201).body(records.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public RecordResponse update(@PathVariable UUID id, @Valid @RequestBody RecordRequest request) {
        return records.update(id, request);
    }

    @PatchMapping("/{id}/active")
    @PreAuthorize("hasRole('ADMIN')")
    public RecordResponse setActive(@PathVariable UUID id, @RequestParam boolean active, @RequestParam long version) {
        return records.setActive(id, active, version);
    }
}
