package com.izzy.kart;

import org.libsdl.app.SDLActivity;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import android.app.AlertDialog;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import java.util.concurrent.CountDownLatch;

import androidx.documentfile.provider.DocumentFile;

import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.view.KeyEvent;

public class MainActivity extends SDLActivity {
    static { System.loadLibrary("Spaghettify"); }

    // ===== Constants / Prefs =====
    private static final String PREFS = "com.izzy.kart.prefs";
    private static final String KEY_USER_FOLDER_URI = "user_folder_uri";
    private static final String TAG = "MainActivity";

    private static final int REQ_PICK_FOLDER = 1001;
    private static final int REQ_PICK_MK64    = 1002;

    // ===== State =====
    SharedPreferences preferences;
    private static final CountDownLatch setupLatch = new CountDownLatch(1);
    private Uri userFolderUri; // Persisted SAF tree URI

    // ===== Native methods =====
    public native void attachController();
    public native void detachController();
    public native void setButton(int button, boolean value);
    public native void setCameraState(int axis, float value);
    public native void setAxis(int axis, short value);

    // ===== Save dir for the engine (internal only; no extra subfolder) =====
    public static String getSaveDir() {
        Context ctx = SDLActivity.getContext();
        File internal = ctx.getFilesDir(); // /data/data/<pkg>/files
        if (!internal.exists()) internal.mkdirs();
        Log.i(TAG, "getSaveDir -> " + internal.getAbsolutePath());
        return internal.getAbsolutePath();
    }

    private Uri getUserFolderUri() {
        String s = preferences.getString(KEY_USER_FOLDER_URI, null);
        return (s != null) ? Uri.parse(s) : null;
    }

    private void setUserFolderUri(Uri uri) {
        preferences.edit().putString(KEY_USER_FOLDER_URI, uri != null ? uri.toString() : null).apply();
    }

    // ===== Lifecycle =====
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        userFolderUri = getUserFolderUri();

        super.onCreate(savedInstanceState);
        setupControllerOverlay();
        attachController();

        // Auto-import from saved SAF folder if present (mk64 + mods + controller db)
        if (userFolderUri != null) {
            try {
                DocumentFile root = DocumentFile.fromTreeUri(this, userFolderUri);
                if (root != null) {
                    boolean imported = importFromUserFolderSaf(root);
                    Log.i(TAG, "Startup importFromUserFolderSaf -> " + imported);
                }
            } catch (Exception e) {
                Log.w(TAG, "Startup import failed: " + e);
            }
        }

