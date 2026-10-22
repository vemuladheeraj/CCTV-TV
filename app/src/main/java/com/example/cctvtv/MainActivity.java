package com.example.cctvtv;

import android.util.Log;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;
import org.json.JSONArray;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Dheeraj Lens: configurable RTSP viewer with encrypted local settings and per-camera audio. */
public class MainActivity extends Activity {
    private static final String PREFS = "dheeraj_lens_secure_settings";
    private static final String PREF_CIPHER_TEXT = "settings_ciphertext";
    private static final String KEY_ALIAS = "dheeraj_lens_settings_key_v1";
    private static final String DEFAULT_PATH = "/cam/realmonitor?channel={channel}&subtype={subtype}";
    private static final int BG = Color.rgb(11, 16, 27);
    private static final int PANEL = Color.rgb(20, 28, 43);
    private static final int PANEL_2 = Color.rgb(28, 39, 58);
    private static final int ACCENT = Color.rgb(69, 145, 255);
    private static final int TEXT = Color.rgb(240, 245, 255);
    private static final int MUTED = Color.rgb(155, 171, 194);

    private final List<ExoPlayer> players = new ArrayList<>();
    private final List<FrameLayout> tiles = new ArrayList<>();
    private final List<PlayerView> playerViews = new ArrayList<>();
    private final List<TextView> cameraStatuses = new ArrayList<>();
    private final List<Button> audioButtons = new ArrayList<>();
    private final List<Button> cameraQualityButtons = new ArrayList<>();
    private final List<String> cameraStateLabels = new ArrayList<>();
    private final List<Integer> cameraSubtypes = new ArrayList<>();

    private LinearLayout root, settingsPanel, gridContainer;
    private ViewGroup cameraLayout;
    private FrameLayout screen;
    private GridLayout grid;
    private TextView globalStatus, audioNowPlaying;
    private EditText hostInput, portInput, usernameInput, passwordInput, pathInput, countInput;
    private Button settingsToggle, settingsCloseButton, connectButton;
    private Button passwordVisibilityToggle;
    private int fullscreenCamera = -1;
    private int audioCamera = -1;
    private int cameraCount = 4;
    private int gridColumns = 2;
    private boolean settingsVisible = false;
    private boolean connectionAttempted = false;
    private String connectionHint;
    private JSONObject activeSettings;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Runnable> pendingRetries = new ArrayList<>();

