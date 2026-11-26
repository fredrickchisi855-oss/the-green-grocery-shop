package zw.fxdevelopment.greengrocery;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class CloudSyncManager {
    interface Listener { void onSyncFinished(); }

    private static final String API = "https://dawn-violet-34da.the-green-grocery-shop.workers.dev";
    private static final String KEY_ALIAS = "green_grocery_cloud_token_v1";
    private final Context context;
    private final ShopDatabase database;
    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Listener listener;

    CloudSyncManager(Context context, ShopDatabase database) {
        this.context = context.getApplicationContext();
        this.database = database;
        this.prefs = this.context.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE);
    }

    void setListener(Listener listener) { this.listener = listener; }

    String pairDevice(String code, String label) {
        try {
            JSONObject body = new JSONObject();
            body.put("code", code == null ? "" : code.trim());
            body.put("device_label", label == null || label.trim().isEmpty() ? "Android POS" : label.trim());
            JSONObject result = request("POST", "/api/pair", body, null);
            String token = result.optString("token", "");
            if (!result.optBoolean("paired", false) || token.length() < 32) {
                return new JSONObject().put("ok", false).put("message", "Pairing was not accepted").toString();
            }
            storeToken(token);
            database.queueCurrentStateForSync();
            prefs.edit().putString("shop_id", result.optString("shop_id", "")).putString("device_id", result.optString("device_id", "")).putString("status", "Paired; syncing").apply();
            requestSync();
            return new JSONObject().put("ok", true).put("message", "Device paired with Cloudflare").toString();
        } catch (Exception e) {
            setStatus("Pairing failed: " + safeMessage(e));
            try { return new JSONObject().put("ok", false).put("message", safeMessage(e)).toString(); }
            catch (Exception ignored) { return "{}"; }
        }
    }


    String authenticateOnline(String username, String secret) {
        try {
            JSONObject body = new JSONObject();
            body.put("username", username == null ? "" : username.trim());
            body.put("secret", secret == null ? "" : secret);
            JSONObject result = request("POST", "/api/auth/login", body, null);
            String token = result.optString("token", "");
            JSONObject user = result.optJSONObject("user");
            if (!result.optBoolean("authenticated", false) || token.length() < 32 || user == null) return null;
            // On a fresh phone, pull the shop snapshot before the UI writes local defaults.
            // If this phone has unsynced offline work, preserve it; requestSync will push it first.
            if (database.pendingSyncCount() == 0) {
                JSONObject pulled = request("GET", "/api/sync/pull", null, token);
                JSONArray records = pulled.optJSONArray("records");
                if (records != null) database.applyRemoteSyncRecords(records.toString());
            }
            if (!database.importRemoteAccount(user.toString())) return null;
            storeToken(token);
            prefs.edit().putString("shop_id", result.optString("shop_id", ""))
                .putString("device_id", result.optString("device_id", ""))
                .putString("status", "Signed in; synced").apply();
            requestSync();
            return user.toString();
        } catch (Exception e) {
            setStatus("Offline or cloud sign-in unavailable");
            return null;
        }
    }

    String enrollOwner(String username, String displayName, String password) {
        try {
            String token = loadToken();
            if (token == null) return new JSONObject().put("ok", false).put("message", "This phone is not paired with Cloudflare. Administrator registration is available only on the existing paired shop device.").toString();
            JSONObject body = new JSONObject();
            body.put("username", username == null ? "" : username.trim());
            body.put("display_name", displayName == null ? "" : displayName.trim());
            body.put("password", password == null ? "" : password);
            JSONObject result = request("POST", "/api/owner/enroll", body, token);
            return new JSONObject().put("ok", result.optBoolean("enrolled", false))
                .put("message", result.optString("message", result.optString("error", "Cloudflare Administrator registration failed"))).toString();
        } catch (Exception e) {
            try { return new JSONObject().put("ok", false).put("message", safeMessage(e)).toString(); }
            catch (Exception ignored) { return "{}"; }
        }
    }

    String ownerRegistrationStatus() {
        try {
            JSONObject result = request("GET", "/api/shop/status", null, null);
            if (!result.optBoolean("configured", false) || !result.has("owner_registered") || result.isNull("owner_registered"))
                return new JSONObject().put("ok", false).put("message", "Shop registration status could not be verified.").toString();
            return new JSONObject().put("ok", true).put("registered", result.optBoolean("owner_registered", true)).toString();
        } catch (Exception e) {
            try { return new JSONObject().put("ok", false).put("message", "Cannot verify shop registration while offline.").toString(); }
            catch (Exception ignored) { return "{}"; }
        }
    }

    String changeOwnerPassword(String username, String verifier) {
        try {
            String token = loadToken();
            if (token == null) return new JSONObject().put("ok", false).put("message", "Connect this authorised shop device to the internet before changing the Administrator password.").toString();
            JSONObject body = new JSONObject();
            body.put("username", username == null ? "" : username.trim());
            body.put("password_verifier", verifier == null ? "" : verifier);
            JSONObject result = request("POST", "/api/owner/password", body, token);
            return new JSONObject().put("ok", result.optBoolean("changed", false))
                .put("message", result.optString("message", result.optString("error", "Password update failed."))).toString();
        } catch (Exception e) {
            try { return new JSONObject().put("ok", false).put("message", safeMessage(e)).toString(); }
            catch (Exception ignored) { return "{}"; }
        }
    }

    boolean isPaired() { return loadToken() != null; }

    void requestSync() {
        if (loadToken() == null || !running.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                String token = loadToken();
                if (token == null) return;
                String ownerJson=database.pendingOwnerEnrollment();
                if(ownerJson!=null) {
                    JSONObject owner=new JSONObject(ownerJson);
                    JSONObject enrollment=new JSONObject();
                    enrollment.put("username",owner.getString("username"));
                    enrollment.put("display_name",owner.getString("display_name"));
                    enrollment.put("password_salt",owner.getString("password_salt"));
                    enrollment.put("password_hash",owner.getString("password_hash"));
                    enrollment.put("password_iterations",owner.getInt("password_iterations"));
                    JSONObject enrolled=request("POST","/api/owner/enroll",enrollment,token);
                    if(enrolled.optBoolean("enrolled",false)) database.finishOwnerEnrollment(owner.getString("id"),true);
                    else throw new IllegalStateException(enrolled.optString("error","Cloudflare Administrator registration rejected"));
                }
                JSONArray pending = new JSONArray(database.pendingSyncOperations());
                if (pending.length() > 0) {
                    JSONObject push = new JSONObject();
                    push.put("operations", pending);
                    JSONObject pushed = request("POST", "/api/sync/push", push, token);
                    JSONArray results = pushed.optJSONArray("results");
                    if (results != null) {
                        for (int i = 0; i < results.length(); i++) {
                            JSONObject r = results.optJSONObject(i);
                            if (r == null) continue;
                            String opId = r.optString("operation_id", "");
                            String status = r.optString("status", "");
                            int version = r.optInt("version", r.optInt("current_version", 0));
                            database.finishSyncOperation(opId, status, version,
                                status.equals("CONFLICT") ? "Cloud record changed on another device" : r.optString("reason", "Sync rejected"),
                                findSentPayload(pending, opId));
                        }
                    }
                }
                JSONObject pulled = request("GET", "/api/sync/pull", null, token);
                JSONArray records = pulled.optJSONArray("records");
                if (records != null) database.applyRemoteSyncRecords(records.toString());
                setStatus("Synced");
                prefs.edit().putLong("last_sync_ms", System.currentTimeMillis()).apply();
            } catch (Exception e) {
                setStatus("Offline or sync error: " + safeMessage(e));
            } finally {
                running.set(false);
                Listener current = listener;
                if (current != null) current.onSyncFinished();
            }
        });
    }

    String statusJson() {
        try {
            JSONObject out = new JSONObject();
            boolean paired = loadToken() != null;
            out.put("paired", paired);
            out.put("tokenStored", prefs.contains("token_enc"));
            out.put("deviceId", prefs.getString("device_id", ""));
            out.put("pending", database.pendingSyncCount());
            out.put("conflicts", database.syncConflictCount());
            out.put("lastSync", prefs.getLong("last_sync_ms", 0));
            out.put("status", prefs.getString("status", paired ? "Paired; waiting to sync" : "Not connected"));
            return out.toString();
        } catch (Exception e) { return "{\"paired\":false,\"pending\":0,\"conflicts\":0}"; }
    }

    private static String findSentPayload(JSONArray operations,String id) {
        for (int i=0;i<operations.length();i++) {
            JSONObject operation=operations.optJSONObject(i);
            if (operation!=null && id.equals(operation.optString("operation_id"))) {
                JSONObject payload=operation.optJSONObject("payload");
                return payload==null ? "{}" : payload.toString();
            }
        }
        return "{}";
    }

    private JSONObject request(String method, String path, JSONObject body, String token) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(API + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("Accept", "application/json");
            if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 200 && status < 400 ? connection.getInputStream() : connection.getErrorStream();
            String response = readAll(input);
            JSONObject parsed = response.isEmpty() ? new JSONObject() : new JSONObject(response);
            if (status < 200 || status >= 300) throw new IllegalStateException(parsed.optString("error", "Cloudflare returned HTTP " + status));
            return parsed;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void storeToken(String token) throws Exception {
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] packed = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, packed, 0, iv.length);
        System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
        prefs.edit().putString("token_enc", Base64.encodeToString(packed, Base64.NO_WRAP)).apply();
    }

    private String loadToken() {
        try {
            String encoded = prefs.getString("token_enc", null);
            if (encoded == null) return null;
            byte[] packed = Base64.decode(encoded, Base64.NO_WRAP);
            if (packed.length < 29) return null;
            byte[] iv = new byte[12];
            byte[] encrypted = new byte[packed.length - iv.length];
            System.arraycopy(packed, 0, iv, 0, iv.length);
            System.arraycopy(packed, iv.length, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build());
        return generator.generateKey();
    }

    private void setStatus(String status) { prefs.edit().putString("status", status).apply(); }
    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "Connection unavailable";
        return message.length() > 120 ? message.substring(0, 120) : message;
    }
}
