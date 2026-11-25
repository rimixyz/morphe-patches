package miui.util;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@SuppressWarnings("unused")
public class HashUtils {
    public static String getHash(String content, String method) {
        if (content == null || method == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance(method);
            return toHex(md.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String getSHA1(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            return toHex(md.digest());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
