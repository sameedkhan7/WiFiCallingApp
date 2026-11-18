package com.secure.wificalling.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
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
    private boolean isActivityResumed = false;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isActivityResumed) return;
            renderContactsUI();
            if (tvMyDeviceIp != null) {
                tvMyDeviceIp.setText(getNetworkStatusString());
            }
            mainHandler.postDelayed(this, 5000);
        }
    };

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

            myExtId = CallSignaling.detectMyExtensionId(this);

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
        isActivityResumed = true;
        loadContacts();
        renderContactsUI();
        if (tvMyDeviceIp != null) {
            tvMyDeviceIp.setText(getNetworkStatusString());
        }

        // Announce presence immediately on app resume and ensure signaling is started
        try {
            CallSignaling sig = new CallSignaling(this, null);
            sig.start();
            sig.broadcastPresence();
            sig.publishPresence();
        } catch (Throwable ignored) {}

        mainHandler.removeCallbacks(refreshRunnable);
        mainHandler.postDelayed(refreshRunnable, 4000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        isActivityResumed = false;
        mainHandler.removeCallbacks(refreshRunnable);
    }

    private String getNetworkStatusString() {
        boolean isWifi = CallSignaling.isWifiConnected(this);
        if (isWifi) {
            return "🟢 Online (Wi-Fi)";
        }
        return "🟢 Online (Mobile Data)";
    }

    private void loadContacts() {
        contactList.clear();

        String json = prefs.getString(KEY_CONTACTS_JSON, null);
        if (json != null && !json.trim().isEmpty()) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String ext = obj.optString("ext", "");
                    if (ext.isEmpty()) continue;
                    String name = obj.optString("name", "Contact " + ext);
                    String query = obj.optString("query", name);
                    String directIp = obj.optString("directIp", "");
                    long lastInteractionTime = obj.optLong("lastInteractionTime", 0);
                    contactList.add(new ContactItem(ext, name, query, directIp, lastInteractionTime));
                }
            } catch (Exception ignored) {}
        }

        // If no contacts exist yet (first fresh run), add initial speed-dial contacts
        if (contactList.isEmpty()) {
            contactList.add(new ContactItem("101", "Home Vivo Y51L", "Vivo", "", 4000));
            contactList.add(new ContactItem("102", "My OnePlus (Main)", "OnePlus", "", 3000));
            contactList.add(new ContactItem("103", "Samsung Galaxy S24 Ultra", "S24", "", 2000));
            contactList.add(new ContactItem("104", "Samsung Galaxy M35 5G", "M35", "", 1000));
            saveContacts();
        }
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
        if (isSelf) {
            String profileName = getMyProfileName(this);
            tvName.setText(profileName + " (This Device)");
        } else {
            tvName.setText(contact.name);
        }
        tvName.setTextSize(17);
        tvName.setTypeface(null, Typeface.BOLD);
        tvName.setTextColor(Color.WHITE);
        details.addView(tvName);

        TextView tvStatus = new TextView(this);
        if (isSelf) {
            tvStatus.setText("🟢 Active • " + getNetworkStatusString() + " • ID: " + contact.ext + " • Tap to edit");
            tvStatus.setTextColor(Color.parseColor("#34A853"));
        } else {
            boolean isOnline = CallSignaling.isPeerOnline(contact.ext);
            if (isOnline) {
                CallSignaling.PeerStatus ps = CallSignaling.peerStatuses.get(contact.ext);
                String netType = (ps != null && ps.networkType != null) ? ps.networkType : "Online";
                tvStatus.setText("🟢 Online (" + netType + ") • ID: " + contact.ext);
                tvStatus.setTextColor(Color.parseColor("#34A853"));
            } else {
                tvStatus.setText("⚪ Offline / Standby • ID: " + contact.ext);
                tvStatus.setTextColor(Color.parseColor("#9E9E9E"));
            }
        }
        tvStatus.setTextSize(12);
        details.addView(tvStatus);
        card.addView(details);

        // Action: [CALL] for others, [EDIT] for self
        if (isSelf) {
            TextView selfBadge = new TextView(this);
            selfBadge.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(75), dpToPx(38)));
            selfBadge.setText("EDIT");
            selfBadge.setGravity(Gravity.CENTER);
            selfBadge.setTextSize(12);
            selfBadge.setTypeface(null, Typeface.BOLD);
            selfBadge.setTextColor(Color.parseColor("#34A853"));
            selfBadge.setBackgroundResource(R.drawable.bg_card);
            card.addView(selfBadge);

            card.setOnClickListener(v -> showEditMyProfileDialog());
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
        if (!CallSignaling.isPeerOnline(contact.ext)) {
            return null; // Peer is currently offline
        }
        if (contact.directIp != null && !contact.directIp.trim().isEmpty()) {
            return contact.directIp.trim();
        }
        String myLocalIp = CallSignaling.getRealIpAddress();
        CallSignaling.PeerStatus ps = CallSignaling.peerStatuses.get(contact.ext);
        if (ps != null && ps.localIp != null && !ps.localIp.isEmpty() && !ps.localIp.equals("127.0.0.1")) {
            // Only return LAN IP if both devices are on the SAME local subnet!
            if (CallSignaling.isSameSubnet(myLocalIp, ps.localIp)) {
                return ps.localIp;
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
        builder.setTitle(contact.name);

        boolean isOnline = CallSignaling.isPeerOnline(contact.ext);
        String statusText = isOnline ? "🟢 Online" : "⚪ Offline / Standby";
        builder.setMessage("Calling Number: " + contact.ext + "\nStatus: " + statusText + "\nAudio Security: AES-256 Encrypted Voice");

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
        builder.setTitle("Add New Contact");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(20), dpToPx(10), dpToPx(20), dpToPx(10));

        final EditText etName = new EditText(this);
        etName.setHint("Contact Name (e.g. Sameed, Home, Friend)");
        layout.addView(etName);

        final EditText etExt = new EditText(this);
        etExt.setHint("Calling Number / ID (e.g. 101, 102, 105...)");
        layout.addView(etExt);

        builder.setView(layout);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String name = etName.getText().toString().trim();
            String ext = etExt.getText().toString().trim();
            if (!name.isEmpty() && !ext.isEmpty()) {
                boolean updated = false;
                for (ContactItem c : contactList) {
                    if (c.ext.equals(ext)) {
                        c.name = name;
                        c.query = name;
                        updated = true;
                        break;
                    }
                }
                if (!updated) {
                    contactList.add(new ContactItem(ext, name, name, "", System.currentTimeMillis()));
                }
                saveContacts();
                renderContactsUI();
                Toast.makeText(this, updated ? "Contact updated!" : "Contact added!", Toast.LENGTH_SHORT).show();
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
        etName.setHint("Contact Name");
        layout.addView(etName);

        final EditText etExt = new EditText(this);
        etExt.setText(contact.ext);
        etExt.setHint("Calling Number / ID");
        layout.addView(etExt);

        builder.setView(layout);

        builder.setPositiveButton("Save", (dialog, which) -> {
            contact.name = etName.getText().toString().trim();
            contact.ext = etExt.getText().toString().trim();
            contact.query = contact.name;
            contact.directIp = "";
            saveContacts();
            renderContactsUI();
            Toast.makeText(this, "Updated!", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    public static String getMyProfileName(Context context) {
        if (context != null) {
            try {
                SharedPreferences p = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                String name = p.getString("MY_PROFILE_NAME", null);
                if (name != null && !name.trim().isEmpty()) {
                    return name.trim();
                }
            } catch (Throwable ignored) {}
        }
        String ext = CallSignaling.detectMyExtensionId(context);
        if ("101".equals(ext)) return "Home Vivo Y51L";
        if ("102".equals(ext)) return "My OnePlus";
        if ("103".equals(ext)) return "Samsung Galaxy S24 Ultra";
        if ("104".equals(ext)) return "Samsung Galaxy M35 5G";
        return Build.MODEL;
    }

    private void showEditMyProfileDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Set Calling Profile");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(20), dpToPx(10), dpToPx(20), dpToPx(10));

        TextView tvHint = new TextView(this);
        tvHint.setText("Set the Name and Calling ID that other phones will see when you call them:");
        tvHint.setTextColor(Color.parseColor("#CCCCCC"));
        tvHint.setTextSize(13);
        layout.addView(tvHint);

        final EditText etName = new EditText(this);
        etName.setHint("Your Name (e.g. Sameed, Papa, Ghar)");
        etName.setText(getMyProfileName(this));
        layout.addView(etName);

        final EditText etExt = new EditText(this);
        etExt.setHint("Calling ID / Username (e.g. 102, sameed)");
        etExt.setText(myExtId);
        layout.addView(etExt);

        builder.setView(layout);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String newName = etName.getText().toString().trim();
            String newExt = etExt.getText().toString().trim();
            if (!newName.isEmpty()) {
                prefs.edit().putString("MY_PROFILE_NAME", newName).apply();
            }
            if (!newExt.isEmpty()) {
                myExtId = newExt;
                prefs.edit().putString("SAVED_MY_EXT_ID", newExt).apply();
                getSharedPreferences("WiFiCallingPrefs", MODE_PRIVATE).edit().putString("SAVED_MY_EXT_ID", newExt).apply();
            }
            loadContacts();
            renderContactsUI();

            // Broadcast presence immediately with updated profile name
            try {
                CallSignaling sig = new CallSignaling(this, null);
                sig.start();
                sig.publishPresence();
                sig.broadcastPresence();
            } catch (Throwable ignored) {}

            Toast.makeText(this, "Profile updated! Name: " + newName + " | ID: " + myExtId, Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    public static String getContactDisplayName(Context context, String ext, String fallbackName) {
        if (context != null) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                String json = prefs.getString(KEY_CONTACTS_JSON, null);
                if (json != null && !json.isEmpty()) {
                    JSONArray arr = new JSONArray(json);
                    // 1. Match by extension ID first (Primary guarantee)
                    if (ext != null && !ext.isEmpty()) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject obj = arr.getJSONObject(i);
                            if (ext.equals(obj.optString("ext"))) {
                                String savedName = obj.optString("name", "");
                                if (!savedName.trim().isEmpty()) {
                                    return savedName.trim();
                                }
                            }
                        }
                    }
                    // 2. Match by hardware model or query (e.g. "CPH2381" or "OnePlus" or "Vivo")
                    if (fallbackName != null && !fallbackName.trim().isEmpty()) {
                        String lowerFallback = fallbackName.toLowerCase();
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject obj = arr.getJSONObject(i);
                            String q = obj.optString("query", "").toLowerCase();
                            String n = obj.optString("name", "");
                            if (!q.isEmpty() && (lowerFallback.contains(q) || q.contains(lowerFallback))) {
                                if (!n.trim().isEmpty()) return n.trim();
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Caller passed their custom profile name (e.g. "Sameed") -> Honor and display it directly!
        if (fallbackName != null && !fallbackName.trim().isEmpty() &&
            !fallbackName.equalsIgnoreCase("Unknown") &&
            !fallbackName.startsWith("Extension") &&
            !fallbackName.equals(Build.MODEL)) {
            return fallbackName.trim();
        }

        // Friendly default identities based on hardware mapping
        if ("101".equals(ext)) return "Home Vivo Y51L";
        if ("102".equals(ext)) return "My OnePlus (Main)";
        if ("103".equals(ext)) return "Samsung Galaxy S24 Ultra";
        if ("104".equals(ext)) return "Samsung Galaxy M35 5G";

        return (fallbackName != null && !fallbackName.isEmpty()) ? fallbackName : "Calling ID: " + ext;
    }

    public static void openAutostartSettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(context, "Please open Settings > Apps > Internet Call > Permissions", Toast.LENGTH_LONG).show();
        }
    }

    private void showSettingsDialog() {
        try {
            LayoutInflater inflater = LayoutInflater.from(this);
            View view = inflater.inflate(R.layout.dialog_settings, null);

            TextView tvDeviceName = view.findViewById(R.id.tvSettingsDeviceName);
            TextView tvExtension = view.findViewById(R.id.tvSettingsExtension);
            TextView tvNetwork = view.findViewById(R.id.tvSettingsNetwork);
            TextView tvEncryption = view.findViewById(R.id.tvSettingsEncryption);
            TextView tvVersion = view.findViewById(R.id.tvSettingsVersion);
            Button btnAutostart = view.findViewById(R.id.btnOpenAutostart);
            Button btnUpdates = view.findViewById(R.id.btnCheckUpdates);
            Button btnClose = view.findViewById(R.id.btnCloseSettings);

            String friendlyDevice = "Unknown Device";
            if ("101".equals(myExtId)) friendlyDevice = "Home Vivo Y51L";
            else if ("102".equals(myExtId)) friendlyDevice = "My OnePlus (CPH2381)";
            else if ("103".equals(myExtId)) friendlyDevice = "Samsung Galaxy S24 Ultra";
            else if ("104".equals(myExtId)) friendlyDevice = "Samsung Galaxy M35 5G";
            else friendlyDevice = Build.MODEL;

            tvDeviceName.setText(friendlyDevice + " (" + Build.MODEL + ")");
            tvExtension.setText("My Calling Number: " + myExtId);
            tvNetwork.setText("Status: " + getNetworkStatusString());
            tvEncryption.setText("Security: AES-256 Military End-to-End Encrypted");

            String verName = "1.2.1";
            try {
                verName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception ignored) {}
            tvVersion.setText("Version: v" + verName + " (Internet Call HD Voice)");

            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setView(view)
                    .create();

            btnAutostart.setOnClickListener(v -> openAutostartSettings(this));

            btnUpdates.setOnClickListener(v -> {
                Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show();
                UpdateManager.checkUpdate(this, true);
                dialog.dismiss();
            });

            btnClose.setOnClickListener(v -> dialog.dismiss());

            dialog.show();
        } catch (Throwable t) {
            Log.e(TAG, "Settings dialog error", t);
        }
    }

    private void startPeriodicStatusRefresh() {
        // Handled dynamically in onResume and onPause to save battery
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
