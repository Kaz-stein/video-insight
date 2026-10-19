const $ = (selector) => document.querySelector(selector);

const STAGES = [
    ['FETCHING', '正在读取视频字幕…'],
    ['SEGMENTING', '正在切分语义段…'],
    ['PLANNING', '正在规划章节…'],
    ['EXTRACTING', '正在抽取要点…'],
    ['VERIFYING', '正在核对引用…'],
    ['COMPOSING', '正在写成笔记…'],
];

const STAGE_NAMES = STAGES.map(([name]) => name);
const CHUNK_SIZE = 8 * 1024 * 1024;
const UPLOAD_CONCURRENCY = 3;
const VIDEO_EXT = ['.mp4', '.mkv', '.mov', '.webm', '.m4v', '.avi', '.flv'];
const SUBTITLE_EXT = ['.srt', '.vtt'];

const SAMPLE_SRT = `1
00:00:00,000 --> 00:00:08,000
Java 线程池的核心参数包括核心线程数、最大线程数和队列长度。

2
00:00:08,000 --> 00:00:16,000
当任务数量超过核心线程数时，任务会先进入阻塞队列等待。

3
00:00:16,000 --> 00:00:24,000
只有队列满了，线程池才会创建非核心线程。`;

const state = {
    mode: 'link',
    file: null,
    fileKind: null,
    taskId: null,
    task: null,
    note: null,
    polling: false,
};

const els = {
    sourceTabs: [...document.querySelectorAll('.source-tab')],
    linkPanel: $('#linkPanel'),
    filePanel: $('#filePanel'),
    linkInput: $('#linkInput'),
    dropZone: $('#dropZone'),
    fileInput: $('#fileInput'),
    dropTitle: $('#dropTitle'),
    dropHint: $('#dropHint'),
    fileName: $('#fileName'),
    titleInput: $('#titleInput'),
    submitBtn: $('#submitBtn'),
    demoBtn: $('#demoBtn'),
    heroUpload: $('#heroUpload'),
    uploadMsg: $('#uploadMsg'),
    retryBtn: $('#retryBtn'),
    uploadProgress: $('#uploadProgress'),
    progressBar: $('#progressBar'),
    progressText: $('#progressText'),
    stageBlock: $('#stageBlock'),
    stageText: $('#stageText'),
    stageList: $('#stageList'),
    noteEmpty: $('#noteEmpty'),
    noteArticle: $('#noteArticle'),
    noteTitle: $('#noteTitle'),
    noteMeta: $('#noteMeta'),
    noteSummary: $('#noteSummary'),
    noteBody: $('#noteBody'),
    rejectedBlock: $('#rejectedBlock'),
    rejectedList: $('#rejectedList'),
    timeReadout: $('#timeReadout'),
    downloadBtn: $('#downloadBtn'),
    statRecall: $('#statRecall'),
    statUnsupported: $('#statUnsupported'),
    statDuration: $('#statDuration'),
};

function getUserId() {
    const key = 'video-insight-user';
    try {
        let value = localStorage.getItem(key);
        if (!value) {
            value = `web-${Math.random().toString(36).slice(2, 10)}`;
            localStorage.setItem(key, value);
        }
        return value;
    } catch {
        return `web-${Math.random().toString(36).slice(2, 10)}`;
    }
}

function setMessage(text, isError = false) {
    els.uploadMsg.textContent = text || '';
    els.uploadMsg.classList.toggle('is-error', Boolean(isError));
}

function updateSubmitAvailability() {
    const ready = state.mode === 'link'
        ? Boolean(els.linkInput.value.trim())
        : Boolean(state.file);
    els.submitBtn.disabled = !ready || state.polling;
}

function setBusy(busy) {
    els.demoBtn.disabled = busy;
    els.fileInput.disabled = busy;
    els.linkInput.disabled = busy;
    state.polling = busy;
    updateSubmitAvailability();
}

