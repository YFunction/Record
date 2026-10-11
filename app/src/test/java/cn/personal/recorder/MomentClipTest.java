package cn.personal.recorder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class MomentClipTest {
    @Test public void encryptedClipAuthenticatesMetadataAndAudio() throws Exception {
        byte[] key = new byte[32], audio = { (byte) 0xff, (byte) 0xf1, 0x60, 0x40, 0, (byte) 0xff, (byte) 0xfc };
        JSONObject metadata = new JSONObject().put("version", 1).put("session", "10000000-0000-4000-8000-000000000001")
            .put("markOffsetMs", 1000).put("startMs", 0).put("endMs", 2000).put("codec", "aac-adts");
        byte[] envelope = MomentClip.seal(new SecretKeySpec(key, "AES"), metadata, audio);
        assertEquals("RC01", new String(envelope, 0, 4, StandardCharsets.US_ASCII));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, envelope, 4, 12));
        cipher.updateAAD("record-moment-clip-v1".getBytes(StandardCharsets.UTF_8));
        byte[] plain = cipher.doFinal(envelope, 16, envelope.length - 16);
        int length = ByteBuffer.wrap(plain).getInt();
        JSONObject decoded = new JSONObject(new String(plain, 4, length, StandardCharsets.UTF_8));
        assertEquals(1000, decoded.getLong("markOffsetMs"));
        assertArrayEquals(audio, Arrays.copyOfRange(plain, 4 + length, plain.length));
        envelope[envelope.length - 1] ^= 1;
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, envelope, 4, 12));
        cipher.updateAAD("record-moment-clip-v1".getBytes(StandardCharsets.UTF_8));
        try { cipher.doFinal(envelope, 16, envelope.length - 16); fail("tampering accepted"); }
        catch (javax.crypto.AEADBadTagException expected) { /* authenticated */ }
    }
    @Test public void rejectsBadAdtsFrame() throws Exception {
        byte[] header = AacRecorder.adts(71);
        assertEquals(71, MomentClip.adtsLength(header));
        header[0] = 0;
        try { MomentClip.adtsLength(header); fail("bad ADTS header accepted"); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("帧头")); }
    }
}
