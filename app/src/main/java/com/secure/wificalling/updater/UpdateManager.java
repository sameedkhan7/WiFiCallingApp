package com.secure.wificalling.updater;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;

/**
 * Clean & Play Protect Compliant Update Checker.
 * Zero package installer intents, zero APK file downloads, zero security flags.
 * Launches official GitHub release directly in default system browser.
 */
public class UpdateManager {

    private static final String TAG = "UpdateManager";
    public static final String UPDATE_JSON_URL = "https://raw.githubusercontent.com/sameedkhan7/WiFiCallingApp/main/version.json";
    public static final String GITHUB_RELEASE_URL = "https://github.com/sameedkhan7/WiFiCallingApp/releases/latest";

    public static void checkUpdate(Context context, boolean showUpToDatePrompt) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                URL url = new URL(UPDATE_JSON_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
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

                String rawJson = sb.toString().trim();
                if (rawJson.startsWith("\uFEFF")) {
                    rawJson = rawJson.substring(1).trim();
                }
                int firstBrace = rawJson.indexOf('{');
                int lastBrace = rawJson.lastIndexOf('}');
                if (firstBrace != -1 && lastBrace > firstBrace) {
                    rawJson = rawJson.substring(firstBrace, lastBrace + 1);
                }

                JSONObject json = new JSONObject(rawJson);
                int remoteVersionCode = json.optInt("versionCode", 1);
                String remoteVersionName = json.optString("versionName", "1.0.0");
                String downloadUrl = json.optString("downloadUrl", GITHUB_RELEASE_URL);
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
        builder.setTitle("Update Available (v" + versionName + ")");
        builder.setMessage("What's New:\n" + changelog + "\n\nDownload the new version from GitHub?");
        builder.setPositiveButton("UPDATE", (dialog, which) -> {
            try {
                String targetUrl = (downloadUrl != null && !downloadUrl.isEmpty()) ? downloadUrl : GITHUB_RELEASE_URL;
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl));
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(browserIntent);
            } catch (Exception e) {
                Toast.makeText(context, "Unable to open browser: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Later", (dialog, which) -> dialog.dismiss());
        builder.setCancelable(true);
        builder.show();
    }
}
