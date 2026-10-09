package cn.personal.recorder;

import android.content.Context;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RecordingLibrary {
    static final class Entry {
        final String id;
        final String categoryId;
        final String categoryName;
        final File[] chunks;
        final long time, bytes;
        final int uploaded;
        Entry(String id, String categoryId, String categoryName, List<File> files) {
            this(id, categoryId, categoryName, files, 0);
        }
        Entry(String id, String categoryId, String categoryName, List<File> files, long textTime) {
            this.id = id; chunks = files.toArray(new File[0]);
            this.categoryId = categoryId; this.categoryName = categoryName;
            long total = 0; int ack = 0;
            for (File file : chunks) { total += file.length(); if (ChunkStore.uploaded(file)) ack++; }
            bytes = total; uploaded = ack; time = chunks.length == 0 ? textTime : chunks[0].lastModified();
        }
    }
    static List<Entry> list(Context context) {
        CategoryStore.State catalog;
        try { catalog = CategoryStore.load(context); } catch (Exception e) { throw new IllegalStateException("无法读取加密分类目录", e); }
        Map<String, List<File>> sessions = new LinkedHashMap<>();
        for (File f : ChunkStore.files(context)) {
            if (!f.getName().matches("[0-9a-f-]{36}_[0-9]{8}\\.enc")) continue;
            sessions.computeIfAbsent(f.getName().substring(0, 36), k -> new ArrayList<>()).add(f);
        }
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, List<File>> e : sessions.entrySet()) { String category = catalog.assigned(e.getKey()); entries.add(new Entry(e.getKey(), category, catalog.name(category), e.getValue())); }
        for (File file : TextStore.files(context)) {
            String id = file.getName().substring(0, 36);
            File latest = TextStore.latest(context, id);
            if (!sessions.containsKey(id) && latest != null) { sessions.put(id, new ArrayList<>()); String category = catalog.assigned(id); entries.add(new Entry(id, category, catalog.name(category), new ArrayList<>(), latest.lastModified())); }
        }
        entries.sort(Comparator.comparingLong((Entry e) -> e.time).reversed());
        return entries;
    }
    static boolean delete(Entry entry) {
        boolean ok = true;
        for (File file : entry.chunks) {
            if (file.exists() && !file.delete()) ok = false;
            if (!file.exists()) {
                File ack = new File(file.getPath() + ".ack");
                if (ack.exists() && !ack.delete()) ok = false;
            }
        }
        return ok;
    }
}
