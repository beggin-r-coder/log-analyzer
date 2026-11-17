package com.anishka.log_analyzer.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts stored database passwords (AES-256-GCM) with a key kept in a separate file next to the data,
 * readable only by the current user. Keeps passwords out of the database file and backups in plain text.
 */
@Component
public class SecretBox {

	private final Path keyFile;
	private volatile SecretKeySpec key;
	private final SecureRandom random = new SecureRandom();

	public SecretBox(@Value("${DATA_DIR:./data}") String dataDir) {
		this.keyFile = Path.of(dataDir).resolve("secret.key");
	}

	public String encrypt(String plain) {
		if (plain == null || plain.isEmpty()) {
			return null;
		}
		try {
			byte[] iv = new byte[12];
			random.nextBytes(iv);
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
			byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			byte[] out = new byte[iv.length + ct.length];
			System.arraycopy(iv, 0, out, 0, iv.length);
			System.arraycopy(ct, 0, out, iv.length, ct.length);
			return "v1:" + Base64.getEncoder().encodeToString(out);
		} catch (GeneralSecurityException | IOException e) {
			throw new IllegalStateException("Could not encrypt the password", e);
		}
	}

	public String decrypt(String stored) {
		if (stored == null || !stored.startsWith("v1:")) {
			return null;
		}
		try {
			byte[] in = Base64.getDecoder().decode(stored.substring(3));
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, in, 0, 12));
			return new String(c.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
			throw new IllegalStateException("Could not decrypt the saved password; enter it again", e);
		}
	}

	private SecretKeySpec key() throws IOException {
		if (key == null) {
			synchronized (this) {
				if (key == null) {
					if (!Files.exists(keyFile)) {
						byte[] k = new byte[32];
						random.nextBytes(k);
						Files.createDirectories(keyFile.getParent());
						Files.write(keyFile, k);
						try {
							Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
						} catch (UnsupportedOperationException e) {
							// Windows: the file is in the user's own profile folder
						}
					}
					key = new SecretKeySpec(Files.readAllBytes(keyFile), "AES");
				}
			}
		}
		return key;
	}
}
