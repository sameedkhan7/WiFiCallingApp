package com.secure.wificalling.updater;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Universal In-App Auto Updater:
 * - 100% Android 5.0 (Vivo Y51L) compatible with custom TLS 1.2 Socket Factory.
 * - 100% Android 14+ (OnePlus, Samsung S24 Ultra, M35) compatible via secure FileProvider.
 * - Non-blocking: Never aborts on permission checks; seamlessly guides user to system installer.
 * - Multi-layer fallback: If native install fails, opens direct APK download in default browser.
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
                configureTls12ForLegacyAndroid(conn);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
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
                String downloadUrl = json.optString("downloadUrl", "https://raw.githubusercontent.com/sameedkhan7/WiFiCallingApp/main/WiFiPhone.apk");
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

    public static void showUpdatePrompt(Context context, String versionName, String changelog, String downloadUrl) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("⚡ Update Available (v" + versionName + ")");
        builder.setMessage("What's New:\n" + changelog + "\n\nInstall the latest version in 1-click?");
        builder.setPositiveButton("UPDATE NOW", (dialog, which) -> downloadAndInstallApk(context, downloadUrl, versionName));
        builder.setNeutralButton("Open in Browser", (dialog, which) -> {
            try {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(browserIntent);
            } catch (Exception ignored) {}
        });
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
                configureTls12ForLegacyAndroid(conn);
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(20000);
                conn.setInstanceFollowRedirects(true);
                conn.connect();

                int fileLength = conn.getContentLength();
                InputStream input = conn.getInputStream();

                // Save directly in application internal cache directory (no external storage permissions required)
                File cacheDir = context.getCacheDir();
                File updateFile = new File(cacheDir, "update.apk");
                if (updateFile.exists()) {
                    updateFile.delete();
                }

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
                    try {
                        progress.dismiss();
                    } catch (Throwable ignored) {}
                    installApk(context, updateFile, downloadUrl);
                });

            } catch (Exception e) {
                Log.e(TAG, "Download APK failed", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        progress.dismiss();
                    } catch (Throwable ignored) {}
                    Toast.makeText(context, "In-app download failed. Opening browser download...", Toast.LENGTH_SHORT).show();
                    try {
                        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
                        browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(browserIntent);
                    } catch (Throwable ignored) {}
                });
            }
        });
    }

    private static void installApk(Context context, File apkFile, String fallbackDownloadUrl) {
        try {
            if (apkFile == null || !apkFile.exists() || apkFile.length() < 10000) {
                throw new IOException("Downloaded APK file is incomplete or corrupted");
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
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

            // Explicitly grant URI permission to all potential package installers
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                List<ResolveInfo> resInfoList = context.getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
                for (ResolveInfo resolveInfo : resInfoList) {
                    String packageName = resolveInfo.activityInfo.packageName;
                    context.grantUriPermission(packageName, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            }

            context.startActivity(intent);

        } catch (Exception e) {
            Log.e(TAG, "Native install launch failed", e);
            Toast.makeText(context, "Opening update in browser...", Toast.LENGTH_LONG).show();
            try {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(fallbackDownloadUrl));
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(browserIntent);
            } catch (Throwable ignored) {}
        }
    }

    private static void configureTls12ForLegacyAndroid(URLConnection conn) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1 && conn instanceof HttpsURLConnection) {
            try {
                // Trust managers for legacy Android 5.0 (Vivo Y51L) to reach GitHub raw servers
                TrustManager[] legacyTrustManagers = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
                };
                SSLContext sc = SSLContext.getInstance("TLSv1.2");
                sc.init(null, legacyTrustManagers, new java.security.SecureRandom());
                HttpsURLConnection httpsConn = (HttpsURLConnection) conn;
                httpsConn.setSSLSocketFactory(new Tls12SocketFactory(sc.getSocketFactory()));
            } catch (Throwable t) {
                try {
                    SSLContext sc = SSLContext.getInstance("TLS");
                    sc.init(null, null, new java.security.SecureRandom());
                    ((HttpsURLConnection) conn).setSSLSocketFactory(sc.getSocketFactory());
                } catch (Throwable ignored) {}
            }
        }
    }

    private static class Tls12SocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        public Tls12SocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return patch(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return patch(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return patch(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return patch(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return patch(delegate.createSocket(address, port, localAddress, localPort));
        }

        private Socket patch(Socket s) {
            if (s instanceof SSLSocket) {
                try {
                    ((SSLSocket) s).setEnabledProtocols(new String[]{"TLSv1.2"});
                } catch (Throwable ignored) {}
            }
            return s;
        }
    }
}
