package com.example.server.storage.service;

import com.example.server.api.DependencyUnavailableException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class ObjectIntegrityVerifier {

    public IntegrityResult verify(InputStream stream) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            long size = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                size += read;
            }
            return new IntegrityResult(HexFormat.of().formatHex(digest.digest()), size);
        } catch (IOException exception) {
            throw new DependencyUnavailableException("读取对象进行完整性校验失败", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 MD5", exception);
        }
    }
}
