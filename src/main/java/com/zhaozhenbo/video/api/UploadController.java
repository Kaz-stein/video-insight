package com.zhaozhenbo.video.api;

import com.zhaozhenbo.video.api.dto.ChunkResponse;
import com.zhaozhenbo.video.api.dto.CompleteUploadRequest;
import com.zhaozhenbo.video.api.dto.CreateUploadRequest;
import com.zhaozhenbo.video.api.dto.TaskResponse;
import com.zhaozhenbo.video.api.dto.UploadStatusResponse;
import com.zhaozhenbo.video.application.UploadSessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/uploads")
public class UploadController {

    private final UploadSessionService uploads;

    public UploadController(UploadSessionService uploads) {
        this.uploads = uploads;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UploadStatusResponse> create(@RequestBody CreateUploadRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(uploads.create(request));
    }

    @PutMapping(value = "/{id}/chunks/{index}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ChunkResponse putChunk(@PathVariable String id,
                                  @PathVariable int index,
                                  @RequestBody byte[] data,
                                  @RequestHeader(value = "X-Chunk-SHA256", required = false) String checksum) {
        return uploads.putChunk(id, index, data, checksum);
    }

    @GetMapping("/{id}")
    public UploadStatusResponse status(@PathVariable String id) {
        return uploads.status(id);
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<TaskResponse> complete(@PathVariable String id,
                                                 @RequestBody(required = false) CompleteUploadRequest request) {
        String title = request == null ? null : request.title();
        String expectedSha256 = request == null ? null : request.expectedSha256();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(TaskResponse.from(uploads.complete(id, expectedSha256, title)));
    }
}
