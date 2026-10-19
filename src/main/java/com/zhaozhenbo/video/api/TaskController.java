package com.zhaozhenbo.video.api;

import com.zhaozhenbo.video.adapter.persistence.VideoTaskEntity;
import com.zhaozhenbo.video.adapter.persistence.VideoTaskRepository;
import com.zhaozhenbo.video.api.dto.NoteResponse;
import com.zhaozhenbo.video.api.dto.TaskResponse;
import com.zhaozhenbo.video.application.TaskSubmissionService;
import com.zhaozhenbo.video.port.NoteStorePort;
import com.zhaozhenbo.video.port.StoredNote;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskSubmissionService submission;
    private final VideoTaskRepository tasks;
    private final NoteStorePort notes;

    public TaskController(TaskSubmissionService submission,
                          VideoTaskRepository tasks,
                          NoteStorePort notes) {
        this.submission = submission;
        this.tasks = tasks;
        this.notes = notes;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TaskResponse> submit(@RequestParam("file") MultipartFile file,
                                               @RequestParam(value = "title", required = false) String title) {
        VideoTaskEntity task = submission.submit(file, title);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(TaskResponse.from(task));
    }

    @GetMapping("/{id}")
    public TaskResponse get(@PathVariable long id) {
        return TaskResponse.from(findTask(id));
    }

    @GetMapping("/{id}/note")
    public NoteResponse note(@PathVariable long id) {
        VideoTaskEntity task = findTask(id);
        StoredNote note = notes.findByTask(task.getId(), task.getUserId(), task.getFingerprint())
                .orElseThrow(() -> new NoteNotReadyException(id));
        return NoteResponse.from(note);
    }

    @GetMapping(value = "/{id}/note.md", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> markdown(@PathVariable long id) {
        VideoTaskEntity task = findTask(id);
        StoredNote note = notes.findByTask(task.getId(), task.getUserId(), task.getFingerprint())
                .orElseThrow(() -> new NoteNotReadyException(id));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"note-" + id + ".md\"")
                .body(note.markdown());
    }

    @PostMapping("/{id}/retry")
    public TaskResponse retry(@PathVariable long id) {
        return TaskResponse.from(submission.retry(id));
    }

    private VideoTaskEntity findTask(long id) {
        return tasks.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
    }
}