    private static final MediaCodecSelector CCTV_MEDIA_CODEC_SELECTOR = (mimeType, reqSecure, reqTunneling) -> {
        List<MediaCodecInfo> decoders =
                new ArrayList<>(MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, reqSecure, reqTunneling));
        // Sort software decoders first so multiple concurrent feeds avoid
        // hardware decoder memory/instance exhaustion on Smart TV chipsets.
        Collections.sort(decoders, (a, b) -> {
            if (a.softwareOnly && !b.softwareOnly) return -1;
            if (!a.softwareOnly && b.softwareOnly) return 1;
            return 0;
        });
        return decoders;
    };

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        buildUi();
        JSONObject savedSettings = readEncryptedSettings();
        loadSettingsIntoFields(savedSettings);
        settingsPanel.setVisibility(View.GONE);
        settingsVisible = false;
        settingsToggle.requestFocus();
        if (hasSavedConnectionSettings(savedSettings)) {
            globalStatus.setText("Reconnecting to saved DVR…");
            root.post(this::connectCameras);
        }
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(6), dp(12), dp(6));
        root.setFocusable(false);
        root.setFocusableInTouchMode(false);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("DHEERAJ LENS");
        title.setTextColor(TEXT);
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView subtitle = new TextView(this);
        subtitle.setText("YOUR CAMERAS · LIVE VIEW");
        subtitle.setTextColor(MUTED);
        subtitle.setTextSize(10);
        brand.addView(title);
        brand.addView(subtitle);
        header.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        settingsToggle = makeButton("⚙  Settings", false);
        settingsToggle.setId(View.generateViewId());
        settingsToggle.setOnClickListener(v -> toggleSettings());
        header.addView(settingsToggle);
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        settingsPanel = new LinearLayout(this);
        settingsPanel.setOrientation(LinearLayout.VERTICAL);
        settingsPanel.setPadding(dp(12), dp(10), dp(12), dp(10));
        settingsPanel.setBackground(roundRect(PANEL, dp(14), Color.rgb(42, 57, 80), dp(1)));
        settingsPanel.setElevation(dp(12));
        LinearLayout settingsHeading = horizontalRow();
        TextView settingsTitle = new TextView(this);
        settingsTitle.setText("CONNECTION SETTINGS");
        settingsTitle.setTextColor(TEXT);
        settingsTitle.setTextSize(12);
        settingsTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        settingsHeading.addView(settingsTitle, new LinearLayout.LayoutParams(0, dp(36), 1));
        settingsCloseButton = makeButton("Close", false);
        settingsCloseButton.setOnClickListener(v -> toggleSettings());
        settingsHeading.addView(settingsCloseButton);
        settingsPanel.addView(settingsHeading);
        TextView typingHelp = new TextView(this);
        typingHelp.setText("Select a field and press OK to type. Use Next to move between fields.");
        typingHelp.setTextColor(MUTED);
        typingHelp.setTextSize(11);
        typingHelp.setPadding(dp(4), 0, dp(4), dp(4));
        settingsPanel.addView(typingHelp);

        LinearLayout row1 = horizontalRow();
        hostInput = field("DVR IP or hostname",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        portInput = field("RTSP port", InputType.TYPE_CLASS_NUMBER);
        row1.addView(fieldWrap("DVR ADDRESS", hostInput), new LinearLayout.LayoutParams(0, -2, 2f));
        row1.addView(fieldWrap("RTSP PORT", portInput), new LinearLayout.LayoutParams(0, -2, 1f));
        settingsPanel.addView(row1);

        LinearLayout row2 = horizontalRow();
        usernameInput = field("Username",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        passwordInput = field("Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordVisibilityToggle = makeButton("👁", false);
        passwordVisibilityToggle.setTextSize(18);
        passwordVisibilityToggle.setContentDescription("Show password");
        passwordVisibilityToggle.setOnClickListener(v -> togglePasswordVisibility());
        LinearLayout passwordRow = horizontalRow();
        LinearLayout.LayoutParams passwordInputParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        passwordInputParams.setMargins(dp(4), dp(4), dp(2), dp(6));
        passwordInput.setLayoutParams(passwordInputParams);
        LinearLayout.LayoutParams passwordToggleParams = new LinearLayout.LayoutParams(dp(50), dp(50));
        passwordToggleParams.setMargins(dp(2), dp(4), dp(4), dp(6));
        passwordVisibilityToggle.setLayoutParams(passwordToggleParams);
        passwordRow.addView(passwordInput);
        passwordRow.addView(passwordVisibilityToggle);
        row2.addView(fieldWrap("USERNAME", usernameInput), new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout passwordWrap = new LinearLayout(this);
        passwordWrap.setOrientation(LinearLayout.VERTICAL);
        TextView passwordCaption = new TextView(this);
        passwordCaption.setText("PASSWORD");
        passwordCaption.setTextColor(MUTED);
        passwordCaption.setTextSize(10);
        passwordCaption.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        passwordCaption.setPadding(dp(4), dp(3), 0, 0);
        passwordWrap.addView(passwordCaption);
        passwordWrap.addView(passwordRow);
        row2.addView(passwordWrap, new LinearLayout.LayoutParams(0, -2, 1f));
        settingsPanel.addView(row2);

        LinearLayout row3 = horizontalRow();
        pathInput = field("RTSP path template",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        countInput = field("Camera count", InputType.TYPE_CLASS_NUMBER);
        row3.addView(fieldWrap("RTSP PATH TEMPLATE", pathInput), new LinearLayout.LayoutParams(0, -2, 4f));
        row3.addView(fieldWrap("CAMERAS (1–16)", countInput), new LinearLayout.LayoutParams(0, -2, 1f));
        settingsPanel.addView(row3);

        TextView pathHelp = new TextView(this);
        pathHelp.setText("Use {channel} and {subtype} in the path. Cameras start on SD (subtype 1); choose HD or SD independently on each tile. Example: " + DEFAULT_PATH);
        pathHelp.setTextColor(MUTED);
        pathHelp.setTextSize(10);
        pathHelp.setPadding(0, dp(4), 0, dp(6));
        settingsPanel.addView(pathHelp);

        LinearLayout actions = horizontalRow();
        Button saveButton = makeButton("Save settings", false);
        saveButton.setOnClickListener(v -> {
            if (saveSettingsFromFields()) {
                globalStatus.setText("Settings saved. Select Save & Connect to start the camera feeds.");
                Toast.makeText(this, "Settings saved. Select Save & Connect to start the feeds.", Toast.LENGTH_SHORT).show();
            }
        });
        connectButton = makeButton("▶  Save & Connect", true);
        connectButton.setOnClickListener(v -> connectCameras());
        actions.addView(saveButton, new LinearLayout.LayoutParams(0, dp(42), 1));
        actions.addView(connectButton, new LinearLayout.LayoutParams(0, dp(42), 1));
        settingsPanel.addView(actions);
        configureSettingsFocus(saveButton);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(dp(2), dp(2), dp(2), dp(2));
        globalStatus = new TextView(this);
        globalStatus.setText("Open Settings, verify DVR details, then select Save & Connect.");
        globalStatus.setTextColor(MUTED);
        globalStatus.setTextSize(12);
        statusRow.addView(globalStatus, new LinearLayout.LayoutParams(0, dp(32), 1));
        audioNowPlaying = new TextView(this);
        audioNowPlaying.setText("🔇 Audio off");
        audioNowPlaying.setTextColor(MUTED);
        audioNowPlaying.setTextSize(11);
        statusRow.addView(audioNowPlaying);
        root.addView(statusRow);

        gridContainer = new LinearLayout(this);
        gridContainer.setOrientation(LinearLayout.VERTICAL);
        grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setUseDefaultMargins(false);
        grid.setFocusable(false);
        root.addView(gridContainer, new LinearLayout.LayoutParams(-1, 0, 1));
        screen = new FrameLayout(this);
        screen.addView(root, new FrameLayout.LayoutParams(-1, -1));
        int panelWidth = Math.max(dp(320), Math.min(
                getResources().getDisplayMetrics().widthPixels - dp(48), dp(1000)));
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        panelParams.topMargin = dp(54);
        screen.addView(settingsPanel, panelParams);
        setContentView(screen);
        settingsPanel.setVisibility(View.GONE);
        createTiles(cameraCount);
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private EditText field(String hint, int inputType) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setHint(hint);
        edit.setTextColor(TEXT);
        edit.setHintTextColor(MUTED);
        edit.setTextSize(13);
        edit.setInputType(inputType);
        edit.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        edit.setShowSoftInputOnFocus(true);
        edit.setPadding(dp(14), 0, dp(14), 0);
        edit.setBackground(roundRect(PANEL_2, dp(8), Color.rgb(51, 67, 92), dp(1)));
        edit.setFocusable(true);
        edit.setFocusableInTouchMode(true);
        edit.setSelectAllOnFocus(false);
        edit.setOnFocusChangeListener((v, hasFocus) -> {
            v.setBackground(roundRect(hasFocus ? Color.rgb(35, 54, 82) : PANEL_2,
                    dp(8), hasFocus ? Color.rgb(111, 177, 255) : Color.rgb(75, 96, 126),
                    hasFocus ? dp(3) : dp(1)));
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(50));
        p.setMargins(dp(4), dp(4), dp(4), dp(6));
        edit.setLayoutParams(p);
        return edit;
    }

    private LinearLayout fieldWrap(String label, EditText edit) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextColor(MUTED);
        caption.setTextSize(10);
        caption.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        caption.setPadding(dp(4), dp(3), 0, 0);
        wrap.addView(caption);
        wrap.addView(edit);
        return wrap;
    }

    private Button makeButton(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTextColor(TEXT);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setBackground(roundRect(primary ? ACCENT : PANEL_2, dp(10),
                primary ? ACCENT : Color.rgb(75, 96, 126), dp(1)));
        button.setOnFocusChangeListener((v, hasFocus) -> {
            int base = primary ? ACCENT : PANEL_2;
            v.setBackground(roundRect(hasFocus ? Color.rgb(48, 91, 151) : base,
                    dp(10), hasFocus ? Color.WHITE : (primary ? ACCENT : Color.rgb(75, 96, 126)),
                    hasFocus ? dp(3) : dp(1)));
            v.setScaleX(hasFocus ? 1.04f : 1f);
            v.setScaleY(hasFocus ? 1.04f : 1f);
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(42));
        p.setMargins(dp(3), dp(3), dp(3), dp(3));
        button.setLayoutParams(p);
        return button;
    }

    private GradientDrawable roundRect(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (strokeWidth > 0) d.setStroke(strokeWidth, strokeColor);
        return d;
    }

    private void configureSettingsFocus(Button saveButton) {
        passwordVisibilityToggle.setId(View.generateViewId());
        EditText[] fields = {
                hostInput, portInput, usernameInput, passwordInput, pathInput, countInput
        };
        for (int i = 0; i < fields.length; i++) {
            EditText field = fields[i];
            field.setId(View.generateViewId());
            if (i + 1 < fields.length) {
                field.setNextFocusForwardId(fields[i + 1].getId());
            } else {
                field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            }
        }

        hostInput.setNextFocusRightId(portInput.getId());
        hostInput.setNextFocusDownId(usernameInput.getId());
        portInput.setNextFocusLeftId(hostInput.getId());
        portInput.setNextFocusDownId(passwordInput.getId());
        usernameInput.setNextFocusUpId(hostInput.getId());
        usernameInput.setNextFocusRightId(passwordInput.getId());
        usernameInput.setNextFocusDownId(pathInput.getId());
        passwordInput.setNextFocusUpId(portInput.getId());
        passwordInput.setNextFocusLeftId(usernameInput.getId());
        passwordInput.setNextFocusDownId(pathInput.getId());
        passwordInput.setNextFocusRightId(passwordVisibilityToggle.getId());
        passwordVisibilityToggle.setNextFocusLeftId(passwordInput.getId());
        passwordVisibilityToggle.setNextFocusUpId(portInput.getId());
        passwordVisibilityToggle.setNextFocusDownId(pathInput.getId());
        pathInput.setNextFocusUpId(usernameInput.getId());
        pathInput.setNextFocusRightId(countInput.getId());
        countInput.setNextFocusLeftId(pathInput.getId());
        countInput.setNextFocusDownId(connectButton.getId());
        saveButton.setNextFocusRightId(connectButton.getId());
        connectButton.setNextFocusLeftId(saveButton.getId());
        connectButton.setNextFocusDownId(settingsCloseButton.getId());
        settingsCloseButton.setNextFocusUpId(connectButton.getId());
    }

    private void togglePasswordVisibility() {
        boolean showPassword = passwordInput.getTransformationMethod()
                instanceof PasswordTransformationMethod;
        int cursorPosition = passwordInput.getSelectionStart();
        passwordInput.setTransformationMethod(showPassword
                ? HideReturnsTransformationMethod.getInstance()
                : PasswordTransformationMethod.getInstance());
        passwordVisibilityToggle.setText(showPassword ? "🙈" : "👁");
        passwordVisibilityToggle.setContentDescription(showPassword ? "Hide password" : "Show password");
        if (cursorPosition >= 0) {
            passwordInput.setSelection(Math.min(cursorPosition, passwordInput.length()));
        }
        passwordInput.requestFocus();
    }

    private void toggleSettings() {
        settingsVisible = !settingsVisible;
        settingsPanel.setVisibility(settingsVisible ? View.VISIBLE : View.GONE);
        settingsToggle.setText(settingsVisible ? "✕  Close" : "⚙  Settings");
        if (settingsVisible) hostInput.requestFocus();
        else settingsToggle.requestFocus();
    }

    private void loadSettingsIntoFields(JSONObject settings) {
        hostInput.setText(settings.optString("host", ""));
        portInput.setText(settings.optString("port", "554"));
        usernameInput.setText(settings.optString("username", "admin"));
        passwordInput.setText(settings.optString("password", ""));
        pathInput.setText(settings.optString("path", DEFAULT_PATH));
        countInput.setText(settings.optString("count", "4"));
    }

    private boolean hasSavedConnectionSettings(JSONObject settings) {
        return !settings.optString("host").trim().isEmpty()
                && !settings.optString("port").trim().isEmpty()
                && !settings.optString("username").isEmpty()
                && !settings.optString("password").isEmpty()
                && settings.optString("path", DEFAULT_PATH).contains("{channel}");
    }

    private JSONObject collectSettings() throws Exception {
        String host = hostInput.getText().toString().trim();
        String port = portInput.getText().toString().trim();
        String username = usernameInput.getText().toString();
        String password = passwordInput.getText().toString();
        String path = pathInput.getText().toString().trim();
        String count = countInput.getText().toString().trim();
        if (host.isEmpty() || port.isEmpty() || username.isEmpty() || password.isEmpty()) {
            throw new IllegalArgumentException("Enter the DVR address, port, username and password.");
        }
        int portNumber = Integer.parseInt(port);
        if (portNumber < 1 || portNumber > 65535) throw new IllegalArgumentException("RTSP port must be between 1 and 65535.");
        int channels = Integer.parseInt(count);
        if (channels < 1 || channels > 16) throw new IllegalArgumentException("Camera count must be between 1 and 16.");
        if (!path.startsWith("/")) throw new IllegalArgumentException("RTSP path must start with /.");
        if (!path.contains("{channel}")) throw new IllegalArgumentException("RTSP path must contain {channel}.");
        JSONObject result = new JSONObject();
        result.put("host", host);
        result.put("port", portNumber);
        result.put("username", username);
        result.put("password", password);
        result.put("path", path);
        result.put("subtype", 1);
        result.put("count", channels);
        return result;
    }

    private boolean saveSettingsFromFields() {
        try {
            JSONObject settings = collectSettings();
            writeEncryptedSettings(settings.toString());
            return true;
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage() == null ? "Could not save settings" : e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void connectCameras() {
        final JSONObject settings;
        try {
            settings = collectSettings();
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage() == null ? "Check connection settings" : e.getMessage(), Toast.LENGTH_LONG).show();
            if (!settingsVisible) toggleSettings();
            return;
        }

        releasePlayers();
        fullscreenCamera = -1;
        audioCamera = -1;
        audioNowPlaying.setText("🔇 Audio off");
        cameraCount = settings.optInt("count", 4);
        initializeCameraProfiles(settings);
        connectionAttempted = true;
        connectionHint = null;
        createTiles(cameraCount);
        activeSettings = settings;
        try {
            settings.put("cameraSubtypes", new JSONArray(cameraSubtypes));
            settings.put("cameraProfileVersion", 2);
            writeEncryptedSettings(settings.toString());
        } catch (Exception e) {
            Toast.makeText(this, "Could not save camera stream profiles.", Toast.LENGTH_LONG).show();
        }
        globalStatus.setText("Connecting to " + settings.optString("host") + "…");
        if (settingsVisible) toggleSettings();

        for (int i = 0; i < cameraCount; i++) {
            players.add(null);
        }
        for (int i = 0; i < cameraCount; i++) {
            startCamera(i);
        }
    }

    private void initializeCameraProfiles(JSONObject settings) {
        JSONObject savedSettings = readEncryptedSettings();
        JSONArray savedProfiles = savedSettings.optInt("cameraProfileVersion", 0) >= 1
                ? savedSettings.optJSONArray("cameraSubtypes") : null;
        boolean useSavedProfiles = cameraSubtypes.size() == settings.optInt("count", 4);
        if (!useSavedProfiles && savedProfiles != null && savedProfiles.length() == settings.optInt("count", 4)) {
            cameraSubtypes.clear();
            for (int i = 0; i < savedProfiles.length(); i++) {
                int subtype = savedProfiles.optInt(i, 1);
                cameraSubtypes.add(subtype == 0 || subtype == 1 ? subtype : 1);
            }
            useSavedProfiles = true;
        }
        if (!useSavedProfiles) {
            cameraSubtypes.clear();
            for (int i = 0; i < settings.optInt("count", 4); i++) {
                cameraSubtypes.add(1);
            }
        }
    }

    private void startCamera(int index) {
        if (activeSettings == null || index < 0 || index >= cameraCount) return;
        ExoPlayer previous = players.get(index);
        if (previous != null) {
            playerViews.get(index).setPlayer(null);
            previous.release();
            players.set(index, null);
        }
        setCameraStatus(index, "CONNECTING", Color.rgb(48, 92, 146));
        updateCameraQualityButton(index);
        try {
            int subtypeToUse = cameraSubtypes.get(index);
            String url = buildRtspUrl(activeSettings, index + 1, subtypeToUse);
            Log.i("CCTV_DEBUG", "startCamera " + (index + 1) + " (channel=" + (index + 1) + ", subtype=" + subtypeToUse + ")");

            DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this)
                    .setMediaCodecSelector(CCTV_MEDIA_CODEC_SELECTOR)
                    .setEnableDecoderFallback(true);
            ExoPlayer player = new ExoPlayer.Builder(this, renderersFactory).build();
            players.set(index, player);
            playerViews.get(index).setPlayer(player);
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (players.get(index) != player) return;
                    if (state == Player.STATE_BUFFERING) {
                        setCameraStatus(index, "CONNECTING", Color.rgb(48, 92, 146));
                    } else if (state == Player.STATE_READY) {
                        setCameraStatus(index, "LIVE", Color.rgb(32, 139, 91));
                    } else if (state == Player.STATE_ENDED) {
                        setCameraStatus(index, "ENDED", Color.rgb(157, 92, 40));
                    }
                }

                @Override
                public void onVideoSizeChanged(VideoSize videoSize) {
                    Log.i("CCTV_DEBUG", "Camera " + (index + 1) + " videoSize: " + videoSize.width + "x" + videoSize.height);
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    if (players.get(index) != player) return;
                    Log.e("CCTV_DEBUG", "Camera " + (index + 1) + " onPlayerError: " + error.getMessage() + " code=" + error.errorCode + " (" + error.getErrorCodeName() + ")", error);
                    String message = error.getErrorCodeName();
                    String detail = getCauseDetails(error);
                    String lowerDetail = detail.toLowerCase();
                    if (detail.contains("401") || message.contains("AUTHENTICATION")) {
                        message = "DVR rejected login (RTSP 401)";
                        connectionHint = "Check the DVR credentials and RTSP permissions.";
                        setCameraStatus(index, "AUTH 401", Color.rgb(157, 48, 56), message);
                        return;
                    } else if (detail.contains("404")) {
                        message = "RTSP path not found (404)";
                    } else if (lowerDetail.contains("timeout")) {
                        message = "Connection timed out";
                    } else if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED) {
                        boolean hd = cameraSubtypes.get(index) == 0;
                        if (hd) {
                            Log.w("CCTV_DEBUG", "Camera " + (index + 1) + " HD decoding failed, falling back to SD substream.");
                            cameraSubtypes.set(index, 1);
                            updateCameraQualityButton(index);
                            persistCameraProfiles();
                            startCamera(index);
                            return;
                        }
                        message = "Video decoder failed";
                        connectionHint = "The TV decoder rejected this stream format.";
                        setCameraStatus(index, "DECODER", Color.rgb(157, 92, 40), message);
                        return;
                    }
                    connectionHint = "Check the DVR stream settings.";
                    setCameraStatus(index, "ERROR", Color.rgb(157, 48, 56), message);
                    scheduleCameraRetry(index);
                }
            });
            player.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
            player.setVolume(index == audioCamera ? 1f : 0f);
            player.prepare();
            player.play();
        } catch (Exception e) {
            Log.e("CCTV_DEBUG", "Could not start camera " + (index + 1), e);
            setCameraStatus(index, "ERROR", Color.rgb(157, 48, 56), "Could not start: " + e.getMessage());
        }
    }

    private void scheduleCameraRetry(int index) {
        Runnable retryRunnable = new Runnable() {
            @Override
            public void run() {
                pendingRetries.remove(this);
                if (activeSettings != null && !isDestroyed() && index < players.size()) {
                    Log.i("CCTV_DEBUG", "Retrying camera " + (index + 1) + " connection...");
                    startCamera(index);
                }
            }
        };
        pendingRetries.add(retryRunnable);
        mainHandler.postDelayed(retryRunnable, 5000);
    }

    private void persistCameraProfiles() {
        if (activeSettings == null) return;
        try {
            activeSettings.put("cameraSubtypes", new JSONArray(cameraSubtypes));
            activeSettings.put("cameraProfileVersion", 2);
            writeEncryptedSettings(activeSettings.toString());
        } catch (Exception e) {
            Toast.makeText(this, "Could not save camera stream profiles.", Toast.LENGTH_LONG).show();
        }
    }

    private void toggleCameraQuality(int index) {
        if (index < 0 || index >= cameraSubtypes.size()) return;
        cameraSubtypes.set(index, cameraSubtypes.get(index) == 0 ? 1 : 0);
        persistCameraProfiles();
        for (int i = 0; i < cameraSubtypes.size(); i++) updateCameraQualityButton(i);
        connectionHint = null;
        if (connectionAttempted) startCamera(index);
    }

    private void updateCameraQualityButton(int index) {
        if (index < 0 || index >= cameraQualityButtons.size()
                || index >= cameraSubtypes.size()) return;
        boolean hd = cameraSubtypes.get(index) == 0;
        Button button = cameraQualityButtons.get(index);
        button.setText(hd ? "HD" : "SD");
        button.setContentDescription("Camera " + (index + 1) + " stream: "
                + (hd ? "HD main stream" : "SD substream") + ". Press OK to switch.");
    }

    private String getCauseDetails(Throwable error) {
        StringBuilder details = new StringBuilder();
        Throwable cause = error;
        int depth = 0;
        while (cause != null && depth++ < 8) {
            String message = cause.getMessage();
            if (message != null && !message.isEmpty()) {
                if (details.length() > 0) details.append(" ");
                details.append(message);
            }
            cause = cause.getCause();
        }
        return details.toString();
    }

    private String buildRtspUrl(JSONObject s, int channel, int subtype) {
        String host = s.optString("host").trim();
        // Avoid URI injection and malformed authority values.
        if (host.contains("://") || host.contains("/") || host.contains("@") || host.contains(" ")) {
            throw new IllegalArgumentException("Enter only the DVR IP address or hostname, without rtsp:// or a path.");
        }
        String username = Uri.encode(s.optString("username"), "");
        String password = Uri.encode(s.optString("password"), "");
        String path = s.optString("path", DEFAULT_PATH)
                .replace("{channel}", String.valueOf(channel))
                .replace("{subtype}", String.valueOf(subtype));
        if (path.contains(" ")) throw new IllegalArgumentException("RTSP path cannot contain spaces.");
        return "rtsp://" + username + ":" + password + "@" + host + ":" + s.optInt("port", 554) + path;
    }

    private void createTiles(int count) {
        grid.removeAllViews();
        gridContainer.removeAllViews();
        tiles.clear(); playerViews.clear(); cameraStatuses.clear(); audioButtons.clear();
        cameraQualityButtons.clear();
        cameraStateLabels.clear();
        boolean singleCameraRow = count <= 4;
        gridColumns = count <= 4 ? count : count <= 9 ? 3 : 4;
        grid.setColumnCount(gridColumns);
        grid.setRowCount((count + gridColumns - 1) / gridColumns);
        if (singleCameraRow) {
            LinearLayout row = horizontalRow();
            row.setGravity(Gravity.TOP);
            cameraLayout = row;
            gridContainer.addView(row, new LinearLayout.LayoutParams(-1, -1));
        } else {
            cameraLayout = grid;
            gridContainer.addView(grid, new LinearLayout.LayoutParams(-1, -1));
        }
        for (int i = 0; i < count; i++) createCameraTile(i);
        configureCameraFocus(count);
    }

    private void createCameraTile(int index) {
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(roundRect(Color.rgb(5, 9, 16), dp(12), Color.rgb(39, 54, 77), dp(1)));
        tile.setFocusable(true);
        tile.setFocusableInTouchMode(true);
        tile.setClickable(true);
        tile.setClipToOutline(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(-1, -1);
        tile.addView(content, contentParams);

        LinearLayout tileHeader = new LinearLayout(this);
        tileHeader.setGravity(Gravity.CENTER_VERTICAL);
        tileHeader.setPadding(dp(6), dp(2), dp(4), dp(2));
        tileHeader.setBackgroundColor(PANEL);
        TextView label = new TextView(this);
        label.setText(String.format("CAMERA %02d", index + 1));
        label.setTextColor(TEXT);
        label.setTextSize(11);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tileHeader.addView(label, new LinearLayout.LayoutParams(0, dp(26), 1));
        Button audioButton = makeButton("🔇 Listen", false);
        audioButton.setTextSize(12);
        LinearLayout.LayoutParams audioParams = new LinearLayout.LayoutParams(-2, dp(32));
        audioParams.setMargins(0, 0, 0, 0);
        audioButton.setContentDescription("Listen to camera " + (index + 1));
        audioButton.setOnClickListener(v -> setAudioCamera(index));
        Button cameraQualityButton = makeButton("SD", false);
        cameraQualityButton.setTextSize(11);
        LinearLayout.LayoutParams cameraQualityParams = new LinearLayout.LayoutParams(dp(48), dp(32));
        cameraQualityParams.setMargins(0, 0, dp(3), 0);
        tileHeader.addView(cameraQualityButton, cameraQualityParams);
        tileHeader.addView(audioButton, audioParams);
        content.addView(tileHeader);

        FrameLayout videoFrame = new FrameLayout(this);
        videoFrame.setBackgroundColor(Color.BLACK);
        PlayerView playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setShutterBackgroundColor(Color.BLACK);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        videoFrame.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        content.addView(videoFrame, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView cameraStatus = new TextView(this);
        cameraStatus.setText("Ready to connect");
        cameraStatus.setTextColor(MUTED);
        cameraStatus.setTextSize(11);
        cameraStatus.setPadding(dp(6), dp(1), dp(6), dp(1));
        cameraStatus.setBackground(roundRect(Color.rgb(42, 53, 70), dp(4), 0, 0));
        content.addView(cameraStatus, new LinearLayout.LayoutParams(-1, dp(20)));

        tile.setContentDescription("Camera " + (index + 1) + ". Press OK to view full screen.");
        tile.setOnClickListener(v -> toggleFullscreen(index));
        tile.setOnFocusChangeListener((v, hasFocus) -> {
            tile.setForeground(roundRect(Color.TRANSPARENT, dp(12),
                    hasFocus ? Color.WHITE : Color.TRANSPARENT, hasFocus ? dp(3) : 0));
            tile.setScaleX(hasFocus ? 1.015f : 1f);
            tile.setScaleY(hasFocus ? 1.015f : 1f);
        });

        tiles.add(tile);
        tile.setId(View.generateViewId());
        playerViews.add(playerView);
        audioButton.setId(View.generateViewId());
        cameraStatuses.add(cameraStatus);
        cameraStateLabels.add("READY");
        audioButtons.add(audioButton);
        cameraQualityButtons.add(cameraQualityButton);
        cameraQualityButton.setId(View.generateViewId());
        cameraQualityButton.setOnClickListener(v -> toggleCameraQuality(index));
        updateCameraQualityButton(index);
        if (cameraLayout == grid) {
            grid.addView(tile, cameraTileLayoutParams(index));
        } else {
            cameraLayout.addView(tile, index, cameraTileRowLayoutParams());
        }
    }

    private LinearLayout.LayoutParams cameraTileRowLayoutParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1f);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        return params;
    }

    private GridLayout.LayoutParams cameraTileLayoutParams(int index) {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(index / gridColumns, 1, 1f),
                GridLayout.spec(index % gridColumns, 1, 1f));
        params.width = 0;
        params.height = 0;
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        return params;
    }

    private void configureCameraFocus(int count) {
        for (int i = 0; i < count; i++) {
            int row = i / gridColumns;
            int column = i % gridColumns;
            Button qualityButton = cameraQualityButtons.get(i);
            Button audioButton = audioButtons.get(i);
            qualityButton.setNextFocusRightId(audioButton.getId());
            audioButton.setNextFocusLeftId(qualityButton.getId());
            tiles.get(i).setNextFocusDownId(qualityButton.getId());
            qualityButton.setNextFocusDownId(tiles.get(i).getId());
            audioButton.setNextFocusUpId(qualityButton.getId());
            if (column > 0) {
                int leftId = tiles.get(i - 1).getId();
                tiles.get(i).setNextFocusLeftId(leftId);
                qualityButton.setNextFocusLeftId(cameraQualityButtons.get(i - 1).getId());
            }
            if (column + 1 < gridColumns && i + 1 < count) {
                int rightId = tiles.get(i + 1).getId();
                tiles.get(i).setNextFocusRightId(rightId);
                audioButton.setNextFocusRightId(cameraQualityButtons.get(i + 1).getId());
            }
            if (row > 0) {
                int upId = cameraQualityButtons.get(i - gridColumns).getId();
                tiles.get(i).setNextFocusUpId(upId);
                qualityButton.setNextFocusUpId(upId);
                audioButton.setNextFocusUpId(audioButtons.get(i - gridColumns).getId());
            } else if (i == 0) {
                qualityButton.setNextFocusUpId(settingsToggle.getId());
            }
            if (i + gridColumns < count) {
                int downId = cameraQualityButtons.get(i + gridColumns).getId();
                tiles.get(i).setNextFocusDownId(downId);
                qualityButton.setNextFocusDownId(downId);
                audioButton.setNextFocusDownId(audioButtons.get(i + gridColumns).getId());
            }
        }
        if (count > 0) settingsToggle.setNextFocusDownId(cameraQualityButtons.get(0).getId());
    }

    private void setCameraStatus(int index, String status, int color) {
        setCameraStatus(index, status, color, status);
    }

    private void setCameraStatus(int index, String status, int color, String detail) {
        if (index < 0 || index >= cameraStatuses.size()) return;
        TextView cameraStatus = cameraStatuses.get(index);
        cameraStatus.setText(status);
        cameraStatus.setTextColor(Color.WHITE);
        cameraStatus.setBackground(roundRect(color, dp(4), 0, 0));
        cameraStatus.setContentDescription("Camera " + (index + 1) + ": " + detail);
        cameraStateLabels.set(index, status);
        if (connectionAttempted) updateConnectionSummary();
    }

    private void updateConnectionSummary() {
        int live = 0;
        int connecting = 0;
        int authenticationErrors = 0;
        int decoderErrors = 0;
        int otherErrors = 0;
        int ended = 0;
        for (String state : cameraStateLabels) {
            if ("LIVE".equals(state)) live++;
            else if ("CONNECTING".equals(state)) connecting++;
            else if (state.startsWith("AUTH")) authenticationErrors++;
            else if ("DECODER".equals(state)) decoderErrors++;
            else if ("ERROR".equals(state)) otherErrors++;
            else if ("ENDED".equals(state)) ended++;
        }
        if (live == cameraCount) {
            globalStatus.setText("All " + cameraCount + " camera feeds live.");
            return;
        }

        StringBuilder summary = new StringBuilder()
                .append(live).append('/').append(cameraCount).append(" feeds live");
        if (authenticationErrors > 0) {
            summary.append(" · ").append(authenticationErrors).append(" authentication errors");
        }
        if (decoderErrors > 0) {
            summary.append(" · ").append(decoderErrors).append(" decoder errors");
        }
        if (otherErrors > 0) summary.append(" · ").append(otherErrors).append(" other errors");
        if (ended > 0) summary.append(" · ").append(ended).append(" ended");
        if (connecting > 0) summary.append(" · ").append(connecting).append(" connecting");
        if (connectionHint != null) summary.append(". ").append(connectionHint);
        globalStatus.setText(summary.toString());
    }

    private void setAudioCamera(int index) {
        if (index < 0 || index >= players.size() || players.get(index) == null) {
            Toast.makeText(this, "Connect to the camera before enabling audio.", Toast.LENGTH_SHORT).show();
            return;
        }
        audioCamera = (audioCamera == index) ? -1 : index;
        for (int i = 0; i < players.size(); i++) {
            ExoPlayer player = players.get(i);
            if (player != null) player.setVolume(i == audioCamera ? 1f : 0f);
            if (i < audioButtons.size()) audioButtons.get(i).setText(i == audioCamera ? "🔊 Listening" : "🔇 Listen");
        }
        audioNowPlaying.setText(audioCamera == -1 ? "🔇 Audio off" : "🔊 Camera " + (audioCamera + 1));
        if (audioCamera != -1) Toast.makeText(this, "Audio enabled for Camera " + (audioCamera + 1) + ". Availability depends on the DVR stream.", Toast.LENGTH_SHORT).show();
    }

    private void toggleFullscreen(int index) {
        if (fullscreenCamera == index) { exitFullscreen(); return; }
        if (fullscreenCamera != -1) exitFullscreen();
        if (index < 0 || index >= tiles.size()) return;
        FrameLayout tile = tiles.get(index);
        if (tile.getParent() instanceof ViewGroup) ((ViewGroup) tile.getParent()).removeView(tile);
        cameraLayout.setVisibility(View.GONE);
        // Keep the header and settings hierarchy intact; the selected tile expands
        // into the grid container and Back restores the camera grid.
        gridContainer.addView(tile, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.MATCH_PARENT));
        fullscreenCamera = index;
        tile.requestFocus();
    }

    private void exitFullscreen() {
        if (fullscreenCamera == -1) return;
        int index = fullscreenCamera;
        FrameLayout tile = tiles.get(index);
        if (tile.getParent() instanceof ViewGroup) ((ViewGroup) tile.getParent()).removeView(tile);
        if (cameraLayout == grid) {
            grid.addView(tile, index, cameraTileLayoutParams(index));
        } else {
            cameraLayout.addView(tile, index, cameraTileRowLayoutParams());
        }
        cameraLayout.setVisibility(View.VISIBLE);
        fullscreenCamera = -1;
        tile.requestFocus();
    }

    private void releasePlayers() {
        for (Runnable r : pendingRetries) mainHandler.removeCallbacks(r);
        pendingRetries.clear();
        for (ExoPlayer player : players) if (player != null) player.release();
        players.clear();
        audioCamera = -1;
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private void writeEncryptedSettings(String plaintext) throws Exception {
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        String packed = android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP) + ":" +
                android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_CIPHER_TEXT, packed).apply();
    }

    private JSONObject readEncryptedSettings() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            String packed = prefs.getString(PREF_CIPHER_TEXT, null);
            if (packed == null) return new JSONObject();
            String[] parts = packed.split(":", 2);
            if (parts.length != 2) return new JSONObject();
            byte[] iv = android.util.Base64.decode(parts[0], android.util.Base64.NO_WRAP);
            byte[] encrypted = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new JSONObject(new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    @Override public void onBackPressed() {
        if (fullscreenCamera != -1) exitFullscreen();
        else if (settingsVisible) toggleSettings();
        else super.onBackPressed();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && fullscreenCamera != -1) { exitFullscreen(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onDestroy() {
        releasePlayers();
        super.onDestroy();
    }
}
