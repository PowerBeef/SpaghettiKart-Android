package com.izzy.kart;

import org.libsdl.app.SDLActivity;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.provider.DocumentsContract;
import android.database.Cursor;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.os.Build;
import android.widget.Toast;
import android.util.Log;

import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.view.KeyEvent;

import java.util.concurrent.Executors;
import android.app.AlertDialog;

public class MainActivity extends SDLActivity {
static {
    System.loadLibrary("Spaghettify");
}
    SharedPreferences preferences;
    private static final CountDownLatch setupLatch = new CountDownLatch(1);
    private static final int STORAGE_PERMISSION_REQUEST_CODE = 2296;
    private static final int FILE_PICKER_REQUEST_CODE = 0;
    private File targetRootFolder; // User-selected folder for mods and mk64.o2r
    private File romTargetFile; // Will hold the mk64.o2r destination in user folder
    private Uri userFolderUri; // SAF URI for user-selected folder
    private volatile boolean romFileReady = false;

    public static String getSaveDir() {
        // Return user-selected folder if available, otherwise app work dir
        try {
            Context context = SDLActivity.getContext();
            if (context != null) {
                SharedPreferences prefs = context.getSharedPreferences("com.izzy.kart.prefs", Context.MODE_PRIVATE);
                String userPath = prefs.getString("chosen_folder_path", null);
                if (userPath != null && !userPath.isEmpty()) {
                    File userDir = new File(userPath);
                    if (userDir.exists() || userDir.mkdirs()) {
                        Log.i("MainActivity", "getSaveDir returning user folder: " + userDir.getAbsolutePath());
                        return userDir.getAbsolutePath();
                    }
                }
                
                // Fallback to app work dir
                File workDir = new File(context.getExternalFilesDir(null), "Spaghetti-Kart");
                if (!workDir.exists()) workDir.mkdirs();
                Log.i("MainActivity", "getSaveDir returning app work dir: " + workDir.getAbsolutePath());
                return workDir.getAbsolutePath();
            }
        } catch (Exception e) {
            Log.w("MainActivity", "Could not get save dir, falling back to internal files dir", e);
        }
        // Final fallback to internal files dir
        Context context = SDLActivity.getContext();
        File internal = new File(context.getFilesDir(), "Spaghetti-Kart");
        if (!internal.exists()) internal.mkdirs();
        Log.i("MainActivity", "getSaveDir returning internal dir: " + internal.getAbsolutePath());
        return internal.getAbsolutePath();
    }
    
    private String getUserChosenFolder() {
        // Return user-chosen folder path if available
        return preferences.getString("chosen_folder_path", null);
    }
    
    private void setUserChosenFolder(String folderPath) {
        preferences.edit().putString("chosen_folder_path", folderPath).apply();
    }
    
    private void setUserFolderUri(Uri uri) {
        if (uri != null) {
            preferences.edit().putString("user_folder_uri", uri.toString()).apply();
        }
    }
    
    private Uri getUserFolderUri() {
        String uriString = preferences.getString("user_folder_uri", null);
        return uriString != null ? Uri.parse(uriString) : null;
    }

    private File getAppWorkDir() {
        // Return the root external files directory, not a subfolder
        File dir = getExternalFilesDir(null);
        if (dir != null && !dir.exists()) dir.mkdirs();
        return dir;
    }
    
    private String tryGetPathFromUri(Uri treeUri) {
        try {
            // Store the SAF URI string for reference, but don't extract raw path
            String treeId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
            Log.i("MainActivity", "Selected folder URI: " + treeUri + ", treeId: " + treeId);
            
            // Return a descriptive path for display purposes only
            if (treeId.startsWith("primary:")) {
                String relativePath = treeId.substring("primary:".length());
                return "/storage/emulated/0/" + relativePath;
            }
            
            return "Selected folder: " + treeId;
            
        } catch (Exception e) {
            Log.e("MainActivity", "Error processing URI", e);
            return "Selected folder";
        }
    }
    