function formatBytes(bytes) {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
    return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

function formatTime(ms) {
    const total = Math.max(0, Math.floor(ms / 1000));
    const hours = Math.floor(total / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    const seconds = total % 60;
    return hours > 0
        ? `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
        : `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}

function formatPercent(value) {
    return `${Math.round((Number(value) || 0) * 100)}%`;
}

function formatDuration(ms) {
    const value = Number(ms) || 0;
    if (value < 1000) return `${Math.round(value)} ms`;
    return `${(value / 1000).toFixed(1)} s`;
}

function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

function extensionOf(name) {
    const dot = name.lastIndexOf('.');
    return dot < 0 ? '' : name.slice(dot).toLowerCase();
}

function setMode(mode) {
    state.mode = mode;
    els.sourceTabs.forEach((tab) => {
        const active = tab.dataset.mode === mode;
        tab.classList.toggle('is-active', active);
        tab.setAttribute('aria-selected', String(active));
    });
    els.linkPanel.hidden = mode !== 'link';
    els.filePanel.hidden = mode !== 'file';
    els.submitBtn.firstChild.textContent = mode === 'link' ? '解析链接并生成' : '上传并生成';
    updateSubmitAvailability();
}

function showProgress(ratio) {
    const percent = Math.max(0, Math.min(100, Math.round(ratio * 100)));
    els.uploadProgress.hidden = false;
    els.progressBar.style.width = `${percent}%`;
    els.progressText.textContent = `${percent}%`;
}

function pickFile(file) {
    if (!file) return;
    const ext = extensionOf(file.name);
    if (!VIDEO_EXT.includes(ext) && !SUBTITLE_EXT.includes(ext)) {
        setMessage('支持 mp4 / mkv / mov / webm 视频，或 srt / vtt 字幕。', true);
        return;
    }
    state.file = file;
    state.fileKind = VIDEO_EXT.includes(ext) ? 'video' : 'subtitle';
    els.fileName.textContent = `${file.name} · ${formatBytes(file.size)}`;
    els.fileName.hidden = false;
    els.dropTitle.textContent = state.fileKind === 'video' ? '视频已就位' : '字幕已就位';
    els.dropHint.textContent = state.fileKind === 'video'
        ? '将按 8MB 分片、3 路并发上传，可断点续传'
        : '字幕将单请求上传';
    if (!els.titleInput.value.trim()) {
        els.titleInput.value = file.name.replace(/\.(mp4|mkv|mov|webm|m4v|avi|flv|srt|vtt)$/i, '');
    }
    els.retryBtn.hidden = true;
    setMessage('');
    updateSubmitAvailability();
}

function resetForNewTask() {
    state.taskId = null;
    state.task = null;
    state.note = null;
    state.polling = false;
    els.stageBlock.hidden = true;
    els.retryBtn.hidden = true;
    els.noteArticle.hidden = true;
    els.noteEmpty.hidden = false;
    els.stageList.querySelectorAll('li').forEach((li) => {
        li.classList.remove('done', 'active');
    });
}

async function submit() {
    if (state.mode === 'link') {
        await submitLink();
    } else if (state.fileKind === 'video') {
        await chunkedUpload(state.file);
    } else {
        await uploadSubtitle(state.file);
    }
}

async function submitLink() {
    const url = els.linkInput.value.trim();
    if (!url) {
        setMessage('先粘贴一个视频链接。', true);
        return;
    }
    resetForNewTask();
    setBusy(true);
    setMessage('正在提交链接任务…');
    try {
        const response = await fetch('/api/tasks/link', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-User-Id': getUserId(),
            },
            body: JSON.stringify({ url, title: els.titleInput.value.trim() }),
        });
        const payload = await response.json().catch(() => ({}));
        if (response.status === 429) {
            setMessage(`提交太频繁了，请 ${response.headers.get('Retry-After') || '几'} 秒后再试。`, true);
            setBusy(false);
            return;
        }
        if (!response.ok) {
            throw new Error(payload.error || `HTTP ${response.status}`);
        }
        startTask(payload);
    } catch (err) {
        setMessage(`链接提交失败：${err.message}`, true);
        setBusy(false);
    }
}

