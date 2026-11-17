package com.secure.wificalling.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.secure.wificalling.R;
import com.secure.wificalling.network.CallSignaling;
import com.secure.wificalling.service.CallBackgroundService;
import com.secure.wificalling.updater.UpdateManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Google Dialer Speed-Dial & Contact Management Screen.
 * Prevents self-calling, displays live Wi-Fi vs 4G/5G status,
 * and manages speed-dial contacts.
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQ_CODE = 2001;
    private static final String PREFS_NAME = "WiFiCallingPrefs";
    private static final String KEY_CONTACTS_JSON = "CONTACTS_JSON_V3";

    public static class ContactItem {
        public String ext;      // Extension ID (e.g. "101", "102", "103", "104")
        public String name;     // Display name
        public String query;    // Device model filter
        public String directIp; // Optional static IP

        public ContactItem(String ext, String name, String query, String directIp) {
            this.ext = ext;
            this.name = name;
            this.query = query;
            this.directIp = directIp;
        }
    }

    private SharedPreferences prefs;
    private List<ContactItem> contactList = new ArrayList<>();
    private LinearLayout containerContacts;
    private TextView tvMyDeviceName, tvMyDeviceIp;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private String myExtId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "Uncaught Exception caught safely: ", throwable);
        });

        try {
            setContentView(R.layout.activity_main);
            prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

            myExtId = CallSignaling.detectMyExtensionId();

            tvMyDeviceName = findViewById(R.id.tvMyDeviceName);
            tvMyDeviceIp = findViewById(R.id.tvMyDeviceIp);
            containerContacts = findViewById(R.id.containerContacts);

            tvMyDeviceName.setText("This Phone: " + Build.MODEL + " (Ext: " + myExtId + ")");
            tvMyDeviceIp.setText("Network: " + getNetworkStatusString());

            Button btnAdd = findViewById(R.id.btnTopAddContact);
            Button btnSettings = findViewById(R.id.btnTopSettings);

            btnAdd.setOnClickListener(v -> showAddContactDialog());
            btnSettings.setOnClickListener(v -> showSettingsDialog());

            loadContacts();
            renderContactsUI();

            if (hasAllPermissions()) {
                startCallService();
            } else {
                requestAllPermissions();
            }

            startPeriodicStatusRefresh();
            UpdateManager.checkUpdate(this, false);
        } catch (Throwable t) {
            Log.e(TAG, "onCreate error", t);
        }
    }

    private String getNetworkStatusString() {
        String ip = getMyWifiIp();
        if (ip.equals("127.0.0.1") || ip.isEmpty()) {
            return "Mobile Data (4G/5G Active)";
        }
        return "Wi-Fi IP: " + ip;
    }

    private String getMyWifiIp() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                int ip = wm.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return String.format("%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private void loadContacts() {
        contactList.clear();
        // Always establish the 4 designated family extension lines
        contactList.add(new ContactItem("101", "Home Vivo Y51L", "Vivo", ""));
        contactList.add(new ContactItem("102", "My OnePlus (Main)", "OnePlus", ""));
        contactList.add(new ContactItem("103", "Samsung Galaxy S24 Ultra", "S24", ""));
        contactList.add(new ContactItem("104", "Samsung Galaxy M35 5G", "M35", ""));

        String json = prefs.getString(KEY_CONTACTS_JSON, null);
        if (json != null && !json.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String ext = obj.optString("ext", "");
                    for (ContactItem c : contactList) {
                        if (c.ext.equals(ext)) {
                            if (obj.has("name") && !obj.optString("name", "").isEmpty()) {
                                c.name = obj.getString("name");
                            }
                            if (obj.has("directIp")) {
                                c.directIp = obj.getString("directIp");
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        saveContacts();
    }

    private void saveContacts() {
        try {
            JSONArray arr = new JSONArray();
            for (ContactItem c : contactList) {
                JSONObject obj = new JSONObject();
                obj.put("ext", c.ext);
                obj.put("name", c.name);
                obj.put("query", c.query);
                obj.put("directIp", c.directIp);
                arr.put(obj);
            }
            prefs.edit().putString(KEY_CONTACTS_JSON, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void renderContactsUI() {
        containerContacts.removeAllViews();

        for (ContactItem contact : contactList) {
            boolean isSelf = contact.ext.equals(myExtId);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setBackgroundResource(R.drawable.bg_card);
            card.setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14));
            card.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dpToPx(10));
            card.setLayoutParams(lp);

            // Circular Initial Avatar
            TextView avatar = new TextView(this);
            avatar.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(44), dpToPx(44)));
            avatar.setBackgroundResource(R.drawable.bg_circle_avatar);
            avatar.setGravity(Gravity.CENTER);
            avatar.setText(contact.name.isEmpty() ? "P" : contact.name.substring(0, 1).toUpperCase());
            avatar.setTextColor(Color.WHITE);
            avatar.setTextSize(18);
            avatar.setTypeface(null, Typeface.BOLD);
            card.addView(avatar);

            // Contact Name & Status
            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams detLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            detLp.setMargins(dpToPx(14), 0, dpToPx(10), 0);
            details.setLayoutParams(detLp);

            TextView tvName = new TextView(this);
            tvName.setText(contact.name + (isSelf ? " (This Device)" : ""));
            tvName.setTextSize(17);
            tvName.setTypeface(null, Typeface.BOLD);
            tvName.setTextColor(Color.WHITE);
            details.addView(tvName);

            TextView tvStatus = new TextView(this);
            if (isSelf) {
                tvStatus.setText("🟢 This Device (" + getNetworkStatusString() + ")");
                tvStatus.setTextColor(Color.parseColor("#34A853"));
            } else {
                CallSignaling.PeerStatus ps = CallSignaling.peerStatuses.get(contact.ext);
                boolean isOnline = (ps != null && (System.currentTimeMillis() - ps.lastSeen < 25000));
                if (isOnline) {
                    tvStatus.setText("🟢 Online (" + ps.networkType + (ps.localIp.isEmpty() ? "" : ": " + ps.localIp) + ")");
                    tvStatus.setTextColor(Color.parseColor("#34A853"));
                } else {
                    String lanIp = resolveLanIp(contact);
                    if (lanIp != null && !lanIp.isEmpty()) {
                        tvStatus.setText("🟢 Online (Local Wi-Fi)");
                        tvStatus.setTextColor(Color.parseColor("#34A853"));
                    } else {
                        tvStatus.setText("⚪ Offline");
                        tvStatus.setTextColor(Color.parseColor("#9E9E9E"));
                    }
                }
            }
            tvStatus.setTextSize(12);
            details.addView(tvStatus);
            card.addView(details);

            // Call Action or Self Badge
            if (isSelf) {
                TextView selfBadge = new TextView(this);
                selfBadge.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(85), dpToPx(38)));
                selfBadge.setText("YOU");
                selfBadge.setGravity(Gravity.CENTER);
                selfBadge.setTextSize(12);
                selfBadge.setTypeface(null, Typeface.BOLD);
                selfBadge.setTextColor(Color.parseColor("#888888"));
                selfBadge.setBackgroundResource(R.drawable.bg_card);
                card.addView(selfBadge);
            } else {
                Button btnCall = new Button(this);
                btnCall.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(80), dpToPx(42)));
                btnCall.setText("CALL");
                btnCall.setTextSize(13);
                btnCall.setTypeface(null, Typeface.BOLD);
                btnCall.setTextColor(Color.WHITE);
                btnCall.setBackgroundResource(R.drawable.bg_button_call);
                btnCall.setOnClickListener(v -> initiateCall(contact));
                card.addView(btnCall);
            }

            // Click card opens profile / edit dialog
            card.setOnClickListener(v -> {
                if (!isSelf) showContactProfileDialog(contact);
                else Toast.makeText(this, "This is your current phone.", Toast.LENGTH_SHORT).show();
            });

            containerContacts.addView(card);
        }
    }

    private String resolveLanIp(ContactItem contact) {
        if (contact.directIp != null && !contact.directIp.trim().isEmpty()) {
            return contact.directIp.trim();
        }
        CallSignaling.PeerStatus ps = CallSignaling.peerStatuses.get(contact.ext);
        if (ps != null && ps.localIp != null && !ps.localIp.isEmpty()) {
            return ps.localIp;
        }
        for (String dev : CallSignaling.onlineDevices.keySet()) {
            if (dev.toLowerCase().contains(contact.query.toLowerCase()) ||
                dev.toLowerCase().contains(contact.name.toLowerCase())) {
                return CallSignaling.onlineDevices.get(dev);
            }
        }
        return null;
    }

    private void initiateCall(ContactItem contact) {
        if (contact.ext.equals(myExtId)) {
            Toast.makeText(this, "Cannot call own device!", Toast.LENGTH_SHORT).show();
            return;
        }

        String lanIp = resolveLanIp(contact);
        Toast.makeText(this, "Calling " + contact.name + "...", Toast.LENGTH_SHORT).show();

        CallSignaling signaling = new CallSignaling(this, null);
        signaling.sendCommand(contact.ext, lanIp, "INVITE");

        Intent intent = new Intent(this, InCallActivity.class);
        intent.putExtra("REMOTE_NAME", contact.name);
        intent.putExtra("REMOTE_IP", lanIp != null ? lanIp : "remote");
        intent.putExtra("REMOTE_EXT", contact.ext);
        intent.putExtra("IS_OUTGOING", true);
        startActivity(intent);
    }

    private void showContactProfileDialog(ContactItem contact) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(contact.name + " (Ext: " + contact.ext + ")");

        CallSignaling.PeerStatus ps = CallSignaling.peerStatuses.get(contact.ext);
        String status = (ps != null && System.currentTimeMillis() - ps.lastSeen < 15000)
                ? "Online (" + ps.networkType + ")" : "Offline / Standby";

        String lanIp = resolveLanIp(contact);
        builder.setMessage("Device: " + contact.query + "\nStatus: " + status + "\nLAN IP: " + (lanIp != null ? lanIp : "N/A"));

        builder.setPositiveButton("CALL", (dialog, which) -> initiateCall(contact));
        builder.setNeutralButton("Edit", (dialog, which) -> showEditContactDialog(contact));
        builder.setNegativeButton("Delete", (dialog, which) -> {
            contactList.remove(contact);
            saveContacts();
            renderContactsUI();
            Toast.makeText(this, "Contact deleted", Toast.LENGTH_SHORT).show();
        });
        builder.show();
    }

    private void showAddContactDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Add New Device");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(20), dpToPx(10), dpToPx(20), dpToPx(10));

        final EditText etName = new EditText(this);
        etName.setHint("Contact Name (e.g. Samsung S24)");
        layout.addView(etName);

        final EditText etExt = new EditText(this);
        etExt.setHint("Extension ID (e.g. 101, 102, 103, 104)");
        layout.addView(etExt);

        final EditText etQuery = new EditText(this);
        etQuery.setHint("Device Model Keyword (e.g. S24, Vivo)");
        layout.addView(etQuery);

        final EditText etIp = new EditText(this);
        etIp.setHint("Direct IP (Optional)");
        layout.addView(etIp);

        builder.setView(layout);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String name = etName.getText().toString().trim();
            String ext = etExt.getText().toString().trim();
            String query = etQuery.getText().toString().trim();
            String ip = etIp.getText().toString().trim();
            if (!name.isEmpty() && !ext.isEmpty()) {
                contactList.add(new ContactItem(ext, name, query.isEmpty() ? name : query, ip));
                saveContacts();
                renderContactsUI();
                Toast.makeText(this, "Contact added!", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void showEditContactDialog(ContactItem contact) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Edit Contact");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(20), dpToPx(10), dpToPx(20), dpToPx(10));

        final EditText etName = new EditText(this);
        etName.setText(contact.name);
        layout.addView(etName);

        final EditText etExt = new EditText(this);
        etExt.setText(contact.ext);
        layout.addView(etExt);

        final EditText etQuery = new EditText(this);
        etQuery.setText(contact.query);
        layout.addView(etQuery);

        final EditText etIp = new EditText(this);
        etIp.setText(contact.directIp);
        etIp.setHint("Direct IP (optional)");
        layout.addView(etIp);

        builder.setView(layout);

        builder.setPositiveButton("Save", (dialog, which) -> {
            contact.name = etName.getText().toString().trim();
            contact.ext = etExt.getText().toString().trim();
            contact.query = etQuery.getText().toString().trim();
            contact.directIp = etIp.getText().toString().trim();
            saveContacts();
            renderContactsUI();
            Toast.makeText(this, "Updated!", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void showSettingsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Settings & Profile");

        String msg = "Phone: " + Build.MODEL + "\n" +
                     "Extension ID: " + myExtId + "\n" +
                     "Network: " + getNetworkStatusString() + "\n" +
                     "Version: v1.0.5 (Hybrid Wi-Fi + 5G)\n" +
                     "Signaling: HiveMQ + P2P Direct\n" +
                     "Encryption: AES-256 Military Grade";
        builder.setMessage(msg);

        builder.setPositiveButton("Check Updates", (dialog, which) -> {
            Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show();
            UpdateManager.checkUpdate(this, true);
        });

        builder.setNegativeButton("Close", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    private void startPeriodicStatusRefresh() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                renderContactsUI();
                tvMyDeviceIp.setText("Network: " + getNetworkStatusString());
                mainHandler.postDelayed(this, 3000);
            }
        }, 3000);
    }

    private boolean hasAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
        }
        return true;
    }

    private void requestAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS
                }, PERMISSION_REQ_CODE);
            } else {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.RECORD_AUDIO
                }, PERMISSION_REQ_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            startCallService();
        }
    }

    private void startCallService() {
        try {
            Intent serviceIntent = new Intent(this, CallBackgroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed startCallService", t);
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}
