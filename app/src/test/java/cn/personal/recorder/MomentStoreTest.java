package cn.personal.recorder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class MomentStoreTest {
    @Test public void marksKeepRecordingOffsetsAndIgnoreRapidDuplicates() throws Exception {
        JSONObject doc = new JSONObject().put("marks", new JSONArray());
        assertEquals(1, MomentStore.append(doc, 4500));
        assertEquals(1, MomentStore.append(doc, 4900));
        assertEquals(2, MomentStore.append(doc, 5600));
        assertEquals(4500, doc.getJSONArray("marks").getJSONObject(0).getLong("offsetMs"));
        assertEquals(5600, doc.getJSONArray("marks").getJSONObject(1).getLong("offsetMs"));
        try { MomentStore.append(doc, -1); fail("negative offset accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("尚未开始")); }
    }
    @Test public void encryptedMarkCannotBeOpenedAsAnotherSession() throws Exception {
        byte[] keyBytes = new byte[32];
        SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
        String name = "moment-v1:10000000-0000-4000-8000-000000000001.enc";
        byte[] payload = "{\"marks\":[{\"offsetMs\":4500}]}".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = TextCrypto.encrypt(payload, key, name);
        assertArrayEquals(payload, TextCrypto.decrypt(encrypted, key, name));
        try {
            TextCrypto.decrypt(encrypted, key, "moment-v1:10000000-0000-4000-8000-000000000002.enc");
            fail("cross-session ciphertext accepted");
        } catch (javax.crypto.AEADBadTagException expected) { /* File name is authenticated. */ }
    }
}
