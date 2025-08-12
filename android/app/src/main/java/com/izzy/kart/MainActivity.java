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
    static {
        System.loadLibrary("Spaghettify");
    }

    // ===== Constants / Prefs =====
    private static final String PREFS = "com.izzy.kart.prefs";
    private static final String KEY_USER_FOLDER_URI = "user_folder_uri";

    private static final int REQ_PICK_FOLDER = 1001;
    private static final int REQ_PICK_MK64    = 1002;

    // Filenames of interest
    private static final String F_CONTROLLER_DB_1 = "controllerdb.txt";
    private static final String F_CONTROLLER_DB_2 = "gamecontrollerdb.txt"; // legacy name
    private static final String F_SPAGHETTI       = "spaghetti.o2r";
    private static final String D_MODS            = "mods";
    private static final String F_MK64            = "mk64.o2r";

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

    // ===== Save dir for the engine (internal runtime cache) =====
    // Note: Native code expects real filesystem paths; SAF exposes URIs only.
    // We keep INTERNAL as runtime cache and mirror to/from SAF automatically.
    public static String getSaveDir() {
        Context ctx = SDLActivity.getContext();
        File internal = ctx.getFilesDir();
        if (!internal.exists()) internal.mkdirs();
        Log.i("MainActivity", "getSaveDir -> " + internal.getAbsolutePath());
        return internal.getAbsolutePath();
    }

    private Uri getUserFolderUri() {
        String s = preferences.getString(KEY_USER_FOLDER_URI, null);
        return (s != null) ? Uri.parse(s) : null;
    }

    private void setUserFolderUri(Uri uri) {
        preferences.edit().putString(KEY_USER_FOLDER_URI, uri != null ? uri.toString() : null).apply();
    }

    private DocumentFile getUserRoot() {
        if (userFolderUri == null) return null;
        return DocumentFile.fromTreeUri(this, userFolderUri);
    }

    // ===== Lifecycle =====
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        userFolderUri = getUserFolderUri();

        // Start SDL and overlay
        super.onCreate(savedInstanceState);
        setupControllerOverlay();
        attachController();

        if (userFolderUri == null) {
            promptForUserFolder();
        } else {
            // On launch, make SAF the source of truth and sync to internal runtime cache
            syncBothWaysPreferSaf();
            // Optional: if mk64.o2r still missing internally after sync, prompt user
            File internalMk64 = new File(getFilesDir(), F_MK64);
            if (!internalMk64.exists()) {
                promptForMk64();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // On suspend, push internal changes back to SAF so user's folder stays current
        if (userFolderUri != null) {
            syncInternalToSaf();
        }
    }

    public static void waitForSetupFromNative() {
        try { setupLatch.await(); } catch (InterruptedException ignored) {}
    }

    // ===== UI helpers =====
    private AlertDialog.Builder createPortraitDialog() {
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setOnDismissListener(d ->
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        );
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
            Log.e("MainActivity", "restartApp", e);
            showToast("Please restart the app manually");
        }
    }

    private void promptForUserFolder() {
        runOnUiThread(() -> createPortraitDialog()
            .setTitle("Choose Your Folder")
            .setMessage("Pick the folder to store your game files. We'll read and write using Android's Storage Access Framework (SAF).")
            .setCancelable(false)
            .setPositiveButton("Select Folder", (d, w) -> openFolderPicker())
            .show());
    }

    private void promptForMk64() {
        runOnUiThread(() -> createPortraitDialog()
            .setTitle("mk64.o2r not found")
            .setMessage("Select an existing mk64.o2r file, or install Torch to create one from your ROM.")
            .setCancelable(false)
            .setPositiveButton("Download Torch App", (d, w) -> openTorchDownload())
            .setNegativeButton("Select mk64.o2r File", (d, w) -> openFilePickerForMk64())
            .show());
    }

    // ===== Folder / File pickers =====
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
            Log.e("MainActivity", "openTorchDownload", e);
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
            handleMk64Selection(data.getData());
        }
    }

    // ===== Folder selection & SAF-first sync =====
    private void handleFolderSelection(Uri treeUri, int returnedFlags) {
        if (treeUri == null) { showToast("No folder selected."); return; }

        // Persist read/write
        try {
            final int perms = returnedFlags &
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(treeUri, perms);
        } catch (Exception ignored) {}

        setUserFolderUri(treeUri);
        userFolderUri = treeUri;
        showToast("Folder selected.");

        // Make SAF authoritative and sync into internal cache
        syncBothWaysPreferSaf();

        // If mk64 still not present internally, prompt
        File internalMk64 = new File(getFilesDir(), F_MK64);
        if (!internalMk64.exists()) {
            promptForMk64();
        } else {
            runOnUiThread(() -> createPortraitDialog()
                .setTitle("Ready")
                .setMessage("Files are in sync. Restart to load the game.")
                .setPositiveButton("Restart", (d, w) -> restartApp())
                .setNegativeButton("Later", null)
                .show());
        }
    }

    // When user selects mk64.o2r via SAF file picker (any location),
    // copy into SAF userRoot (if set) and internal cache.
    private void handleMk64Selection(Uri selectedFileUri) {
        if (selectedFileUri == null) { showToast("No mk64.o2r selected."); return; }

        showToast("Importing mk64.o2r...");
        try {
            // If userRoot is set, copy into it first
            DocumentFile userRoot = getUserRoot();
            if (userRoot != null && userRoot.canWrite()) {
                // Overwrite mk64.o2r in SAF root
                copyContentUriToTree(selectedFileUri, userRoot, F_MK64, "application/octet-stream");
            }

            // Always update internal runtime copy
            File destInternal = new File(getFilesDir(), F_MK64);
            try (InputStream in = getContentResolver().openInputStream(selectedFileUri);
                 FileOutputStream out = new FileOutputStream(destInternal)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                out.flush();
                out.getFD().sync();
            }

            runOnUiThread(() -> createPortraitDialog()
                .setTitle("mk64.o2r ready")
                .setMessage("mk64.o2r imported. Restart to load the game.")
                .setPositiveButton("Restart", (d, w) -> restartApp())
                .show());
        } catch (IOException e) {
            Log.e("MainActivity", "handleMk64Selection", e);
            showToast("Failed to import mk64.o2r: " + e.getMessage());
        }
    }

    // ===== Sync logic =====

    // Full sync where SAF is the source of truth:
    // - If exists only on SAF -> copy to internal
    // - If exists only internal -> copy to SAF
    // - If both exist -> compare lastModified and copy newer over older
    private void syncBothWaysPreferSaf() {
        DocumentFile userRoot = getUserRoot();
        if (userRoot == null || !userRoot.canWrite()) {
            showToast("Selected folder is unavailable or read-only.");
            return;
        }

        File internal = getFilesDir();
        if (!internal.exists()) internal.mkdirs();

        // Controller DB (either name)
        bidirSyncFile(userRoot, internal, F_CONTROLLER_DB_1, "text/plain");
        bidirSyncFile(userRoot, internal, F_CONTROLLER_DB_2, "text/plain");

        // spaghetti.o2r
        bidirSyncFile(userRoot, internal, F_SPAGHETTI, "application/octet-stream");

        // mk64.o2r
        bidirSyncFile(userRoot, internal, F_MK64, "application/octet-stream");

        // mods directory
        bidirSyncFolder(userRoot, internal, D_MODS);
    }

    // Push everything internal -> SAF (used onPause to export saves/configs)
    private void syncInternalToSaf() {
        DocumentFile userRoot = getUserRoot();
        if (userRoot == null || !userRoot.canWrite()) return;

        File internal = getFilesDir();
        pushFileIfExists(internal, userRoot, F_CONTROLLER_DB_1, "text/plain");
        pushFileIfExists(internal, userRoot, F_CONTROLLER_DB_2, "text/plain");
        pushFileIfExists(internal, userRoot, F_SPAGHETTI, "application/octet-stream");
        pushFileIfExists(internal, userRoot, F_MK64, "application/octet-stream");
        pushFolderIfExists(internal, userRoot, D_MODS);
    }

    private void pushFileIfExists(File internalRoot, DocumentFile userRoot, String name, String mime) {
        File src = new File(internalRoot, name);
        if (src.exists() && src.isFile()) {
            copyFileToTree(src, userRoot, mime);
        }
    }

    private void pushFolderIfExists(File internalRoot, DocumentFile userRoot, String dirName) {
        File srcDir = new File(internalRoot, dirName);
        if (srcDir.exists() && srcDir.isDirectory()) {
            copyFolderToTree(srcDir, userRoot);
        }
    }

    private void bidirSyncFile(DocumentFile userRoot, File internalRoot, String name, String mime) {
        DocumentFile safFile = findChild(userRoot, name);
        File internalFile = new File(internalRoot, name);

        boolean hasSaf = safFile != null && safFile.isFile();
        boolean hasInt = internalFile.exists() && internalFile.isFile();

        if (hasSaf && !hasInt) {
            copyTreeToFile(safFile, internalFile);
            return;
        }
        if (!hasSaf && hasInt) {
            copyFileToTree(internalFile, userRoot, mime);
            return;
        }
        if (hasSaf && hasInt) {
            long safTime = safFile.lastModified();
            long intTime = internalFile.lastModified();
            // Prefer SAF when in doubt
            if (safTime >= intTime) {
                copyTreeToFile(safFile, internalFile);
            } else {
                copyFileToTree(internalFile, userRoot, mime);
            }
        }
    }

    private void bidirSyncFolder(DocumentFile userRoot, File internalRoot, String dirName) {
        DocumentFile safDir = findChild(userRoot, dirName);
        File intDir = new File(internalRoot, dirName);

        boolean hasSaf = safDir != null && safDir.isDirectory();
        boolean hasInt = intDir.exists() && intDir.isDirectory();

        if (hasSaf && !hasInt) {
            copyTreeFolderToInternal(safDir, intDir);
            return;
        }
        if (!hasSaf && hasInt) {
            copyFolderToTree(intDir, userRoot);
            return;
        }
        if (hasSaf && hasInt) {
            // Merge: pull newer files from SAF, push newer from internal
            // For simplicity, pull SAF first then push internal (will overwrite older)
            copyTreeFolderToInternal(safDir, intDir);
            copyFolderToTree(intDir, userRoot);
        }
    }

    // ===== SAF and File helpers =====
    private void copyFileToTree(File src, DocumentFile dstParent, String mimeGuess) {
        try {
            // Overwrite if present
            DocumentFile existing = findChild(dstParent, src.getName());
            if (existing != null && existing.isFile()) existing.delete();

            String mime = (mimeGuess != null) ? mimeGuess : guessMime(src.getName());
            DocumentFile dest = dstParent.createFile(mime, src.getName());
            if (dest == null) {
                Log.e("MainActivity", "Failed to create file in tree: " + src.getName());
                return;
            }
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = getContentResolver().openOutputStream(dest.getUri(), "w")) {
                if (out == null) throw new IOException("Null OutputStream from resolver");
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                out.flush();
            }
            Log.i("MainActivity", "Copied to SAF: " + src.getAbsolutePath() + " → " + dest.getUri());
        } catch (IOException e) {
            Log.e("MainActivity", "copyFileToTree " + src.getName(), e);
            showToast("Failed copying " + src.getName());
        }
    }

    private void copyContentUriToTree(Uri srcUri, DocumentFile dstParent, String outName, String mime) throws IOException {
        DocumentFile existing = findChild(dstParent, outName);
        if (existing != null && existing.isFile()) existing.delete();

        DocumentFile dest = dstParent.createFile(mime, outName);
        if (dest == null) throw new IOException("Failed to create SAF file: " + outName);

        try (InputStream in = getContentResolver().openInputStream(srcUri);
             OutputStream out = getContentResolver().openOutputStream(dest.getUri(), "w")) {
            if (in == null || out == null) throw new IOException("Resolver stream null");
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
            out.flush();
        }
    }

    private void copyTreeToFile(DocumentFile src, File dest) {
        try {
            // Ensure parent exists
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            try (InputStream in = getContentResolver().openInputStream(src.getUri());
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                out.flush();
                out.getFD().sync();
            }
            if (src.lastModified() > 0) {
                // Try to keep timestamps roughly aligned
                dest.setLastModified(src.lastModified());
            }
            Log.i("MainActivity", "Copied SAF → internal: " + src.getName() + " -> " + dest.getAbsolutePath());
        } catch (IOException e) {
            Log.e("MainActivity", "copyTreeToFile " + src.getName(), e);
            showToast("Failed importing " + src.getName());
        }
    }

    private void copyFolderToTree(File srcDir, DocumentFile dstParent) {
        DocumentFile dstDir = ensureDirectory(dstParent, srcDir.getName());
        if (dstDir == null) return;

        File[] kids = srcDir.listFiles();
        if (kids == null) return;

        for (File kid : kids) {
            if (kid.isDirectory()) {
                copyFolderToTree(kid, dstDir);
            } else {
                copyFileToTree(kid, dstDir, guessMime(kid.getName()));
            }
        }
    }

    private void copyTreeFolderToInternal(DocumentFile srcDir, File dstDir) {
        if (!dstDir.exists()) dstDir.mkdirs();
        DocumentFile[] kids = srcDir.listFiles();
        if (kids == null) return;

        for (DocumentFile kid : kids) {
            if (kid.isDirectory()) {
                copyTreeFolderToInternal(kid, new File(dstDir, kid.getName()));
            } else if (kid.isFile()) {
                copyTreeToFile(kid, new File(dstDir, kid.getName()));
            }
        }
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