    private void copyEssentialFilesToFolder() {
        try {
            // Always ensure essential files are in app work directory first (for fallback)
            File appWorkDir = getAppWorkDir();
            File appSkO2rFile = new File(appWorkDir, "spaghetti.o2r");
            File appGameControllerDb = new File(appWorkDir, "gamecontrollerdb.txt");
            
            if (!appSkO2rFile.exists()) copyAssetFile("spaghetti.o2r", appSkO2rFile);
            if (!appGameControllerDb.exists()) copyAssetFile("gamecontrollerdb.txt", appGameControllerDb);
            
            Log.i("MainActivity", "Essential files ensured in app work dir: " + appWorkDir.getAbsolutePath());
            
            // If user has selected a folder, also copy files there
            if (targetRootFolder != null && targetRootFolder.exists() && !targetRootFolder.equals(appWorkDir)) {
                File userSkO2rFile = new File(targetRootFolder, "spaghetti.o2r");
                File userGameControllerDb = new File(targetRootFolder, "gamecontrollerdb.txt");
                File userModsDir = new File(targetRootFolder, "mods");
                
                if (!userSkO2rFile.exists()) copyAssetFile("spaghetti.o2r", userSkO2rFile);
                if (!userGameControllerDb.exists()) copyAssetFile("gamecontrollerdb.txt", userGameControllerDb);
                if (!userModsDir.exists()) userModsDir.mkdirs();
                
                Log.i("MainActivity", "Essential files also copied to user folder: " + targetRootFolder.getAbsolutePath());
            }
            
        } catch (Exception e) {
            Log.e("MainActivity", "Error copying essential files", e);
            showToast("Warning: Some game files may be missing");
        }
    }
    

    
    private void restartApp() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                finish();
                System.exit(0);
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Error restarting app", e);
            showToast("Please restart the app manually");
        }
    }
    
    private AlertDialog.Builder createPortraitDialog() {
        // Temporarily switch to portrait for dialogs
        int currentOrientation = getRequestedOrientation();
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        
        // Set a listener to restore landscape when dialog is dismissed
        builder.setOnDismissListener(dialog -> {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        });
        
        return builder;
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        preferences = getSharedPreferences("com.izzy.kart.prefs", Context.MODE_PRIVATE);
        
        // Initialize user folder if previously selected
        userFolderUri = getUserFolderUri();
        String userFolderPath = getUserChosenFolder();
        if (userFolderPath != null) {
            targetRootFolder = new File(userFolderPath);
            romTargetFile = new File(targetRootFolder, "mk64.o2r");
        } else {
            // Fallback to app work directory temporarily
            targetRootFolder = getAppWorkDir();
            romTargetFile = new File(targetRootFolder, "mk64.o2r");
        }

        // Always start SDL and setup controller overlay
        super.onCreate(savedInstanceState);
        setupControllerOverlay();
        attachController();
        
        // Copy essential files to app work dir first
        copyEssentialFilesToFolder();
        
        // Check if mk64.o2r exists - if not, start setup UI immediately
        // Engine.cpp will poll for the file to exist
        if (!romTargetFile.exists()) {
            checkAndSetupFiles();
        }
    }

    public static void waitForSetupFromNative() {
        try {
            setupLatch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // Permissions helpers - no longer need storage permissions for app-specific dirs
    private boolean hasStoragePermission() {
        // App-specific external storage doesn't require permissions
        return true;
    }

    private void requestStoragePermission() {
        // No longer need to request storage permissions - directly setup files
        checkAndSetupFiles();
    }

// Check & Setup Files 
public void checkAndSetupFiles() {
    // Don't create any files yet - just ask user to choose their folder first
    runOnUiThread(() -> createPortraitDialog()
        .setTitle("Choose Your Folder")
        .setMessage("Please select the folder where you want to store your Spaghetti Kart files. We suggest creating a 'Spaghetti-Kart' folder.")
        .setCancelable(false)
        .setPositiveButton("Select Folder", (dialog, which) -> openFolderPicker())
        .show());
}

    // Helper methods for asset copying
    private void copyAssetFolder(String assetFolderName, String destPath) {
        try {
            File dest = new File(destPath);
            if (!dest.exists()) dest.mkdirs();
            AssetCopyUtil.copyAssetsToExternal(this, assetFolderName, destPath);
            showToast(assetFolderName + " copied");
        } catch (IOException e) {
            showToast("Error copying " + assetFolderName);
        }
    }

    private void copyAssetFile(String assetFileName, File destFile) {
        try {
            // Ensure parent directory exists
            File parentDir = destFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            
            try (InputStream in = getAssets().open(assetFileName);
                 OutputStream out = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                Log.i("MainActivity", "Successfully copied " + assetFileName + " to " + destFile.getAbsolutePath());
            }
        } catch (IOException e) {
            Log.e("MainActivity", "Error copying " + assetFileName + " to " + destFile.getAbsolutePath(), e);
            showToast("Error copying " + assetFileName + ": " + e.getMessage());
        }
    }

    private void showToast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_PICKER_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            handleFolderSelection(data.getData());
        }
        // No longer handle STORAGE_PERMISSION_REQUEST_CODE - not needed
    }

    public void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, FILE_PICKER_REQUEST_CODE);
    }

    public void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, FILE_PICKER_REQUEST_CODE);
    }

    private void openTorchDownload() {
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/izzy2lost/Torch/releases"));
            // Create a chooser to let user pick browser and avoid download managers
            Intent chooser = Intent.createChooser(browserIntent, "Open Torch download page with:");
            chooser.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(chooser);
            showToast("Opening Torch app download page. After installing Torch, use it to create mk64.o2r from your ROM, then return here.");
        } catch (Exception e) {
            Log.e("MainActivity", "Error opening Torch download link", e);
            showToast("Unable to open download link. Please visit: https://github.com/izzy2lost/Torch/releases");
        }
    }

    private void handleFolderSelection(Uri selectedFolderUri) {
        if (selectedFolderUri == null) {
            showToast("No folder selected.");
            return;
        }

        // Store folder URI and path for user files (mods, mk64.o2r)
        setUserFolderUri(selectedFolderUri);
        String folderPath = tryGetPathFromUri(selectedFolderUri);
        if (folderPath != null) {
            setUserChosenFolder(folderPath);
            targetRootFolder = new File(folderPath);
            romTargetFile = new File(targetRootFolder, "mk64.o2r");
            
            // Create the user folder and copy essential files
            copyEssentialFilesToFolder();
            
            showToast("Folder selected: " + folderPath);
        }

        // Now check if mk64.o2r exists in the selected folder
        try {
            android.content.ContentResolver resolver = getContentResolver();
            android.database.Cursor cursor = resolver.query(
                android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(selectedFolderUri, android.provider.DocumentsContract.getTreeDocumentId(selectedFolderUri)),
                new String[]{android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null
            );

            Uri mk64O2rUri = null;
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String displayName = cursor.getString(cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME));
                    if ("mk64.o2r".equals(displayName)) {
                        String documentId = cursor.getString(cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID));
                        mk64O2rUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(selectedFolderUri, documentId);
                        break;
                    }
                }
                cursor.close();
            }

            if (mk64O2rUri != null) {
                // Found mk64.o2r in the selected folder - copy it and restart
                final Uri finalMk64O2rUri = mk64O2rUri;
                handleRomFileSelection(finalMk64O2rUri);
            } else {
                // mk64.o2r not found - show torch/file picker options
                runOnUiThread(() -> createPortraitDialog()
                    .setTitle("mk64.o2r not found")
                    .setMessage("mk64.o2r file was not found in the selected folder. You can:\n\n• Download Torch app to create mk64.o2r from your ROM\n• Select the mk64.o2r file directly\n\nNote: You will need to restart the app after adding the mk64.o2r file.")
                    .setCancelable(false)
                    .setPositiveButton("Download Torch App", (dialog, which) -> openTorchDownload())
                    .setNegativeButton("Select mk64.o2r File", (dialog, which) -> openFilePicker())
                    .show());
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Error searching for mk64.o2r in folder", e);
            showToast("Error accessing selected folder. Please try selecting the mk64.o2r file directly.");
            openFilePicker();
        }
    }

    private void handleRomFileSelection(Uri selectedFileUri) {
        if (selectedFileUri == null) {
            showToast("No mk64.o2r file selected.");
            return;
        }

        // Show progress to user
        showToast("Copying mk64.o2r file...");

        try (InputStream in = getContentResolver().openInputStream(selectedFileUri);
             FileOutputStream out = new FileOutputStream(romTargetFile)) {
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            
            // Ensure all data is written to disk
            out.flush();
            out.getFD().sync();
            
            Log.i("MainActivity", "mk64.o2r file copied to user folder successfully, size: " + romTargetFile.length() + " bytes");
            
            // Show restart dialog
            runOnUiThread(() -> createPortraitDialog()
                .setTitle("mk64.o2r file ready!")
                .setMessage("The mk64.o2r file has been copied to your selected folder. The app will now restart to load the game.")
                .setCancelable(false)
                .setPositiveButton("Restart App", (dialog, which) -> restartApp())
                .show());
            
        } catch (IOException e) {
            Log.e("MainActivity", "Error copying mk64.o2r file", e);
            showToast("Failed to copy mk64.o2r: " + e.getMessage());
        }
    }



    // Native methods
    public native void attachController();
    public native void detachController();
    public native void setButton(int button, boolean value);
    public native void setCameraState(int axis, float value);
    public native void setAxis(int axis, short value);

    // Controller overlay and touch handling
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