async function uploadSubtitle(file) {
    resetForNewTask();
    setBusy(true);
    setMessage('正在上传字幕…');
    const form = new FormData();
    form.append('file', file);
    form.append('title', els.titleInput.value.trim() || file.name.replace(/\.(srt|vtt)$/i, ''));
    try {
        const response = await fetch('/api/tasks', {
            method: 'POST',
            headers: { 'X-User-Id': getUserId() },
            body: form,
        });
        const payload = await response.json().catch(() => ({}));
        if (response.status === 429) {
            setMessage(`提交太频繁了，请 ${response.headers.get('Retry-After') || '几'} 秒后再试。`, true);
            setBusy(false);
            return;
        }
        if (!response.ok) {
            throw new Error(payload.error || `HTTP ${response.status}`);
        }
        startTask(payload);
    } catch (err) {
        setMessage(`字幕提交失败：${err.message}`, true);
        setBusy(false);
    }
}

function uploadKey(file) {
    return `vi-upload:${file.name}:${file.size}:${file.lastModified}`;
}

async function sha256Hex(buffer) {
    const digest = await crypto.subtle.digest('SHA-256', buffer);
    return [...new Uint8Array(digest)]
        .map((byte) => byte.toString(16).padStart(2, '0'))
        .join('');
}

async function chunkedUpload(file) {
    resetForNewTask();
    setBusy(true);
    setMessage('正在准备分片上传…');
    showProgress(0);

    const totalChunks = Math.ceil(file.size / CHUNK_SIZE);
    let uploadId = null;
    try {
        const saved = localStorage.getItem(uploadKey(file));
        if (saved) {
            const response = await fetch(`/api/uploads/${saved}`, { cache: 'no-store' });
            if (response.ok) {
                const status = await response.json();
                if (status.status === 'UPLOADING') {
                    uploadId = status.uploadId;
                    setMessage(`发现未完成的上传，继续补传剩余分片…`);
                }
            }
        }

        if (!uploadId) {
            const response = await fetch('/api/uploads', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'X-User-Id': getUserId(),
                },
                body: JSON.stringify({
                    fileName: file.name,
                    totalSize: file.size,
                    totalChunks,
                    chunkSize: CHUNK_SIZE,
                }),
            });
            const payload = await response.json().catch(() => ({}));
            if (!response.ok) {
                throw new Error(payload.error || `HTTP ${response.status}`);
            }
            uploadId = payload.uploadId;
            localStorage.setItem(uploadKey(file), uploadId);
        }

        const statusResponse = await fetch(`/api/uploads/${uploadId}`, { cache: 'no-store' });
        const status = await statusResponse.json();
        const uploaded = new Set(status.uploadedChunks || []);

        const pending = [];
        for (let index = 0; index < totalChunks; index++) {
            if (!uploaded.has(index)) pending.push(index);
        }

        // 3 路并发：串行传 1GB（约 128 片）要等每一片的往返时延，
        // 并发只提升吞吐，不改变"按 index 落盘"的语义，所以合并顺序仍然安全。
        let finished = uploaded.size;
        showProgress(totalChunks === 0 ? 1 : finished / totalChunks);
        if (pending.length === 0) {
            setMessage('所有分片都已在服务端，直接合并…');
        }
        let cursor = 0;
        async function uploadWorker() {
            while (true) {
                const next = cursor++;
                if (next >= pending.length) return;
                const index = pending[next];
                const start = index * CHUNK_SIZE;
                const blob = file.slice(start, Math.min(file.size, start + CHUNK_SIZE));
                const buffer = await blob.arrayBuffer();
                const checksum = await sha256Hex(buffer);
                await putChunk(uploadId, index, blob, checksum);
                finished++;
                showProgress(finished / totalChunks);
                setMessage(`正在上传分片 ${finished} / ${totalChunks}…`);
            }
        }
        const workers = Array.from(
            { length: Math.min(UPLOAD_CONCURRENCY, pending.length) },
            () => uploadWorker(),
        );
        await Promise.all(workers);

        setMessage('分片已传完，正在合并并创建任务…');
        const response = await fetch(`/api/uploads/${uploadId}/complete`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-User-Id': getUserId(),
            },
            body: JSON.stringify({ title: els.titleInput.value.trim() }),
        });
        const payload = await response.json().catch(() => ({}));
        if (!response.ok) {
            throw new Error(payload.error || `HTTP ${response.status}`);
        }
        localStorage.removeItem(uploadKey(file));
        showProgress(1);
        startTask(payload);
    } catch (err) {
        setBusy(false);
        setMessage(`上传中断：${err.message}。已上传的分片会保留，再点一次会继续。`, true);
    }
}

