package com.secure.wificalling.updater;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 1-Click In-App Auto Updater via GitHub Releases / Raw Repo.
 * Zero-cost, unlimited bandwidth, instant in-place updates.
 * Compatible with legacy Android (5.0+) and modern Android (14+).
 */
public class UpdateManager {

    private static final String TAG = "UpdateManager";
    public static final String UPDATE_JSON_URL = "https://raw.githubusercontent.com/sameedkhan7/WiFiCallingApp/main/version.json";

    private static void configureSslForLegacyAndroid(URLConnection conn) {
        if (conn instanceof HttpsURLConnection) {
            try {
                TrustManager[] trustAllCerts = new TrustManager[]{
                        new X509TrustManager() {
                            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                            public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                            public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                        }
                };
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, trustAllCerts, new SecureRandom());
                HttpsURLConnection httpsConn = (HttpsURLConnection) conn;
                httpsConn.setSSLSocketFactory(sc.getSocketFactory());
                httpsConn.setHostnameVerifier((hostname, session) -> true);
            } catch (Exception ignored) {}
        }
    }

    public static void checkUpdate(Context context, boolean showUpToDatePrompt) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                URL url = new URL(UPDATE_JSON_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                configureSslForLegacyAndroid(conn);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestMethod("GET");

                if (conn.getResponseCode() != 200) {
                    if (showUpToDatePrompt) {
                        new Handler(Looper.getMainLooper()).post(() ->
                                Toast.makeText(context, "No update server reachable. You are on the latest version.", Toast.LENGTH_SHORT).show());
                    }
                    return;
                }

                InputStream in = conn.getInputStream();
                byte[] buffer = new byte[2048];
                int read;
                StringBuilder sb = new StringBuilder();
                while ((read = in.read(buffer)) != -1) {
                    sb.append(new String(buffer, 0, read));
                }
                in.close();

                JSONObject json = new JSONObject(sb.toString());
                int remoteVersionCode = json.optInt("versionCode", 1);
                String remoteVersionName = json.optString("versionName", "1.0.0");
                String downloadUrl = json.optString("downloadUrl", "");
                String changelog = json.optString("changelog", "Bug fixes and stability improvements.");

                PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                int currentVersionCode = pInfo.versionCode;

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (remoteVersionCode > currentVersionCode) {
                        showUpdatePrompt(context, remoteVersionName, changelog, downloadUrl);
                    } else if (showUpToDatePrompt) {
                        Toast.makeText(context, "App is up to date! (v" + pInfo.versionName + ")", Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                Log.w(TAG, "Update check failed: " + e.getMessage());
                if (showUpToDatePrompt) {
                    new Handler(Looper.getMainLooper()).post(() ->
                            Toast.makeText(context, "You are on the latest version.", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    private static void showUpdatePrompt(Context context, String versionName, String changelog, String downloadUrl) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("⚡ Update Available (v" + versionName + ")");
        builder.setMessage("What's New:\n" + changelog + "\n\nUpdate now in 1-click without losing any settings?");
        builder.setPositiveButton("UPDATE NOW", (dialog, which) -> downloadAndInstallApk(context, downloadUrl, versionName));
        builder.setNegativeButton("Later", (dialog, which) -> dialog.dismiss());
        builder.setCancelable(true);
        builder.show();
    }

    private static void downloadAndInstallApk(Context context, String downloadUrl, String versionName) {
        ProgressDialog progress = new ProgressDialog(context);
        progress.setTitle("Downloading Update");
        progress.setMessage("Downloading v" + versionName + " update...");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setIndeterminate(false);
        progress.setMax(100);
        progress.setCancelable(false);
        progress.show();

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                URL url = new URL(downloadUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                configureSslForLegacyAndroid(conn);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setInstanceFollowRedirects(true);
                conn.connect();

                int fileLength = conn.getContentLength();
                InputStream input = conn.getInputStream();

                File updateFile = new File(context.getExternalFilesDir(null), "update.apk");
                if (updateFile.exists()) updateFile.delete();

                FileOutputStream output = new FileOutputStream(updateFile);
                byte[] data = new byte[4096];
                long total = 0;
                int count;
                while ((count = input.read(data)) != -1) {
                    total += count;
                    if (fileLength > 0) {
                        int percent = (int) (total * 100 / fileLength);
                        new Handler(Looper.getMainLooper()).post(() -> progress.setProgress(percent));
                    }
                    output.write(data, 0, count);
                }

                output.flush();
                output.close();
                input.close();

                new Handler(Looper.getMainLooper()).post(() -> {
                    progress.dismiss();
                    installApk(context, updateFile);
                });

            } catch (Exception e) {
                Log.e(TAG, "Download APK failed", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    progress.dismiss();
                    Toast.makeText(context, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private static void installApk(Context context, File apkFile) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.getPackageManager().canRequestPackageInstalls()) {
                    Intent manageIntent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                    manageIntent.setData(Uri.parse("package:" + context.getPackageName()));
                    manageIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(manageIntent);
                    Toast.makeText(context, "Please enable 'Allow from this source' to update", Toast.LENGTH_LONG).show();
                    return;
                }
            }

            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri apkUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                apkUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", apkFile);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                apkUri = Uri.fromFile(apkFile);
            }
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Install failed", e);
            Toast.makeText(context, "Install prompt failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
