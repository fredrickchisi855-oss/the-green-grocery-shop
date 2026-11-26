package zw.fxdevelopment.greengrocery;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class RemoteUiManager {
    interface Listener { void onUpdateChecked(String status); }
    private static final String BASE = "https://green-grocery-shop-app.pages.dev/";
    private static final int BRIDGE_API = 1;
    private static final String[] REQUIRED_FILES = {"index.html", "offline.css", "brand_logo.jpg"};
    private final File root, current;
    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    RemoteUiManager(Context context) {
        Context app = context.getApplicationContext();
        root = new File(app.getFilesDir(), "remote-ui");
        current = new File(root, "current");
        prefs = app.getSharedPreferences("remote_ui", Context.MODE_PRIVATE);
    }

    File getActiveIndexFile() {
        File index = new File(current, "index.html");
        return index.isFile() ? index : null;
    }

    void checkForUpdates(Listener listener) {
        executor.execute(() -> {
            String status;
            try {
                JSONObject manifest = getJson(BASE + "manifest.json");
                if (manifest.optInt("bridge_api", -1) != BRIDGE_API)
                    throw new IllegalStateException("Dashboard bridge version is incompatible");
                String version = manifest.optString("version", "");
                if (version.isEmpty()) throw new IllegalStateException("Invalid dashboard version");
                if (version.equals(prefs.getString("active_version", "")) && getActiveIndexFile() != null) {
                    status = "Dashboard is up to date";
                } else {
                    install(manifest, version);
                    status = "Dashboard update ready for next launch";
                }
            } catch (Exception error) {
                status = getActiveIndexFile() != null
                    ? "Using saved dashboard; update check unavailable"
                    : "Using built-in dashboard; update check unavailable";
            }
            if (listener != null) listener.onUpdateChecked(status);
        });
    }

    private void install(JSONObject manifest, String version) throws Exception {
        JSONObject files = manifest.optJSONObject("files");
        if (files == null) throw new IllegalStateException("Missing asset list");
        if (!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("Cannot create update folder");
        File staging = new File(root, "staging"), backup = new File(root, "backup");
        deleteTree(staging);
        if (!staging.mkdirs()) throw new IllegalStateException("Cannot create staging folder");
        for (String name : REQUIRED_FILES) {
            JSONObject entry = files.optJSONObject(name);
            if (entry == null) throw new IllegalStateException("Missing asset " + name);
            String expectedHash = entry.optString("sha256", "").toLowerCase(Locale.US);
            long expectedSize = entry.optLong("size", -1);
            if (!expectedHash.matches("[a-f0-9]{64}") || expectedSize < 1 || expectedSize > 5000000L)
                throw new IllegalStateException("Invalid asset metadata");
            byte[] bytes = download(BASE + name, expectedSize);
            if (!expectedHash.equals(sha256(bytes))) throw new IllegalStateException("Asset integrity check failed");
            writeFile(new File(staging, name), bytes);
        }
        writeFile(new File(staging, "version.txt"), version.getBytes("UTF-8"));
        deleteTree(backup);
        boolean hadCurrent = current.isDirectory();
        if (hadCurrent && !current.renameTo(backup)) throw new IllegalStateException("Cannot preserve active dashboard");
        if (!staging.renameTo(current)) {
            if (hadCurrent) backup.renameTo(current);
            throw new IllegalStateException("Cannot activate dashboard update");
        }
        deleteTree(backup);
        prefs.edit().putString("active_version", version).apply();
    }

    private JSONObject getJson(String address) throws Exception {
        return new JSONObject(new String(download(address, 256000L), "UTF-8"));
    }

    private byte[] download(String address, long maxBytes) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(address);
            if (!"https".equalsIgnoreCase(url.getProtocol()) || !BASE.startsWith("https://"))
                throw new IllegalArgumentException("Secure HTTPS required");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(4500);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "*/*");
            int status = connection.getResponseCode();
            if (status != 200) throw new IllegalStateException("Update server HTTP " + status);
            if (connection.getContentLengthLong() > maxBytes) throw new IllegalStateException("Asset too large");
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > maxBytes) throw new IllegalStateException("Asset too large");
                    out.write(buffer, 0, count);
                }
                return out.toByteArray();
            }
        } finally { if (connection != null) connection.disconnect(); }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format(Locale.US, "%02x", b & 255));
        return out.toString();
    }

    private static void writeFile(File file, byte[] bytes) throws Exception {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("Cannot create asset folder");
        try (OutputStream out = new FileOutputStream(file)) { out.write(bytes); out.flush(); }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }
}