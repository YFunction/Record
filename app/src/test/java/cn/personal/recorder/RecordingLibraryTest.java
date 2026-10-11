package cn.personal.recorder;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class RecordingLibraryTest {
    @Test public void multipleTextRevisionsAppearAsOneRecordingAtTheLatestTime() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String session = UUID.randomUUID().toString();
        String category = UUID.randomUUID().toString();
        File dir = TextStore.directory(context);
        File older = new File(dir, session + "_" + UUID.randomUUID() + ".enc");
        File newest = new File(dir, session + "_" + UUID.randomUUID() + ".enc");
        Files.write(older.toPath(), new byte[]{1});
        Files.write(newest.toPath(), new byte[]{2});
        newest.setLastModified(1_700_000_000_000L);
        Files.write(new File(dir, session + ".latest").toPath(), newest.getName().getBytes(StandardCharsets.UTF_8));
        CategoryStore.State catalog = new CategoryStore.State(
            new JSONArray().put(new JSONObject().put("id", category).put("name", "课堂记录")),
            new JSONObject().put(session, category), category, 1);

        List<RecordingLibrary.Entry> entries = RecordingLibrary.list(context, catalog);
        List<RecordingLibrary.Entry> matching = entries.stream().filter(entry -> entry.id.equals(session)).toList();
        assertEquals(1, matching.size());
        assertEquals("课堂记录", matching.get(0).categoryName);
        assertEquals(newest.lastModified(), matching.get(0).time);
        assertEquals(0, matching.get(0).chunks.length);
    }
}
