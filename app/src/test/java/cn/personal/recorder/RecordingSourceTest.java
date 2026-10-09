package cn.personal.recorder;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Checks the phone reader against real authenticated protocol files, including random seeks. */
public class RecordingSourceTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private final String session = UUID.randomUUID().toString();
    private final SecretKey key = new SecretKeySpec(new byte[32], "AES");
    private File chunk(int index, byte[] audio, boolean complete) throws Exception {
        String name = session + "_" + String.format(java.util.Locale.ROOT, "%08d", index) + ".enc";
        JSONObject meta = new JSONObject().put("version", 1).put("session", session).put("index", index)
            .put("startedAt", 1700000000000L).put("codec", "aac-adts").put("sampleRate", 16000)
            .put("channels", 1).put("samples", complete ? 0 : 480256).put("final", complete);
        byte[] json = meta.toString().getBytes(StandardCharsets.UTF_8);
        byte[] plain = ByteBuffer.allocate(8 + json.length + audio.length).putInt(0x45414131).putInt(json.length).put(json).put(audio).array();
        byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8)); byte[] encrypted = cipher.doFinal(plain);
        File file = new File(folder.getRoot(), name);
        Files.write(file.toPath(), ByteBuffer.allocate(16 + encrypted.length).putInt(0x45523031).put(nonce).put(encrypted).array());
        return file;
    }
    @Test public void seekAndExportAcrossSliceBoundaries() throws Exception {
        byte[] first = new byte[240000], second = new byte[239873];
        new SecureRandom().nextBytes(first); new SecureRandom().nextBytes(second);
        byte[] expected = new byte[first.length + second.length]; System.arraycopy(first, 0, expected, 0, first.length); System.arraycopy(second, 0, expected, first.length, second.length);
        try (RecordingSource source = new RecordingSource(new File[]{chunk(0, first, false), chunk(1, second, false), chunk(2, new byte[0], true)}, key)) {
            assertTrue(source.complete); assertEquals(expected.length, source.getSize()); assertEquals(960512, source.samples);
            for (int position : new int[]{0, 239999, 240000, 470000, 17, 100000}) {
                byte[] result = new byte[65536]; int n = source.readAt(position, result, 0, result.length);
                assertArrayEquals(Arrays.copyOfRange(expected, position, position + n), Arrays.copyOf(result, n));
            }
            byte[] exported = new byte[expected.length]; long position = 0; byte[] block = new byte[65536]; int count;
            while ((count = source.readAt(position, block, 0, block.length)) != -1) { System.arraycopy(block, 0, exported, (int) position, count); position += count; }
            assertArrayEquals(expected, exported); assertEquals(-1, source.readAt(expected.length, block, 0, 10));
            assertEquals(0, source.readAt(expected.length, block, 0, 0));
        }
    }
    @Test public void incompleteButContinuousAudioCanBeRecovered() throws Exception {
        try (RecordingSource source = new RecordingSource(new File[]{chunk(0, new byte[]{1, 2, 3}, false)}, key)) {
            assertFalse(source.complete); assertEquals(3, source.getSize());
        }
    }
    @Test public void missingSliceIsRejected() throws Exception {
        File a = chunk(0, new byte[]{1}, false), b = chunk(2, new byte[]{2}, false);
        assertThrows(IOException.class, () -> new RecordingSource(new File[]{a, b}, key));
    }
    @Test public void tamperedCiphertextAndWrongKeyAreRejected() throws Exception {
        File a = chunk(0, new byte[]{1}, false);
        SecretKey other = new SecretKeySpec(new byte[]{1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0}, "AES");
        assertThrows(IOException.class, () -> new RecordingSource(new File[]{a}, other));
        byte[] bytes = Files.readAllBytes(a.toPath()); bytes[bytes.length - 1] ^= 1; Files.write(a.toPath(), bytes);
        assertThrows(IOException.class, () -> new RecordingSource(new File[]{a}, key));
    }
    @Test public void renamedCiphertextAndDataAfterFinalAreRejected() throws Exception {
        File a = chunk(0, new byte[]{1}, false); File renamed = new File(folder.getRoot(), session + "_00000001.enc");
        Files.copy(a.toPath(), renamed.toPath()); assertThrows(IOException.class, () -> new RecordingSource(new File[]{renamed}, key));
        File end = chunk(1, new byte[0], true), extra = chunk(2, new byte[]{2}, false);
        assertThrows(IOException.class, () -> new RecordingSource(new File[]{a, end, extra}, key));
    }
    @Test public void closedReaderRejectsFurtherAccess() throws Exception {
        RecordingSource source = new RecordingSource(new File[]{chunk(0, new byte[]{1}, false)}, key); source.close();
        assertThrows(IOException.class, () -> source.readAt(0, new byte[1], 0, 1));
    }
}
