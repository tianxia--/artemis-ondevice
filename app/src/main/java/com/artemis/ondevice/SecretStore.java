package com.artemis.ondevice;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Provider secrets (API keys).
 *
 * Values are encrypted with an AES-GCM key that lives in the Android Keystore and never
 * leaves it; only the ciphertext is written to SharedPreferences. Refs are named
 * {@code provider:<id>}. Secret values are never logged or returned to the UI.
 */
final class SecretStore {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "artemis_agent_secrets";
    private static final String PREFS = "artemis_agent_secrets";

    private SecretStore() {}

    static String apiKeyRef(String providerId) {
        return "provider:" + providerId;
    }

    static synchronized void set(Context ctx, String ref, String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] ct = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        String packed = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(ct, Base64.NO_WRAP);
        prefs(ctx).edit().putString(ref, packed).apply();
    }

    /** Decrypted value, or null when nothing is stored. Throws when the stored value is unreadable. */
    static synchronized String get(Context ctx, String ref) throws Exception {
        String packed = prefs(ctx).getString(ref, null);
        if (packed == null) return null;
        String[] parts = packed.split(":", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(),
                new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    static boolean has(Context ctx, String ref) {
        return prefs(ctx).contains(ref);
    }

    static void delete(Context ctx, String ref) {
        prefs(ctx).edit().remove(ref).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return gen.generateKey();
    }
}
