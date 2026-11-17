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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Google Dialer Speed-Dial & Contact Management Screen.
 * - Dynamic Recents: Most recently called/calling contact jumps to #1 at top.
 * - Current device is always pinned at the VERY BOTTOM with 'YOU' badge.
 * - Wi-Fi & 4G/5G Hybrid Calling with HiveMQ & STUN P2P.
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQ_CODE = 2001;
    public static final String PREFS_NAME = "WiFiCallingPrefs";
    public static final String KEY_CONTACTS_JSON = "CONTACTS_JSON_V4";

    public static class ContactItem {
        public String ext;      // Extension ID (e.g. "101", "102", "103", "104")
        public String name;     // Display name
        public String query;    // Device model filter
        public String directIp; // Optional static IP
        public long lastInteractionTime; // Timestamp of last call for recents sorting

        public ContactItem(String ext, String name, String query, String directIp, long lastInteractionTime) {
            this.ext = ext;
            this.name = name;
            this.query = query;
            this.directIp = directIp;
            this.lastInteractionTime = lastInteractionTime;
        }

        public ContactItem(String ext, String name, String query, String directIp) {
            this(ext, name, query, directIp, 0);
        }
    }

    private SharedPreferences prefs;
    private List<ContactItem> contactList = new ArrayList<>();
    private LinearLayout containerContacts;
    private TextView tvMyDeviceIp;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private String myExtId;

    public static void recordInteraction(Context context, String ext) {
        if (context == null || ext == null || ext.isEmpty()) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_CONTACTS_JSON, null);
            if (json != null && !json.isEmpty()) {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    if (ext.equals(obj.optString("ext", ""))) {
                        obj.put("lastInteractionTime", System.currentTimeMillis());
                    }
                }
                prefs.edit().putString(KEY_CONTACTS_JSON, arr.toString()).apply();
            }
        } catch (Exception ignored) {}
    }

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

            tvMyDeviceIp = findViewById(R.id.tvMyDeviceIp);
            containerContacts = findViewById(R.id.containerContacts);

            tvMyDeviceIp.setText(getNetworkStatusString());

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

    @Override
    protected void onResume() {
        super.onResume();
        loadContacts();
        renderContactsUI();
        if (tvMyDeviceIp != null) {
            tvMyDeviceIp.setText(getNetworkStatusString());
        }
    }

    private String getNetworkStatusString() {
        String ip = getMyWifiIp();
        if (ip.equals("127.0.0.1") || ip.isEmpty()) {
            return "🟢 4G/5G Active • Online";
        }
        return "🟢 Wi-Fi: " + ip;
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
        // Staggered initial timestamps so the default list has clean order
        contactList.add(new ContactItem("101", "Home Vivo Y51L", "Vivo", "", 4000));
        contactList.add(new ContactItem("102", "My OnePlus (Main)", "OnePlus", "", 3000));
        contactList.add(new ContactItem("103", "Samsung Galaxy S24 Ultra", "S24", "", 2000));
        contactList.add(new ContactItem("104", "Samsung Galaxy M35 5G", "M35", "", 1000));

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
                            if (obj.has("lastInteractionTime")) {
                                c.lastInteractionTime = obj.getLong("lastInteractionTime");
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
                obj.put("lastInteractionTime", c.lastInteractionTime);
                arr.put(obj);
            }
            prefs.edit().putString(KEY_CONTACTS_JSON, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void renderContactsUI() {
        containerContacts.removeAllViews();

        ContactItem selfContact = null;
        List<ContactItem> others = new ArrayList<>();

        for (ContactItem c : contactList) {
            if (c.ext.equals(myExtId)) {
                selfContact = c;
            } else {
                others.add(c);
            }
        }

        // Sort non-self contacts by lastInteractionTime descending (most recent interaction on top #1)
        Collections.sort(others, (a, b) -> Long.compare(b.lastInteractionTime, a.lastInteractionTime));

        // 1. Render Top Speed-Dial Family Contacts (Other 3 Devices)
        for (ContactItem contact : others) {
            LinearLayout card = createContactCard(contact, false);
            containerContacts.addView(card);
        }

        // 2. Render Bottom Section Header: THIS DEVICE (MY PHONE)
        TextView tvSelfHeader = new TextView(this);
        tvSelfHeader.setText("THIS DEVICE (MY PHONE)");
        tvSelfHeader.setTextSize(12);
        tvSelfHeader.setTypeface(null, Typeface.BOLD);
        tvSelfHeader.setTextColor(Color.parseColor("#9E9E9E"));
        tvSelfHeader.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headerLp.setMargins(0, dpToPx(18), 0, dpToPx(10));
        tvSelfHeader.setLayoutParams(headerLp);
        containerContacts.addView(tvSelfHeader);

        // 3. Render Self Contact Card at the VERY BOTTOM
        if (selfContact != null) {
            LinearLayout selfCard = createContactCard(selfContact, true);
            containerContacts.addView(selfCard);
        }
    }

    private LinearLayout createContactCard(ContactItem contact, boolean isSelf) {
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
            tvStatus.setText("🟢 Active • " + getNetworkStatusString());
            tvStatus.setTextColor(Color.parseColor("#34A853"));
        } else {
            CallSignaling.PeerStatus ps = CallSignaling.peerStatuses.get(contact.ext);
            boolean isOnline = (ps != null && (System.currentTimeMillis() - ps.lastSeen < 25000));
            if (isOnline) {
                String ipPart = (!ps.localIp.isEmpty() && !ps.localIp.equals("127.0.0.1")) ? ": " + ps.localIp : "";
                tvStatus.setText("🟢 Online (" + ps.networkType + ipPart + ")");
                tvStatus.setTextColor(Color.parseColor("#34A853"));
            } else {
                String lanIp = resolveLanIp(contact);
                if (lanIp != null && !lanIp.isEmpty() && !lanIp.equals("127.0.0.1")) {
                    tvStatus.setText("🟢 Online (Local Wi-Fi)");
                    tvStatus.setTextColor(Color.parseColor("#34A853"));
                } else {
                    tvStatus.setText("⚪ Offline / Standby");
                    tvStatus.setTextColor(Color.parseColor("#9E9E9E"));
                }
            }
        }
        tvStatus.setTextSize(12);
        details.addView(tvStatus);
        card.addView(details);

        // Action: [CALL] for others, [YOU] for self
        if (isSelf) {
            TextView selfBadge = new TextView(this);
            selfBadge.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(75), dpToPx(38)));
            selfBadge.setText("YOU");
            selfBadge.setGravity(Gravity.CENTER);
            selfBadge.setTextSize(12);
            selfBadge.setTypeface(null, Typeface.BOLD);
            selfBadge.setTextColor(Color.parseColor("#888888"));
            selfBadge.setBackgroundResource(R.drawable.bg_card);
            card.addView(selfBadge);

            card.setOnClickListener(v -> Toast.makeText(this, "This is your current phone (" + Build.MODEL + ")", Toast.LENGTH_SHORT).show());
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

            card.setOnClickListener(v -> showContactProfileDialog(contact));
        }

        return card;
    }

    private String resolveLanIp(ContactItem contact) {
        if (contact.directIp != null && !contact.directIp.trim().isEmpty()) {
            return contact.directIp.trim();
        }
        CallSignaling.PeerStatus ps = CallSignaling.peerStatuses.get(contact.ext);
        if (ps != null && ps.localIp != null && !ps.localIp.isEmpty() && !ps.localIp.equals("127.0.0.1")) {
            return ps.localIp;
        }
        for (String dev : CallSignaling.onlineDevices.keySet()) {
            if (dev.toLowerCase().contains(contact.query.toLowerCase()) ||
                dev.toLowerCase().contains(contact.name.toLowerCase())) {
                String ip = CallSignaling.onlineDevices.get(dev);
                if (ip != null && !ip.equals("127.0.0.1")) {
                    return ip;
                }
            }
        }
        return null;
    }

    private void initiateCall(ContactItem contact) {
        if (contact.ext.equals(myExtId)) {
            Toast.makeText(this, "Cannot call own device!", Toast.LENGTH_SHORT).show();
            return;
        }

        // Jump to position #1 immediately
        contact.lastInteractionTime = System.currentTimeMillis();
        saveContacts();
        renderContactsUI();

        String lanIp = resolveLanIp(contact);
        Toast.makeText(this, "Calling " + contact.name + "...", Toast.LENGTH_SHORT).show();

        CallSignaling signaling = new CallSignaling(this, null);
        signaling.sendCommand(contact.ext, lanIp, "INVITE");

        Intent intent = new Intent(this, InCallActivity.class);
        intent.putExtra("REMOTE_NAME", contact.name);
        intent.putExtra("REMOTE_IP", lanIp != null ? lanIp : "remote");
        intent.putExtra("REMOTE_EXT", contact.ext);
        intent.putExtra("REMOTE_PUBLIC_EP", CallSignaling.getPeerPublicEndpoint(contact.ext));
        intent.putExtra("IS_OUTGOING", true);
        startActivity(intent);
    }

    private void showContactProfileDialog(ContactItem contact) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(contact.name + " (Ext: " + contact.ext + ")");

        CallSignaling.PeerStatus ps = CallSignaling.peerStatuses.get(contact.ext);
        String status = (ps != null && System.currentTimeMillis() - ps.lastSeen < 25000)
                ? "Online (" + ps.networkType + ")" : "Offline / Standby";

        String lanIp = resolveLanIp(contact);
        builder.setMessage("Device: " + contact.query + "\nStatus: " + status + "\nLAN IP: " + (lanIp != null ? lanIp : "Auto (Remote/4G)"));

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
                contactList.add(new ContactItem(ext, name, query.isEmpty() ? name : query, ip, System.currentTimeMillis()));
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
                     "Public WAN: " + (CallSignaling.myPublicEndpoint.isEmpty() ? "Discovering..." : CallSignaling.myPublicEndpoint) + "\n" +
                     "Version: v1.0.6 (Hybrid Wi-Fi + 5G)\n" +
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
                if (tvMyDeviceIp != null) {
                    tvMyDeviceIp.setText(getNetworkStatusString());
                }
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
