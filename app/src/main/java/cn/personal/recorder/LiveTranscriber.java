package cn.personal.recorder;

import android.content.Context;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Best-effort offline preview fed by the recording microphone. Never blocks audio capture. */
final class LiveTranscriber {
    static final int RATE = 16000, WINDOW_SAMPLES = RATE * 5;
    private static final int MAX_TEXT_CHARS = 200_000, MAX_VISIBLE_SEGMENTS = 80;
    static final class Snapshot {
        final String session, preview, status;
        final boolean busy;
        Snapshot(String session, String preview, String status, boolean busy) {
            this.session = session; this.preview = preview; this.status = status; this.busy = busy;
        }
    }
    static volatile Snapshot snapshot = new Snapshot("", "", "", false);
    private static volatile boolean busy;
    private final Context context;
    private final String session;
    private final Runnable onFinished;
    private final ArrayBlockingQueue<Window> pending = new ArrayBlockingQueue<>(3);
    private final WindowBuffer buffer = new WindowBuffer(WINDOW_SAMPLES);
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private volatile boolean stopping, complete, skipped, failed;
    private int textChars;

    private LiveTranscriber(Context context, String session, Runnable onFinished) {
        this.context = context.getApplicationContext(); this.session = session; this.onFinished = onFinished;
    }
    static synchronized LiveTranscriber start(Context context, String session, Runnable onFinished) {
        if (busy || !ModelManager.ready(context)) return null;
        LiveTranscriber live = new LiveTranscriber(context, session, onFinished);
        busy = true; snapshot = new Snapshot(session, "", "正在准备本地实时识别…", true);
        Thread worker = new Thread(live::run, "live-text-preview");
        worker.setPriority(Thread.NORM_PRIORITY - 1); worker.start();
        return live;
    }
    static boolean isBusyFor(String session) { return busy && session != null && session.equals(snapshot.session); }
    void accept(byte[] pcm, int count) {
        if (stopping || failed) return;
        buffer.offer(pcm, count, window -> {
            if (!pending.offer(window)) {
                Arrays.fill(window.samples, 0f); skipped = true;
            }
        });
    }
    void finish(boolean complete) {
        this.complete = complete;
        if (!failed) buffer.finish(window -> {
            if (!pending.offer(window)) { Arrays.fill(window.samples, 0f); skipped = true; }
        });
        stopping = true;
    }
    private void run() {
        OfflineRecognizer recognizer = null;
        try {
            ModelManager.verify(context);
            OfflineSenseVoiceModelConfig sense = new OfflineSenseVoiceModelConfig();
            sense.setModel(ModelManager.path(context, "asr.onnx")); sense.setLanguage("auto"); sense.setUseInverseTextNormalization(true);
            OfflineModelConfig model = new OfflineModelConfig(); model.setSenseVoice(sense);
            model.setTokens(ModelManager.path(context, "tokens.txt")); model.setNumThreads(1);
            OfflineRecognizerConfig config = new OfflineRecognizerConfig(); config.setModelConfig(model);
            recognizer = new OfflineRecognizer(null, config);
            JSONObject document = Transcript.create(session, false);
            JSONArray segments = document.getJSONArray("segments");
            publish("正在本机识别，约每 5 秒更新…", true);
            while (!stopping || !pending.isEmpty()) {
                Window window = pending.poll(250, TimeUnit.MILLISECONDS);
                if (window == null) continue;
                try {
                    if (textChars >= MAX_TEXT_CHARS) { publish("实时预览已达上限，录音仍在保存", true); continue; }
                    OfflineStream stream = recognizer.createStream(); String text;
                    try {
                        stream.acceptWaveform(window.samples, RATE); recognizer.decode(stream);
                        text = recognizer.getResult(stream).getText().trim();
                    } finally { stream.release(); }
                    if (!text.isEmpty()) {
                        textChars += text.length();
                        segments.put(new JSONObject().put("start", window.start / (double) RATE)
                            .put("end", window.end / (double) RATE).put("speaker", -1).put("text", text).put("overlap", false));
                        recent.addLast(Transcript.time(window.start / (double) RATE) + "  " + text);
                        while (recent.size() > MAX_VISIBLE_SEGMENTS) recent.removeFirst();
                    }
                    publish(skipped ? "识别跟不上，部分预览已跳过；可在停止后完整转写"
                        : "已识别 " + segments.length() + " 段 · 最多显示最近 " + MAX_VISIBLE_SEGMENTS + " 段", true);
                } finally { Arrays.fill(window.samples, 0f); }
            }
            if (segments.length() > 0) {
                document.put("complete", complete).put("previewSkipped", skipped || textChars >= MAX_TEXT_CHARS)
                    .put("engine", "SenseVoiceSmall-int8 live preview")
                    .put("processedSamples", buffer.samplesSeen());
                TextStore.save(context, document);
                publish(skipped ? "已加密保存部分实时文字；建议重新完整转写" : "实时文字已加密保存 · 可在录音详情中核对", false);
            } else publish("未识别到文字；可在录音详情中完整转写", false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); publish("实时识别已中断；录音密文仍保留", false);
        } catch (Exception | LinkageError | OutOfMemoryError e) {
            failed = true; publish("实时识别未完成；录音仍可在停止后完整转写", false);
        } finally {
            if (recognizer != null) { try { recognizer.release(); } catch (RuntimeException ignored) {} }
            Window window; while ((window = pending.poll()) != null) Arrays.fill(window.samples, 0f);
            busy = false;
            onFinished.run();
        }
    }
    private void publish(String status, boolean working) {
        StringBuilder text = new StringBuilder();
        for (String line : recent) { if (text.length() > 0) text.append('\n'); text.append(line); }
        snapshot = new Snapshot(session, text.toString(), status, working);
    }
    static final class Window {
        final float[] samples;
        final long start, end;
        Window(float[] samples, long start, long end) { this.samples = samples; this.start = start; this.end = end; }
    }
    /** One producer on the audio thread; all output windows are detached from its reusable buffer. */
    static final class WindowBuffer {
        private final int size;
        private float[] values;
        private int used;
        private long seen;
        WindowBuffer(int size) { this.size = size; values = new float[size]; }
        long samplesSeen() { return seen; }
        void offer(byte[] pcm, int count, Consumer<Window> output) {
            for (int i = 0; i + 1 < count; i += 2) {
                int sample = ((pcm[i + 1] & 255) << 8) | (pcm[i] & 255);
                if (sample >= 32768) sample -= 65536;
                values[used++] = sample / 32768f; seen++;
                if (used == size) { output.accept(new Window(values, seen - used, seen)); values = new float[size]; used = 0; }
            }
        }
        void finish(Consumer<Window> output) {
            if (used >= RATE / 2) output.accept(new Window(Arrays.copyOf(values, used), seen - used, seen));
            Arrays.fill(values, 0f); used = 0;
        }
    }
}
