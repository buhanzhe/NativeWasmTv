package xiao.bu.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.zip.ZipFile;

/** Checks the repository manifest, downloads a newer APK, and opens the system installer. */
final class AutoUpdater {
    private static final String TAG = "AutoUpdater";
    private static final String VERSION_URL = "https://github.com/buhanzhe/NativeWasmTv/"
            + "releases/latest/download/version.json";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final long MAX_APK_BYTES = 256L * 1024L * 1024L;

    private final Activity activity;
    private volatile boolean destroyed;
    private boolean checking;
    private boolean promptShowing;
    private ProgressDialog progressDialog;

    AutoUpdater(Activity activity) {
        this.activity = activity;
    }

    void checkForUpdates() {
        if (checking || destroyed) {
            return;
        }
        checking = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final UpdateInfo update = loadUpdateInfo();
                    if (update.versionCode <= BuildConfig.VERSION_CODE || destroyed) {
                        return;
                    }
                    Log.i(TAG, "Update available: " + update.versionName + ", asset="
                            + BuildConfig.UPDATE_APK_ASSET);
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showUpdatePrompt(update);
                        }
                    });
                } catch (Exception error) {
                    // Startup checks are intentionally silent when the device is offline.
                    Log.w(TAG, "Update check failed", error);
                } finally {
                    checking = false;
                }
            }
        }, "update-check").start();
    }

    void destroy() {
        destroyed = true;
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
    }

    private UpdateInfo loadUpdateInfo() throws IOException, JSONException {
        // API 14/15 use the HTTP accelerator because their TLS stack cannot reach gh-proxy.com.
        HttpURLConnection connection = openConnection(GithubProxy.apply(VERSION_URL)
                + "?_=" + System.currentTimeMillis());
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cache-Control", "no-cache");
        String json;
        try {
            requireSuccessful(connection);
            InputStream input = new BufferedInputStream(connection.getInputStream());
            try {
                json = readUtf8(input, MAX_MANIFEST_BYTES);
            } finally {
                input.close();
            }
        } finally {
            connection.disconnect();
        }

        JSONObject object = new JSONObject(json);
        int versionCode = object.getInt("versionCode");
        String versionName = object.getString("versionName").trim();
        String apkUrl = object.optString(BuildConfig.UPDATE_APK_URL_FIELD, "").trim();
        String sha256 = object.optString(BuildConfig.UPDATE_SHA256_FIELD, "")
                .trim().toLowerCase(Locale.US);
        String releaseNotes = object.optString("releaseNotes", "").trim();
        if (versionCode < 1 || versionName.length() == 0) {
            throw new JSONException("Invalid version metadata");
        }
        if (apkUrl.length() == 0) {
            throw new JSONException("Missing APK URL for " + BuildConfig.UPDATE_APK_ASSET);
        }
        if (!apkUrl.endsWith("/" + BuildConfig.UPDATE_APK_ASSET)) {
            throw new JSONException("Wrong APK URL for " + BuildConfig.UPDATE_APK_ASSET);
        }
        apkUrl = proxiedGithubUrl(apkUrl);
        if (sha256.length() > 0 && !sha256.matches("[0-9a-f]{64}")) {
            throw new JSONException("Invalid APK SHA-256");
        }
        return new UpdateInfo(versionCode, versionName, apkUrl, sha256, releaseNotes);
    }

    private void showUpdatePrompt(final UpdateInfo update) {
        if (destroyed || promptShowing || activity.isFinishing()) {
            return;
        }
        promptShowing = true;
        String notes = update.releaseNotes.length() == 0 ? "包含功能改进和问题修复。"
                : update.releaseNotes;
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.update_available_title, update.versionName))
                .setMessage(activity.getString(R.string.update_available_message,
                        BuildConfig.VERSION_NAME, update.versionName, notes))
                .setPositiveButton(R.string.update_now, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        promptShowing = false;
                        downloadUpdate(update);
                    }
                })
                .setNegativeButton(R.string.update_later, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        promptShowing = false;
                    }
                })
                .create();
        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
                promptShowing = false;
            }
        });
        dialog.show();
    }

    private void downloadUpdate(final UpdateInfo update) {
        progressDialog = new ProgressDialog(activity);
        progressDialog.setTitle(R.string.update_downloading);
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setIndeterminate(true);
        progressDialog.setCancelable(false);
        progressDialog.setMax(100);
        progressDialog.show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                File partial = null;
                try {
                    File directory = ApkFileProvider.updateDirectory(activity);
                    if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
                        throw new IOException("无法创建更新目录");
                    }
                    File apk = new File(directory, BuildConfig.UPDATE_APK_ASSET
                            .replace(".apk", "-" + update.versionCode + ".apk"));
                    if (apk.isFile() && verifySha256(apk, update.sha256) && isApk(apk)) {
                        finishDownload(apk);
                        return;
                    }
                    partial = new File(directory, apk.getName() + ".part");
                    if (partial.exists() && !partial.delete()) {
                        throw new IOException("无法清理旧的临时文件");
                    }
                    download(update.apkUrl, partial);
                    if (!verifySha256(partial, update.sha256)) {
                        throw new IOException("APK 完整性校验失败");
                    }
                    if (!isApk(partial)) {
                        throw new IOException("下载内容不是有效的 APK");
                    }
                    if (apk.exists() && !apk.delete()) {
                        throw new IOException("无法替换旧的更新文件");
                    }
                    if (!partial.renameTo(apk)) {
                        copyFile(partial, apk);
                        if (!partial.delete()) {
                            Log.w(TAG, "Unable to remove partial APK " + partial);
                        }
                    }
                    finishDownload(apk);
                } catch (final Exception error) {
                    Log.e(TAG, "Update download failed", error);
                    if (partial != null && partial.exists() && !partial.delete()) {
                        Log.w(TAG, "Unable to remove failed partial APK " + partial);
                    }
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dismissProgress();
                            if (!destroyed) {
                                Toast.makeText(activity,
                                        activity.getString(R.string.update_download_failed,
                                                readableMessage(error)), Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                }
            }
        }, "update-download").start();
    }

    private void download(String url, File destination) throws IOException {
        HttpURLConnection connection = openConnection(url);
        try {
            requireSuccessful(connection);
            final long length = contentLength(connection);
            if (length > MAX_APK_BYTES) {
                throw new IOException("APK 文件过大");
            }
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (progressDialog != null) {
                        progressDialog.setIndeterminate(length <= 0L);
                    }
                }
            });
            InputStream input = new BufferedInputStream(connection.getInputStream());
            FileOutputStream output = new FileOutputStream(destination);
            try {
                byte[] buffer = new byte[64 * 1024];
                long total = 0L;
                int lastProgress = -1;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (destroyed) {
                        throw new IOException("下载已取消");
                    }
                    total += count;
                    if (total > MAX_APK_BYTES) {
                        throw new IOException("APK 文件过大");
                    }
                    output.write(buffer, 0, count);
                    if (length > 0L) {
                        final int progress = (int) Math.min(100L, total * 100L / length);
                        if (progress != lastProgress) {
                            lastProgress = progress;
                            activity.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (progressDialog != null) {
                                        progressDialog.setProgress(progress);
                                    }
                                }
                            });
                        }
                    }
                }
                output.getFD().sync();
            } finally {
                try {
                    output.close();
                } finally {
                    input.close();
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private void finishDownload(final File apk) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                dismissProgress();
                if (!destroyed) {
                    install(apk);
                }
            }
        });
    }

    private void install(final File apk) {
        launchInstaller(apk);
    }

    private void launchInstaller(File apk) {
        try {
            Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                uri = ApkFileProvider.uriForFile(activity, apk);
            } else {
                apk.setReadable(true, false);
                uri = Uri.fromFile(apk);
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            activity.startActivity(intent);
        } catch (Exception error) {
            Log.e(TAG, "Unable to launch package installer", error);
            Toast.makeText(activity, R.string.update_install_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
    }

    private static HttpURLConnection openConnection(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "nTv/" + BuildConfig.VERSION_NAME
                + " Android/" + Build.VERSION.RELEASE);
        return connection;
    }

    private static void requireSuccessful(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status);
        }
    }

    private static long contentLength(HttpURLConnection connection) {
        String value = connection.getHeaderField("Content-Length");
        if (value == null) {
            return -1L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static String proxiedGithubUrl(String url) throws JSONException {
        try {
            URL parsed = new URL(url);
            String host = parsed.getHost().toLowerCase(Locale.US);
            if (!"https".equalsIgnoreCase(parsed.getProtocol())
                    || !("github.com".equals(host)
                    || "raw.githubusercontent.com".equals(host))) {
                throw new JSONException("APK URL must be an HTTPS GitHub URL");
            }
            return GithubProxy.apply(url);
        } catch (IOException error) {
            throw new JSONException("Invalid APK URL");
        }
    }

    private static String readUtf8(InputStream input, int limit) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[2048];
        int count;
        while ((count = reader.read(buffer)) != -1) {
            if (text.length() + count > limit) {
                throw new IOException("版本文件过大");
            }
            text.append(buffer, 0, count);
        }
        return text.toString();
    }

    private static boolean verifySha256(File file, String expected)
            throws IOException, NoSuchAlgorithmException {
        if (expected.length() == 0) {
            Log.w(TAG, "Update manifest has no SHA-256; APK authenticity is not pinned");
            return true;
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        InputStream input = new BufferedInputStream(new FileInputStream(file));
        try {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        } finally {
            input.close();
        }
        byte[] hash = digest.digest();
        StringBuilder actual = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            actual.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return expected.equals(actual.toString());
    }

    private static boolean isApk(File file) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(file);
            return zip.getEntry("AndroidManifest.xml") != null;
        } catch (IOException error) {
            return false;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void copyFile(File source, File destination) throws IOException {
        InputStream input = new BufferedInputStream(new FileInputStream(source));
        FileOutputStream output = new FileOutputStream(destination);
        try {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        } finally {
            try {
                output.close();
            } finally {
                input.close();
            }
        }
    }

    private static String readableMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.length() == 0
                ? error.getClass().getSimpleName() : message;
    }

    private static final class UpdateInfo {
        final int versionCode;
        final String versionName;
        final String apkUrl;
        final String sha256;
        final String releaseNotes;

        UpdateInfo(int versionCode, String versionName, String apkUrl,
                String sha256, String releaseNotes) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
            this.releaseNotes = releaseNotes;
        }
    }
}