        // If mk64.o2r isn't in internal storage yet, prompt the user to pick a folder
        File internalMk64 = new File(getFilesDir(), "mk64.o2r");
        if (!internalMk64.exists()) {
            Log.i(TAG, "mk64.o2r not in internal. Prompting for folder.");
            promptForUserFolder();
        }
    }

    public static void waitForSetupFromNative() {
        try { setupLatch.await(); } catch (InterruptedException ignored) {}
    }

    // ===== UI helpers =====
    private AlertDialog.Builder createPortraitDialog() {
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setOnDismissListener(d -> setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
        return b;
    }

    private void showToast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private void restartApp() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                finish();
                System.exit(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "restartApp", e);
            showToast("Please restart the app manually");
        }
    }

    // ===== Folder / File pickers =====
    private void promptForUserFolder() {
        runOnUiThread(() -> createPortraitDialog()
            .setTitle("Choose Your Folder")
            .setMessage("Pick the folder that contains (or will contain) mk64.o2r and mods. No subfolder will be created.")
            .setCancelable(false)
            .setPositiveButton("Select Folder", (d, w) -> openFolderPicker())
            .show());
    }

    public void openFolderPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK_FOLDER);
    }

    public void openFilePickerForMk64() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK_MK64);
    }

    private void openTorchDownload() {
        try {
            Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/izzy2lost/Torch/releases"));
            Intent chooser = Intent.createChooser(browser, "Open Torch download page with:");
            chooser.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(chooser);
            showToast("Install Torch to create mk64.o2r, then return here.");
        } catch (Exception e) {
            Log.e(TAG, "openTorchDownload", e);
            showToast("Visit: https://github.com/izzy2lost/Torch/releases");
        }
    }

    // ===== Activity result =====
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQ_PICK_FOLDER) {
            handleFolderSelection(data.getData(), data.getFlags());
        } else if (requestCode == REQ_PICK_MK64) {
            handleRomFileSelection(data.getData());
        }
    }

    // ===== Folder selection & copy/import (SAF) =====
    private void handleFolderSelection(Uri treeUri, int returnedFlags) {
        if (treeUri == null) { showToast("No folder selected."); return; }

        // Persist read/write — try returned flags; if 0, try explicit
        try {
            final int permsReturned = returnedFlags &
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (permsReturned != 0) {
                getContentResolver().takePersistableUriPermission(treeUri, permsReturned);
            } else {
                getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
        } catch (Exception e) {
            Log.w(TAG, "takePersistableUriPermission failed (continuing with transient perms): " + e);
        }

        setUserFolderUri(treeUri);
        userFolderUri = treeUri;
        showToast("Folder selected.");

        DocumentFile userRoot = DocumentFile.fromTreeUri(this, treeUri);
        if (userRoot == null) {
            showToast("Cannot access the selected folder.");
            return;
        }

        // First: IMPORT from user folder into INTERNAL (so engine sees Torch output or manual files)
        boolean imported = importFromUserFolderSaf(userRoot);
        Log.i(TAG, "handleFolderSelection importFromUserFolderSaf -> " + imported);

        // Then: EXPORT internal to user folder (optional, keeps user folder populated)
        boolean exported = exportInternalToUserSaf(userRoot);
        Log.i(TAG, "handleFolderSelection exportInternalToUserSaf -> " + exported);

        // Check internal mk64.o2r after import/export
        File internalMk64 = new File(getFilesDir(), "mk64.o2r");
        if (!internalMk64.exists()) {
            runOnUiThread(() -> createPortraitDialog()
                .setTitle("mk64.o2r not found internally")
                .setMessage("Pick an existing mk64.o2r file or use Torch to create one.")
                .setCancelable(false)
                .setPositiveButton("Download Torch App", (d, w) -> openTorchDownload())
                .setNegativeButton("Select mk64.o2r File", (d, w) -> openFilePickerForMk64())
                .show());
        } else {
            runOnUiThread(() -> createPortraitDialog()
                .setTitle("Files ready")
                .setMessage("mk64.o2r and mods have been synchronized. Restart to load the game.")
                .setPositiveButton("Restart", (d, w) -> restartApp())
                .setNegativeButton("Later", null)
                .show());
        }
    }

    // ===== Import from SAF (user folder) → INTERNAL =====
    private boolean importFromUserFolderSaf(DocumentFile userRoot) {
        int imported = 0;
        File internal = getFilesDir();

        // mk64.o2r
        DocumentFile mk = findChild(userRoot, "mk64.o2r");
        if (mk != null && mk.isFile()) {
            if (copySafFileToInternal(mk, new File(internal, "mk64.o2r"))) imported++;
        }

        // gamecontrollerdb.txt (prefer this name). Also accept controllerdb.txt fallback.
        DocumentFile gcdb = findChild(userRoot, "gamecontrollerdb.txt");
        DocumentFile cdb  = (gcdb == null) ? findChild(userRoot, "controllerdb.txt") : null;
        if (gcdb != null && gcdb.isFile()) {
            if (copySafFileToInternal(gcdb, new File(internal, "gamecontrollerdb.txt"))) imported++;
        } else if (cdb != null && cdb.isFile()) {
            if (copySafFileToInternal(cdb, new File(internal, "controllerdb.txt"))) imported++;
        }

        // mods directory (recursive)
        DocumentFile mods = findChild(userRoot, "mods");
        if (mods != null && mods.isDirectory()) {
            if (copySafDirToInternal(mods, new File(internal, "mods"))) imported++;
        }

        if (imported > 0) showToast("Imported " + imported + " item(s) from selected folder.");
        return imported > 0;
    }

    private boolean copySafFileToInternal(DocumentFile src, File dest) {
        try {
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (InputStream in = getContentResolver().openInputStream(src.getUri());
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int r;
                long total = 0;
                while ((r = in.read(buf)) != -1) { out.write(buf, 0, r); total += r; }
                out.flush();
                out.getFD().sync();
                Log.i(TAG, "Imported SAF file " + src.getName() + " (" + total + " bytes) → " + dest.getAbsolutePath());
                return true;
            }
        } catch (IOException e) {
            Log.e(TAG, "copySafFileToInternal " + src.getName(), e);
            showToast("Failed importing " + src.getName());
            return false;
        }
    }

    private boolean copySafDirToInternal(DocumentFile srcDir, File destDir) {
        boolean any = false;
        if (!destDir.exists()) destDir.mkdirs();
        for (DocumentFile child : srcDir.listFiles()) {
            if (child.isDirectory()) {
                if (copySafDirToInternal(child, new File(destDir, child.getName()))) any = true;
            } else {
                File dest = new File(destDir, child.getName());
                if (copySafFileToInternal(child, dest)) any = true;
            }
        }
        return any;
    }

    // ===== Export INTERNAL → SAF (user folder) =====
    private boolean exportInternalToUserSaf(DocumentFile userRoot) {
        int copied = 0;
        File internal = getFilesDir();

        // gamecontrollerdb.txt (prefer real name), fallback to controllerdb.txt
        File gcdb = new File(internal, "gamecontrollerdb.txt");
        File cdb  = new File(internal, "controllerdb.txt");
        if (gcdb.exists()) { if (copyFileToTree(gcdb, userRoot, "text/plain")) copied++; }
        else if (cdb.exists()) { if (copyFileToTree(cdb, userRoot, "text/plain")) copied++; }

        // spaghetti.o2r
        File spaghetti = new File(internal, "spaghetti.o2r");
        if (spaghetti.exists()) { if (copyFileToTree(spaghetti, userRoot, "application/octet-stream")) copied++; }

        // mods
        File modsSrc = new File(internal, "mods");
        if (modsSrc.exists() && modsSrc.isDirectory()) {
            if (copyFolderToTree(modsSrc, userRoot)) copied++;
        }

        if (copied > 0) showToast("Exported " + copied + " item(s) to selected folder.");
        return copied > 0;
    }

    // ===== Helper: copy File → SAF =====
    private boolean copyFileToTree(File src, DocumentFile dstParent, String mimeGuess) {
        try {
            // Overwrite if present
            DocumentFile existing = findChild(dstParent, src.getName());
            if (existing != null && existing.isFile()) existing.delete();

            String mime = (mimeGuess != null) ? mimeGuess : guessMime(src.getName());
            DocumentFile dest = dstParent.createFile(mime, src.getName());
            if (dest == null) {
                Log.e(TAG, "Failed to create file in tree: " + src.getName());
                return false;
            }
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = getContentResolver().openOutputStream(dest.getUri(), "w")) {
                if (out == null) throw new IOException("Null OutputStream from resolver");
                byte[] buf = new byte[8192];
                int r;
                long total = 0;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                    total += r;
                }
                out.flush();
                Log.i(TAG, "Wrote " + total + " bytes → " + dest.getUri());
            }
            Log.i(TAG, "Copied to SAF: " + src.getAbsolutePath() + " → " + dest.getUri());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "copyFileToTree " + src.getName(), e);
            showToast("Failed copying " + src.getName());
            return false;
        }
    }

    // Returns true if any item was copied
    private boolean copyFolderToTree(File srcDir, DocumentFile dstParent) {
        boolean any = false;
        DocumentFile dstDir = ensureDirectory(dstParent, srcDir.getName());
        if (dstDir == null) return false;

        File[] kids = srcDir.listFiles();
        if (kids == null) return false;

        for (File kid : kids) {
            if (kid.isDirectory()) {
                if (copyFolderToTree(kid, dstDir)) any = true;
            } else {
                if (copyFileToTree(kid, dstDir, guessMime(kid.getName()))) any = true;
            }
        }
        return any;
    }

    private DocumentFile ensureDirectory(DocumentFile parent, String name) {
        DocumentFile existing = findChild(parent, name);
        if (existing != null && existing.isDirectory()) return existing;
        if (existing != null) existing.delete();
        return parent.createDirectory(name);
    }

    private DocumentFile findChild(DocumentFile parent, String name) {
        for (DocumentFile f : parent.listFiles()) {
            if (name.equals(f.getName())) return f;
        }
        return null;
    }

    private String guessMime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".txt"))  return "text/plain";
        if (n.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    // ===== Handle direct mk64.o2r selection (SAF file -> INTERNAL) =====
    private void handleRomFileSelection(Uri selectedFileUri) {
        if (selectedFileUri == null) { showToast("No mk64.o2r selected."); return; }

        File dest = new File(getFilesDir(), "mk64.o2r");
        showToast("Copying mk64.o2r...");
        try (InputStream in = getContentResolver().openInputStream(selectedFileUri);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int r;
            long total = 0;
            while ((r = in.read(buf)) != -1) { out.write(buf, 0, r); total += r; }
            out.flush();
            out.getFD().sync();
            Log.i(TAG, "mk64.o2r copied to internal (" + total + " bytes): " + dest.getAbsolutePath());

            runOnUiThread(() -> createPortraitDialog()
                .setTitle("mk64.o2r ready")
                .setMessage("mk64.o2r copied. Restart to load the game.")
                .setPositiveButton("Restart", (d, w) -> restartApp())
                .show());
        } catch (IOException e) {
            Log.e(TAG, "handleRomFileSelection", e);
            showToast("Failed to copy mk64.o2r: " + e.getMessage());
        }
    }

    // ================= Controller overlay and touch handling (unchanged) =================
    private Button buttonA, buttonB, buttonX, buttonY;
    private Button buttonDpadUp, buttonDpadDown, buttonDpadLeft, buttonDpadRight;
    private Button buttonLB, buttonRB, buttonZ, buttonStart, buttonBack, buttonToggle, buttonMenu;
    private FrameLayout leftJoystick;
    private ImageView leftJoystickKnob;
    private View overlayView;

    private void setupControllerOverlay() {
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        overlayView = inflater.inflate(R.layout.touchcontrol_overlay, null);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        overlayView.setLayoutParams(layoutParams);
        ViewGroup rootView = (ViewGroup) this.getWindow().getDecorView().findViewById(android.R.id.content);
        rootView.addView(overlayView);

        final ViewGroup buttonGroup = overlayView.findViewById(R.id.button_group);

        buttonA = overlayView.findViewById(R.id.buttonA);
        buttonB = overlayView.findViewById(R.id.buttonB);
        buttonX = overlayView.findViewById(R.id.buttonX);
        buttonY = overlayView.findViewById(R.id.buttonY);

        buttonDpadUp = overlayView.findViewById(R.id.buttonDpadUp);
        buttonDpadDown = overlayView.findViewById(R.id.buttonDpadDown);
        buttonDpadLeft = overlayView.findViewById(R.id.buttonDpadLeft);
        buttonDpadRight = overlayView.findViewById(R.id.buttonDpadRight);

        buttonLB = overlayView.findViewById(R.id.buttonLB);
        buttonRB = overlayView.findViewById(R.id.buttonRB);
        buttonZ = overlayView.findViewById(R.id.buttonZ);

        buttonStart = overlayView.findViewById(R.id.buttonStart);
        buttonBack = overlayView.findViewById(R.id.buttonBack);
        buttonMenu = overlayView.findViewById(R.id.buttonMenu);

        buttonToggle = overlayView.findViewById(R.id.buttonToggle);

        leftJoystick = overlayView.findViewById(R.id.left_joystick);
        leftJoystickKnob = overlayView.findViewById(R.id.left_joystick_knob);

        FrameLayout rightScreenArea = overlayView.findViewById(R.id.right_screen_area);

        addTouchListener(buttonA, ControllerButtons.BUTTON_A);
        addTouchListener(buttonB, ControllerButtons.BUTTON_B);
        addTouchListener(buttonX, ControllerButtons.BUTTON_X);
        addTouchListener(buttonY, ControllerButtons.BUTTON_Y);

        setupCButtons(buttonDpadUp, ControllerButtons.AXIS_RY, 1);
        setupCButtons(buttonDpadDown, ControllerButtons.AXIS_RY, -1);
        setupCButtons(buttonDpadLeft, ControllerButtons.AXIS_RX, 1);
        setupCButtons(buttonDpadRight, ControllerButtons.AXIS_RX, -1);

        addTouchListener(buttonLB, ControllerButtons.BUTTON_LB);
        addTouchListener(buttonRB, ControllerButtons.BUTTON_RB);
        addTouchListener(buttonZ, ControllerButtons.AXIS_RT);

        addTouchListener(buttonStart, ControllerButtons.BUTTON_START);
        addTouchListener(buttonBack, ControllerButtons.BUTTON_BACK);
        setupMenuButton(buttonMenu);

        setupJoystick(leftJoystick, leftJoystickKnob, true);
        setupLookAround(rightScreenArea);
        setupToggleButton(buttonToggle, buttonGroup);
    }

    private void setupMenuButton(Button button) {
        button.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        onNativeKeyDown(KeyEvent.KEYCODE_ESCAPE);
                        button.setPressed(true);
                        // Toggle menu state and controls
                        MenuOpen = !MenuOpen;
                        if (MenuOpen) {
                            DisableAllControls();
                        } else {
                            EnableAllControls();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        onNativeKeyUp(KeyEvent.KEYCODE_ESCAPE);
                        button.setPressed(false);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        onNativeKeyUp(KeyEvent.KEYCODE_ESCAPE);
                        return true;
                }
                return false;
            }
        });
    }

    private void setupToggleButton(Button button, ViewGroup uiGroup) {
        boolean isHidden = preferences.getBoolean("controlsVisible", false);
        uiGroup.setVisibility(isHidden ? View.INVISIBLE : View.VISIBLE);
        button.setOnClickListener(new View.OnClickListener() {
            boolean isHidden = false;
            @Override
            public void onClick(View v) {
                if (isHidden) {
                    uiGroup.setVisibility(View.VISIBLE);
                } else {
                    uiGroup.setVisibility(View.INVISIBLE);
                }
                preferences.edit().putBoolean("controlsVisible", !isHidden).apply();
                isHidden = !isHidden;
            }
        });
    }

    private void addTouchListener(Button button, int buttonNum) {
        button.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (!AllControlsEnabled) return false;

                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        setButton(buttonNum, true);
                        button.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_UP:
                        setButton(buttonNum, false);
                        button.setPressed(false);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        setButton(buttonNum, false);
                        return true;
                }
                return false;
            }
        });
    }

    private void setupCButtons(Button button, int buttonNum, int direction) {
        button.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (!AllControlsEnabled) return false;

                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        setAxis(buttonNum, direction < 0 ? Short.MAX_VALUE : Short.MIN_VALUE);
                        button.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_UP:
                        setAxis(buttonNum, (short) 0);
                        button.setPressed(false);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        setAxis(buttonNum, (short) 0);
                        return true;
                }
                return false;
            }
        });
    }

    // Control state management
    private boolean TouchAreaEnabled = true;
    private boolean MenuOpen = false;
    private boolean AllControlsEnabled = true;

    private void DisableTouchArea() {
        TouchAreaEnabled = false;
    }

    private void EnableTouchArea() {
        TouchAreaEnabled = true;
    }

    private void DisableAllControls() {
        AllControlsEnabled = false;
        TouchAreaEnabled = false;
    }

    private void EnableAllControls() {
        AllControlsEnabled = true;
        TouchAreaEnabled = true;
    }

    private void setupLookAround(FrameLayout rightScreenArea) {
        rightScreenArea.setOnTouchListener(new View.OnTouchListener() {
            private float lastX = 0;
            private float lastY = 0;
            private boolean isTouching = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = event.getX();
                        lastY = event.getY();
                        isTouching = true;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (isTouching) {
                            float deltaX = event.getX() - lastX;
                            float deltaY = event.getY() - lastY;
                            lastX = event.getX();
                            lastY = event.getY();
                            float sensitivityMultiplier = 15;
                            float rx = (deltaX * sensitivityMultiplier);
                            float ry = (deltaY * sensitivityMultiplier);
                            setCameraState(0, rx);
                            setCameraState(1, ry);
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        isTouching = false;
                        setCameraState(0, 0.0f);
                        setCameraState(1, 0.0f);
                        break;
                }
                return TouchAreaEnabled && AllControlsEnabled;
            }
        });
    }

    private void setupJoystick(FrameLayout joystickLayout, ImageView joystickKnob, boolean isLeft) {
        joystickLayout.post(() -> {
            final float joystickCenterX = joystickLayout.getWidth() / 2f;
            final float joystickCenterY = joystickLayout.getHeight() / 2f;

            joystickLayout.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (!AllControlsEnabled) return false;

                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                        case MotionEvent.ACTION_MOVE:
                            float deltaX = event.getX() - joystickCenterX;
                            float deltaY = event.getY() - joystickCenterY;
                            float maxRadius = joystickLayout.getWidth() / 2f - joystickKnob.getWidth() / 2f;
                            float distance = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
                            if (distance > maxRadius) {
                                float scale = maxRadius / distance;
                                deltaX *= scale;
                                deltaY *= scale;
                            }
                            joystickKnob.setX(joystickCenterX + deltaX - joystickKnob.getWidth() / 2f);
                            joystickKnob.setY(joystickCenterY + deltaY - joystickKnob.getHeight() / 2f);

                            short x = (short) (deltaX / maxRadius * Short.MAX_VALUE);
                            short y = (short) (deltaY / maxRadius * Short.MAX_VALUE);
                            setAxis(isLeft ? ControllerButtons.AXIS_LX : ControllerButtons.AXIS_RX, x);
                            setAxis(isLeft ? ControllerButtons.AXIS_LY : ControllerButtons.AXIS_RY, y);
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            joystickKnob.setX(joystickCenterX - joystickKnob.getWidth() / 2f);
                            joystickKnob.setY(joystickCenterY - joystickKnob.getHeight() / 2f);
                            setAxis(isLeft ? ControllerButtons.AXIS_LX : ControllerButtons.AXIS_RX, (short) 0);
                            setAxis(isLeft ? ControllerButtons.AXIS_LY : ControllerButtons.AXIS_RY, (short) 0);
                            break;
                    }
                    return true;
                }
            });
        });
    }
}