async function putChunk(uploadId, index, blob, checksum) {
    let lastError = null;
    for (let attempt = 1; attempt <= 3; attempt++) {
        try {
            const response = await fetch(`/api/uploads/${uploadId}/chunks/${index}`, {
                method: 'PUT',
                headers: {
                    'Content-Type': 'application/octet-stream',
                    'X-Chunk-SHA256': checksum,
                },
                body: blob,
            });
            if (response.ok) return;
            const payload = await response.json().catch(() => ({}));
            lastError = new Error(payload.error || `HTTP ${response.status}`);
            if (response.status >= 400 && response.status < 500) break;
        } catch (err) {
            lastError = err;
        }
        await sleep(400 * attempt);
    }
    throw lastError || new Error('分片上传失败');
}

function startTask(payload) {
    state.taskId = payload.id;
    state.task = payload;
    els.stageBlock.hidden = false;
    setStage(payload);
    setMessage('任务已提交，正在后台处理。');
    pollTask(payload.id);
}

async function pollTask(taskId) {
    state.polling = true;
    updateSubmitAvailability();
    while (state.polling) {
        try {
            const response = await fetch(`/api/tasks/${taskId}`, { cache: 'no-store' });
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            const task = await response.json();
            state.task = task;
            setStage(task);

            if (task.status === 'DONE') {
                state.polling = false;
                setBusy(false);
                setMessage('笔记已生成。');
                await loadNote(task);
                return;
            }
            if (task.status === 'FAILED') {
                state.polling = false;
                setBusy(false);
                setMessage(task.error || '任务失败，可尝试从断点重试。', true);
                els.retryBtn.hidden = false;
                return;
            }
        } catch (err) {
            state.polling = false;
            setBusy(false);
            setMessage(`轮询任务失败：${err.message}`, true);
            return;
        }
        await sleep(1200);
    }
}

function setStage(task) {
    const status = task.status;
    const lastDone = task.stage ? STAGE_NAMES.indexOf(task.stage) : -1;
    const active = status === 'PENDING'
        ? 0
        : status === 'RUNNING'
            ? Math.min(lastDone + 1, STAGES.length - 1)
            : -1;

    els.stageList.querySelectorAll('li').forEach((li, index) => {
        li.classList.toggle('done', status === 'DONE' || index <= lastDone);
        li.classList.toggle('active', status !== 'FAILED' && status !== 'DONE' && index === active);
    });

    if (status === 'DONE') {
        els.stageText.textContent = '笔记已生成';
    } else if (status === 'FAILED') {
        els.stageText.textContent = '处理失败';
    } else if (status === 'PENDING') {
        els.stageText.textContent = '排队中…';
    } else {
        els.stageText.textContent = STAGES[Math.max(0, active)][1];
    }
}

async function loadNote(task) {
    try {
        const response = await fetch(`/api/tasks/${task.id}/note`, { cache: 'no-store' });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const note = await response.json();
        state.note = note;
        renderNote(note, task);
    } catch (err) {
        setMessage(`笔记读取失败：${err.message}`, true);
    }
}

