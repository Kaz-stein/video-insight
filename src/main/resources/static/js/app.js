const $ = (selector) => document.querySelector(selector);

const STAGES = [
    ['FETCHING', '正在读取字幕…'],
    ['SEGMENTING', '正在切分语义段…'],
    ['PLANNING', '正在规划章节…'],
    ['EXTRACTING', '正在抽取要点…'],
    ['VERIFYING', '正在核对引用…'],
    ['COMPOSING', '正在写成笔记…'],
];

const STAGE_NAMES = STAGES.map(([name]) => name);

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
    file: null,
    taskId: null,
    task: null,
    note: null,
    polling: false,
};

const els = {
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

function setBusy(busy) {
    els.submitBtn.disabled = busy || !state.file;
    els.demoBtn.disabled = busy;
    els.fileInput.disabled = busy;
}

function formatBytes(bytes) {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
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

function pickFile(file) {
    if (!file) return;
    const lower = file.name.toLowerCase();
    if (!lower.endsWith('.srt') && !lower.endsWith('.vtt')) {
        setMessage('只支持 .srt / .vtt 字幕文件。', true);
        return;
    }
    if (file.size > 50 * 1024 * 1024) {
        setMessage('字幕文件不能超过 50 MB。', true);
        return;
    }
    state.file = file;
    els.fileName.textContent = `${file.name} · ${formatBytes(file.size)}`;
    els.fileName.hidden = false;
    els.dropTitle.textContent = '字幕已就位';
    els.dropHint.textContent = '点击可重新选择';
    if (!els.titleInput.value.trim()) {
        els.titleInput.value = file.name.replace(/\.(srt|vtt)$/i, '');
    }
    els.submitBtn.disabled = false;
    els.retryBtn.hidden = true;
    setMessage('');
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
    if (!state.file) {
        setMessage('先选择一份 SRT / VTT 字幕。', true);
        return;
    }
    resetForNewTask();
    setBusy(true);
    setMessage('正在提交任务…');

    const form = new FormData();
    form.append('file', state.file);
    form.append('title', els.titleInput.value.trim() || state.file.name.replace(/\.(srt|vtt)$/i, ''));

    try {
        const response = await fetch('/api/tasks', {
            method: 'POST',
            headers: { 'X-User-Id': getUserId() },
            body: form,
        });

        if (response.status === 429) {
            const retryAfter = response.headers.get('Retry-After') || '几';
            setMessage(`提交太频繁了，请 ${retryAfter} 秒后再试。`, true);
            setBusy(false);
            return;
        }

        const payload = await response.json().catch(() => ({}));
        if (!response.ok) {
            setMessage(payload.error || `提交失败（HTTP ${response.status}）`, true);
            setBusy(false);
            return;
        }

        state.taskId = payload.id;
        state.task = payload;
        els.stageBlock.hidden = false;
        setStage(payload);
        setMessage('任务已提交，正在后台处理。');
        await pollTask(payload.id);
    } catch (err) {
        setMessage(`网络异常：${err.message}`, true);
        setBusy(false);
    }
}

async function pollTask(taskId) {
    state.polling = true;
    while (state.polling) {
        try {
            const response = await fetch(`/api/tasks/${taskId}`, { cache: 'no-store' });
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }
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
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
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
        if (!response.ok) {
            throw new Error(payload.error || `HTTP ${response.status}`);
        }
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
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
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
        // 指标只是辅助信息，接口未就绪时保持 “—”
    }
}

function bindEvents() {
    els.heroUpload.addEventListener('click', () => {
        document.querySelector('#workbench').scrollIntoView({ behavior: 'smooth', block: 'start' });
        els.fileInput.click();
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
        const file = new File([SAMPLE_SRT], 'thread-pool-demo.srt', { type: 'text/plain' });
        pickFile(file);
        els.titleInput.value = '线程池讲解';
        submit();
    });
}

bindEvents();
loadEval();

// 供演示/无交互截图使用：/?demo=1 自动跑一遍示例字幕
if (new URLSearchParams(window.location.search).get('demo') === '1') {
    setTimeout(() => els.demoBtn.click(), 500);
}
