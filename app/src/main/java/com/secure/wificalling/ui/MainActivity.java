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
import android.net.DhcpInfo;
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
 * Google Dialer Style Main Speed-Dial & Contact Screen.
 * Full Contact CRUD, Profile Inspection, Subnet Device Discovery,
 * and 1-Click Update Support.
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQ_CODE = 2001;
    private static final String PREFS_NAME = "WiFiCallingPrefs";
    private static final String KEY_CONTACTS_JSON = "CONTACTS_JSON_V2";

    public static class ContactItem {
        public String id;
        public String name;
        public String query; // Device match keyword or direct IP
        public String directIp;

        public ContactItem(String id, String name, String query, String directIp) {
            this.id = id;
            this.name = name;
            this.query = query;
            this.directIp = directIp;
        }
    }

    private SharedPreferences prefs;
    private List<ContactItem> contactList = new ArrayList<>();
    private LinearLayout containerContacts;
    private LinearLayout containerDiscovered;
    private TextView tvMyDeviceName, tvMyDeviceIp;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "Uncaught Exception caught safely: ", throwable);
        });

        try {
            setContentView(R.layout.activity_main);
            prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

            tvMyDeviceName = findViewById(R.id.tvMyDeviceName);
            tvMyDeviceIp = findViewById(R.id.tvMyDeviceIp);
            containerContacts = findViewById(R.id.containerContacts);
            containerDiscovered = findViewById(R.id.containerDiscovered);

            tvMyDeviceName.setText("This Phone: " + Build.MODEL);
            tvMyDeviceIp.setText("IP: " + getMyWifiIp());

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

            startSubnetFastScan();
            startPeriodicStatusRefresh();
            UpdateManager.checkUpdate(this, false);
        } catch (Throwable t) {
            Log.e(TAG, "Error in onCreate", t);
        }
    }

    private String getMyWifiIp() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
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
        String json = prefs.getString(KEY_CONTACTS_JSON, null);
        if (json != null && !json.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    contactList.add(new ContactItem(
                            obj.optString("id", String.valueOf(System.currentTimeMillis())),
                            obj.optString("name", "Device"),
                            obj.optString("query", ""),
                            obj.optString("directIp", "")
                    ));
                }
            } catch (Exception e) {
                Log.e(TAG, "JSON parse error", e);
            }
        }

        // Initialize default 4 phones if empty
        if (contactList.isEmpty()) {
            contactList.add(new ContactItem("101", "Home Vivo Y51L", "Vivo", ""));
            contactList.add(new ContactItem("102", "My OnePlus (Main)", "OnePlus", ""));
            contactList.add(new ContactItem("103", "Samsung Galaxy S24 Ultra", "S24", ""));
            contactList.add(new ContactItem("104", "Samsung Galaxy M35 5G", "M35", ""));
            saveContacts();
        }
    }

    private void saveContacts() {
        try {
            JSONArray arr = new JSONArray();
            for (ContactItem c : contactList) {
                JSONObject obj = new JSONObject();
                obj.put("id", c.id);
                obj.put("name", c.name);
                obj.put("query", c.query);
                obj.put("directIp", c.directIp);
                arr.put(obj);
            }
            prefs.edit().putString(KEY_CONTACTS_JSON, arr.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "Save contacts error", e);
        }
    }

    private void renderContactsUI() {
        containerContacts.removeAllViews();

        for (ContactItem contact : contactList) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setBackgroundResource(R.drawable.bg_card);
            card.setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14));
            card.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dpToPx(10));
            card.setLayoutParams(lp);

            // Circular Initial Avatar (Google Dialer style)
            TextView avatar = new TextView(this);
            avatar.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(44), dpToPx(44)));
            avatar.setBackgroundResource(R.drawable.bg_circle_avatar);
            avatar.setGravity(Gravity.CENTER);
            avatar.setText(contact.name.isEmpty() ? "P" : contact.name.substring(0, 1).toUpperCase());
            avatar.setTextColor(Color.WHITE);
            avatar.setTextSize(18);
            avatar.setTypeface(null, Typeface.BOLD);
            card.addView(avatar);

            // Name & Status Details
            LinearLayout details = new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams detLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            detLp.setMargins(dpToPx(14), 0, dpToPx(10), 0);
            details.setLayoutParams(detLp);

            TextView tvName = new TextView(this);
            tvName.setText(contact.name);
            tvName.setTextSize(17);
            tvName.setTypeface(null, Typeface.BOLD);
            tvName.setTextColor(Color.WHITE);
            details.addView(tvName);

            TextView tvStatus = new TextView(this);
            String resolvedIp = resolveIpForContact(contact);
            boolean isOnline = (resolvedIp != null);
            tvStatus.setText(isOnline ? "🟢 Online (" + resolvedIp + ")" : "⚪ Offline");
            tvStatus.setTextSize(12);
            tvStatus.setTextColor(isOnline ? Color.parseColor("#34A853") : Color.parseColor("#9E9E9E"));
            details.addView(tvStatus);

            card.addView(details);

            // Call Button
            Button btnCall = new Button(this);
            btnCall.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(80), dpToPx(42)));
            btnCall.setText("CALL");
            btnCall.setTextSize(13);
            btnCall.setTypeface(null, Typeface.BOLD);
            btnCall.setTextColor(Color.WHITE);
            btnCall.setBackgroundResource(R.drawable.bg_button_call);
            btnCall.setOnClickListener(v -> initiateCall(contact));
            card.addView(btnCall);

            // Clicking card opens Google Dialer Profile/Edit Sheet
            card.setOnClickListener(v -> showContactProfileDialog(contact));
            card.setOnLongClickListener(v -> {
                showContactProfileDialog(contact);
                return true;
            });

            containerContacts.addView(card);
        }
    }

    private String resolveIpForContact(ContactItem contact) {
        if (contact.directIp != null && !contact.directIp.trim().isEmpty()) {
            return contact.directIp.trim();
        }
        for (String dev : CallSignaling.onlineDevices.keySet()) {
            if (dev.toLowerCase().contains(contact.query.toLowerCase()) ||
                dev.toLowerCase().contains(contact.name.toLowerCase())) {
                return CallSignaling.onlineDevices.get(dev);
            }
        }
        return null;
    }

    private void showContactProfileDialog(ContactItem contact) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(contact.name);

        String currentIp = resolveIpForContact(contact);
        String info = "Device: " + contact.query + "\nStatus: " + (currentIp != null ? "Online (" + currentIp + ")" : "Offline");
        builder.setMessage(info);

        builder.setPositiveButton("CALL", (dialog, which) -> initiateCall(contact));
        builder.setNeutralButton("Edit Name/IP", (dialog, which) -> showEditContactDialog(contact));
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
        builder.setTitle("Add New Contact / Device");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(20), dpToPx(10), dpToPx(20), dpToPx(10));

        final EditText etName = new EditText(this);
        etName.setHint("Contact Name (e.g. Mummy Vivo)");
        layout.addView(etName);

        final EditText etQuery = new EditText(this);
        etQuery.setHint("Device Name / Model (e.g. Vivo, OnePlus)");
        layout.addView(etQuery);

        final EditText etIp = new EditText(this);
        etIp.setHint("Optional IP (e.g. 192.168.1.15)");
        layout.addView(etIp);

        builder.setView(layout);

        builder.setPositiveButton("Add", (dialog, which) -> {
            String name = etName.getText().toString().trim();
            String query = etQuery.getText().toString().trim();
            String ip = etIp.getText().toString().trim();
            if (!name.isEmpty()) {
                contactList.add(new ContactItem(String.valueOf(System.currentTimeMillis()), name, query.isEmpty() ? name : query, ip));
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

        final EditText etQuery = new EditText(this);
        etQuery.setText(contact.query);
        etQuery.setHint("Device query keyword");
        layout.addView(etQuery);

        final EditText etIp = new EditText(this);
        etIp.setText(contact.directIp);
        etIp.setHint("Direct IP address (optional)");
        layout.addView(etIp);

        builder.setView(layout);

        builder.setPositiveButton("Save", (dialog, which) -> {
            contact.name = etName.getText().toString().trim();
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
        builder.setTitle("Profile & Settings");

        String msg = "Device: " + Build.MODEL + "\n" +
                     "Android Version: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n" +
                     "Wi-Fi IP: " + getMyWifiIp() + "\n" +
                     "App Version: v1.0.2 (Signed Release)\n" +
                     "Encryption: AES-256-GCM Hardware-Backed\n" +
                     "Signaling Port: 50620 (P2P)";
        builder.setMessage(msg);

        builder.setPositiveButton("Scan Subnet", (dialog, which) -> {
            Toast.makeText(this, "Scanning local Wi-Fi for devices...", Toast.LENGTH_SHORT).show();
            startSubnetFastScan();
        });

        builder.setNeutralButton("Check for Updates", (dialog, which) -> {
            Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show();
            UpdateManager.checkUpdate(this, true);
        });

        builder.setNegativeButton("Close", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    private void initiateCall(ContactItem contact) {
        String targetIp = resolveIpForContact(contact);
        if (targetIp == null) {
            Toast.makeText(this, contact.name + " is not connected to this Wi-Fi!", Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, "Calling " + contact.name + "...", Toast.LENGTH_SHORT).show();

        CallSignaling signaling = new CallSignaling(this, null);
        signaling.sendCommand(targetIp, "INVITE", Build.MODEL);

        Intent intent = new Intent(this, InCallActivity.class);
        intent.putExtra("REMOTE_NAME", contact.name);
        intent.putExtra("REMOTE_IP", targetIp);
        intent.putExtra("IS_OUTGOING", true);
        startActivity(intent);
    }

    private void startSubnetFastScan() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String myIp = getMyWifiIp();
                if (myIp == null || !myIp.contains(".")) return;
                String prefix = myIp.substring(0, myIp.lastIndexOf(".") + 1);

                // Multi-threaded fast ping to detect all phones on port 50620
                for (int i = 1; i <= 254; i++) {
                    final String testIp = prefix + i;
                    if (testIp.equals(myIp)) continue;

                    Executors.newCachedThreadPool().execute(() -> {
                        try {
                            Socket s = new Socket();
                            s.connect(new InetSocketAddress(testIp, CallSignaling.SIGNALING_PORT), 400);
                            s.close();
                            // Found device on port 50620!
                            CallSignaling.onlineDevices.put("Phone (" + testIp + ")", testIp);
                            mainHandler.post(this::renderContactsUI);
                        } catch (Exception ignored) {}
                    });
                }
            } catch (Exception ignored) {}
        });
    }

    private void startPeriodicStatusRefresh() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                renderContactsUI();
                renderDiscoveredDevicesUI();
                mainHandler.postDelayed(this, 3000);
            }
        }, 3000);
    }

    private void renderDiscoveredDevicesUI() {
        containerDiscovered.removeAllViews();
        for (String devName : CallSignaling.onlineDevices.keySet()) {
            String ip = CallSignaling.onlineDevices.get(devName);

            // If not already in contacts, show as discovered
            boolean alreadySaved = false;
            for (ContactItem c : contactList) {
                if (c.query.equalsIgnoreCase(devName) || (c.directIp != null && c.directIp.equals(ip))) {
                    alreadySaved = true;
                    break;
                }
            }

            if (!alreadySaved) {
                Button btnAddFound = new Button(this);
                btnAddFound.setText("＋ Add Discovered: " + devName + " (" + ip + ")");
                btnAddFound.setTextSize(12);
                btnAddFound.setTextColor(Color.WHITE);
                btnAddFound.setBackgroundResource(R.drawable.bg_card);
                btnAddFound.setOnClickListener(v -> {
                    contactList.add(new ContactItem(String.valueOf(System.currentTimeMillis()), devName, devName, ip));
                    saveContacts();
                    renderContactsUI();
                    renderDiscoveredDevicesUI();
                    Toast.makeText(this, "Added to Contacts!", Toast.LENGTH_SHORT).show();
                });
                containerDiscovered.addView(btnAddFound);
            }
        }
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