function renderNote(note, task) {
    els.noteEmpty.hidden = true;
    els.noteArticle.hidden = false;
    els.noteTitle.textContent = task.title || '视频笔记';
    els.noteSummary.textContent = note.summary || '这份笔记暂时没有摘要。';
    els.noteMeta.replaceChildren();

    // provider=fake 时后端会返回 offline-fake，这里必须显式提示，
    // 否则用户会以为这些"抄原句"的要点是模型写的总结。
    if (String(note.model || '').startsWith('offline')) {
        const warn = document.createElement('span');
        warn.className = 'meta-chip is-warn';
        warn.textContent = '离线抽取模式 · 未接入大模型';
        els.noteMeta.appendChild(warn);
    }

    [
        `模型 ${note.model || '未知'}`,
        `prompt ${note.promptVersion || '—'}`,
        `${note.points?.length || 0} 条要点`,
        `note #${note.noteId}`,
    ].forEach((text) => {
        const chip = document.createElement('span');
        chip.className = 'meta-chip';
        chip.textContent = text;
        els.noteMeta.appendChild(chip);
    });

    els.noteBody.replaceChildren();
    const points = Array.isArray(note.points) ? note.points : [];
    const visible = points.filter((point) => point.status !== 'REJECTED');
    const rejected = points.filter((point) => point.status === 'REJECTED');

    if (visible.length === 0) {
        const empty = document.createElement('p');
        empty.className = 'point-text';
        empty.textContent = '这篇笔记没有通过校验的要点。';
        els.noteBody.appendChild(empty);
    } else {
        const groups = new Map();
        visible.forEach((point) => {
            const section = point.section || '正文';
            if (!groups.has(section)) groups.set(section, []);
            groups.get(section).push(point);
        });

        groups.forEach((groupPoints, sectionTitle) => {
            const section = document.createElement('section');
            section.className = 'note-section';
            const heading = document.createElement('h3');
            heading.textContent = sectionTitle;
            section.appendChild(heading);
            const list = document.createElement('div');
            list.className = 'point-list';
            groupPoints.forEach((point) => list.appendChild(renderPoint(point)));
            section.appendChild(list);
            els.noteBody.appendChild(section);
        });
    }

    els.rejectedBlock.hidden = rejected.length === 0;
    els.rejectedList.replaceChildren();
    rejected.forEach((point) => {
        const item = document.createElement('div');
        item.className = 'rejected-item';
        item.textContent = point.text;
        const reason = document.createElement('small');
        reason.textContent = point.reason || '没有足够的原文依据';
        item.appendChild(reason);
        els.rejectedList.appendChild(item);
    });

    if (visible[0]) {
        const firstRow = els.noteBody.querySelector('.point-row');
        setTimeReadout(visible[0].startMs, firstRow);
    }
}

function renderPoint(point) {
    const row = document.createElement('article');
    row.className = 'point-row';

    const time = document.createElement('button');
    time.type = 'button';
    time.className = 'time-chip';
    time.textContent = formatTime(point.startMs);
    time.setAttribute('aria-label', `跳到 ${formatTime(point.startMs)}`);
    time.addEventListener('click', () => setTimeReadout(point.startMs, row));

    const copy = document.createElement('div');
    const text = document.createElement('p');
    text.className = 'point-text';
    text.textContent = point.text;

    const toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.className = 'quote-toggle';
    toggle.textContent = '查看原文';

    const quote = document.createElement('blockquote');
    quote.className = 'point-quote';
    quote.textContent = point.quote || '（没有保存原文片段）';

    toggle.addEventListener('click', () => {
        const open = row.classList.toggle('is-open');
        toggle.textContent = open ? '收起原文' : '查看原文';
    });

    copy.append(text, toggle, quote);
    const badge = document.createElement('span');
    const status = point.status || 'INFERRED';
    badge.className = `status-badge ${status.toLowerCase()}`;
    badge.textContent = status === 'GROUNDED' ? '有依据' : status === 'INFERRED' ? '模型推断' : '未通过';
    row.append(time, copy, badge);
    return row;
}

