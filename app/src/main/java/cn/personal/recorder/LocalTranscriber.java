package cn.personal.recorder;

import android.content.Context;
import com.k2fsa.sherpa.onnx.*;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline diarization and ASR. Voice prototypes are discarded at the end of each recording. */
final class LocalTranscriber {
    static JSONObject transcribe(Context c, RecordingLibrary.Entry entry, int speakerCount, BooleanSupplier cancelled, Consumer<String> progress) throws Exception {
        if (speakerCount != -1 && (speakerCount < 1 || speakerCount > 20)) throw new java.io.IOException("发言人数需为 1～20 或自动判断");
        ModelManager.verify(c); OfflineRecognizer recognizer = null; OfflineSpeakerDiarization diarizer = null; SpeakerEmbeddingExtractor embeddings = null;
        try (RecordingSource source = new RecordingSource(entry.chunks, new Vault(c).recordingKey()); SpeakerRegistry speakers = new SpeakerRegistry(speakerCount == -1 ? 40 : speakerCount)) {
            OfflineSenseVoiceModelConfig sense = new OfflineSenseVoiceModelConfig(); sense.setModel(ModelManager.path(c, "asr.onnx"));
            sense.setLanguage("auto"); sense.setUseInverseTextNormalization(true);
            OfflineModelConfig model = new OfflineModelConfig(); model.setSenseVoice(sense); model.setTokens(ModelManager.path(c, "tokens.txt")); model.setNumThreads(2);
            OfflineRecognizerConfig rc = new OfflineRecognizerConfig(); rc.setModelConfig(model); recognizer = new OfflineRecognizer(null, rc);
            SpeakerEmbeddingExtractorConfig ec = new SpeakerEmbeddingExtractorConfig(ModelManager.path(c, "embedding.onnx"), 2, false, "cpu");
            embeddings = new SpeakerEmbeddingExtractor(null, ec);
            OfflineSpeakerSegmentationPyannoteModelConfig pyannote = new OfflineSpeakerSegmentationPyannoteModelConfig(ModelManager.path(c, "segmentation.onnx"), 0.1f);
            OfflineSpeakerSegmentationModelConfig sm = new OfflineSpeakerSegmentationModelConfig(pyannote, 2, false, "cpu");
            OfflineSpeakerDiarizationConfig dc = new OfflineSpeakerDiarizationConfig(sm, ec, new FastClusteringConfig(speakerCount, 0.5f, false), 0.2f, 0.5f);
            diarizer = new OfflineSpeakerDiarization(null, dc);
            JSONObject doc = Transcript.create(entry.id, source.complete); JSONArray lines = doc.getJSONArray("segments"); long offset = 0;
            try (PcmDecoder pcm = new PcmDecoder(source)) {
                while (true) {
                    check(cancelled); float[] batch = pcm.read(300 * 16000, cancelled); if (batch.length == 0) break;
                    try {
                        progress.accept("本地区分发言人 · " + Transcript.time(offset / 16000.0));
                        OfflineSpeakerDiarizationSegment[] segments = diarizer.process(batch); check(cancelled);
                        Arrays.sort(segments, Comparator.comparingDouble(OfflineSpeakerDiarizationSegment::getStart));
                        Map<Integer, OfflineSpeakerDiarizationSegment> representatives = new java.util.LinkedHashMap<>();
                        for (OfflineSpeakerDiarizationSegment s : segments) {
                            OfflineSpeakerDiarizationSegment previous = representatives.get(s.getSpeaker());
                            if (previous == null || s.getEnd() - s.getStart() > previous.getEnd() - previous.getStart()) representatives.put(s.getSpeaker(), s);
                        }
                        Map<Integer, Integer> identities = new HashMap<>();
                        for (Map.Entry<Integer, OfflineSpeakerDiarizationSegment> rep : representatives.entrySet()) {
                            check(cancelled); OfflineSpeakerDiarizationSegment s = rep.getValue();
                            int from = Math.max(0, (int) (s.getStart() * 16000)), to = Math.min(batch.length, (int) (s.getEnd() * 16000));
                            float[] sample = Arrays.copyOfRange(batch, from, Math.min(to, from + 10 * 16000)); OnlineStream stream = embeddings.createStream();
                            float[] vector = null;
                            try {
                                stream.acceptWaveform(sample, 16000); stream.inputFinished();
                                if (!embeddings.isReady(stream)) identities.put(rep.getKey(), -1);
                                else { vector = embeddings.compute(stream); identities.put(rep.getKey(), speakers.assign(vector)); }
                            } finally { stream.release(); Arrays.fill(sample, 0); if (vector != null) Arrays.fill(vector, 0); }
                        }
                        for (OfflineSpeakerDiarizationSegment s : segments) {
                            int from = Math.max(0, (int) (s.getStart() * 16000)), end = Math.min(batch.length, (int) (s.getEnd() * 16000));
                            boolean overlap = false;
                            for (OfflineSpeakerDiarizationSegment other : segments) {
                                if (other.getStart() >= s.getEnd()) break;
                                if (other.getSpeaker() != s.getSpeaker() && other.getEnd() > s.getStart()) { overlap = true; break; }
                            }
                            for (int start = from; start < end; start += 26 * 16000) {
                                check(cancelled); int until = Math.min(end, start + 26 * 16000); float[] sample = Arrays.copyOfRange(batch, start, until);
                                OfflineStream stream = recognizer.createStream(); String text;
                                try { stream.acceptWaveform(sample, 16000); recognizer.decode(stream); text = recognizer.getResult(stream).getText().trim(); }
                                finally { stream.release(); Arrays.fill(sample, 0); }
                                check(cancelled); if (!text.isEmpty()) lines.put(new JSONObject().put("start", (offset + start) / 16000.0).put("end", (offset + until) / 16000.0)
                                    .put("speaker", identities.get(s.getSpeaker())).put("text", text).put("overlap", overlap));
                                progress.accept("本地提取文字 · " + Transcript.time((offset + until) / 16000.0));
                            }
                        }
                        offset += batch.length;
                    } finally { Arrays.fill(batch, 0); }
                }
            }
            if (lines.length() == 0) throw new java.io.IOException("没有识别到可转写的语音");
            java.util.List<JSONObject> ordered = new java.util.ArrayList<>(); for (int i = 0; i < lines.length(); i++) ordered.add(lines.getJSONObject(i));
            ordered.sort(Comparator.comparingDouble(line -> line.optDouble("start"))); doc.put("segments", new JSONArray(ordered));
            doc.put("processedSamples", offset).put("speakerCountHint", speakerCount).put("engine", "SenseVoiceSmall-int8 + pyannote segmentation-3.0 + 3D-Speaker ERes2Net"); return doc;
        } finally { if (recognizer != null) recognizer.release(); if (diarizer != null) diarizer.release(); if (embeddings != null) embeddings.release(); }
    }
    static void check(BooleanSupplier cancelled) throws InterruptedException { if (cancelled.getAsBoolean()) throw new InterruptedException(); }
}