function setTimeReadout(ms, row) {
    els.timeReadout.textContent = formatTime(ms);
    els.noteBody.querySelectorAll('.point-row.is-active').forEach((el) => el.classList.remove('is-active'));
    if (row) row.classList.add('is-active');
}

async function retry() {
    if (!state.taskId) return;
    setBusy(true);
    els.retryBtn.hidden = true;
    setMessage('正在重新投递…');
    try {
        const response = await fetch(`/api/tasks/${state.taskId}/retry`, {
            method: 'POST',
            headers: { 'X-User-Id': getUserId() },
        });
        const payload = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(payload.error || `HTTP ${response.status}`);
        state.task = payload;
        els.stageBlock.hidden = false;
        setStage(payload);
        await pollTask(payload.id);
    } catch (err) {
        setBusy(false);
        setMessage(`重试失败：${err.message}`, true);
        els.retryBtn.hidden = false;
    }
}

async function downloadMarkdown() {
    if (!state.taskId) return;
    try {
        const response = await fetch(`/api/tasks/${state.taskId}/note.md`);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const blob = await response.blob();
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = `note-${state.taskId}.md`;
        document.body.appendChild(link);
        link.click();
        link.remove();
        URL.revokeObjectURL(url);
    } catch (err) {
        setMessage(`下载失败：${err.message}`, true);
    }
}

async function loadEval() {
    try {
        const response = await fetch('/api/eval', { cache: 'no-store' });
        if (!response.ok) return;
        const data = await response.json();
        els.statRecall.textContent = formatPercent(data.citationRecall);
        els.statUnsupported.textContent = formatPercent(data.unsupportedRate);
        els.statDuration.textContent = formatDuration(data.avgDurationMs);
    } catch {
        // 指标只是辅助信息
    }
}

function bindEvents() {
    els.sourceTabs.forEach((tab) => {
        tab.addEventListener('click', () => setMode(tab.dataset.mode));
    });
    els.linkInput.addEventListener('input', updateSubmitAvailability);
    els.linkInput.addEventListener('keydown', (event) => {
        if ((event.ctrlKey || event.metaKey) && event.key === 'Enter'
                && els.linkInput.value.trim()) {
            submit();
        }
    });

    els.heroUpload.addEventListener('click', () => {
        document.querySelector('#workbench').scrollIntoView({ behavior: 'smooth', block: 'start' });
        setMode('link');
        setTimeout(() => els.linkInput.focus(), 350);
    });

    els.dropZone.addEventListener('click', () => els.fileInput.click());
    els.dropZone.addEventListener('keydown', (event) => {
        if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            els.fileInput.click();
        }
    });
    els.fileInput.addEventListener('change', () => pickFile(els.fileInput.files?.[0]));

    ['dragenter', 'dragover'].forEach((name) => {
        els.dropZone.addEventListener(name, (event) => {
            event.preventDefault();
            els.dropZone.classList.add('is-dragging');
        });
    });
    ['dragleave', 'drop'].forEach((name) => {
        els.dropZone.addEventListener(name, (event) => {
            event.preventDefault();
            els.dropZone.classList.remove('is-dragging');
        });
    });
    els.dropZone.addEventListener('drop', (event) => {
        pickFile(event.dataTransfer?.files?.[0]);
    });

    els.submitBtn.addEventListener('click', submit);
    els.retryBtn.addEventListener('click', retry);
    els.downloadBtn.addEventListener('click', downloadMarkdown);
    els.demoBtn.addEventListener('click', () => {
        setMode('file');
        const file = new File([SAMPLE_SRT], 'thread-pool-demo.srt', { type: 'text/plain' });
        pickFile(file);
        els.titleInput.value = '线程池讲解';
        submit();
    });
}

setMode('link');
bindEvents();
loadEval();

// 演示入口：/?demo=1 自动跑示例字幕
if (new URLSearchParams(window.location.search).get('demo') === '1') {
    setTimeout(() => els.demoBtn.click(), 500);
}
