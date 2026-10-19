package co.pixelwood.pasta;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String PREFS = "pasta";
    private static final String KEY_BASE = "base_url";
    private static final String KEY_TOKEN = "token";
    private static final int REQ_PICK_IMAGE = 71;
    private static final long AUTO_REFRESH_MS = 12000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private LruCache<String, Bitmap> imageCache;

    private SharedPreferences prefs;
    private ApiClient api;
    private String baseUrl;
    private String token;
    private String deviceName;

    private boolean loggedIn;
    private boolean loadingClips;
    private boolean sending;
    private boolean busy;
    private String lastSignature;
    private List<Clip> clips = new ArrayList<>();

    private LinearLayout clipsContainer;
    private ScrollView scrollView;
    private TextView countView;
    private TextView charCount;
    private TextView sendButton;
    private TextView addImageButton;
    private TextView clipboardButton;
    private TextView statusText;
    private View statusDot;
    private LinearLayout statusPill;
    private ImageView refreshIcon;
    private EditText composer;

    private EditText loginServer;
    private EditText loginPassword;
    private TextView loginError;
    private TextView loginButton;

    private Dialog busyDialog;
    private Dialog imageDialog;
    private String pendingShareText;
    private Uri pendingShareImage;
    private String pendingShareMime;

    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            if (loggedIn) {
                refreshClips(true);
                handler.postDelayed(this, AUTO_REFRESH_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applySystemBars();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        baseUrl = prefs.getString(KEY_BASE, getString(R.string.default_server_url));
        token = prefs.getString(KEY_TOKEN, null);
        deviceName = deviceName();
        imageCache = new LruCache<String, Bitmap>(28 * 1024) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
        try {
            api = new ApiClient(baseUrl, token);
        } catch (Exception error) {
            baseUrl = getString(R.string.default_server_url);
            api = new ApiClient(baseUrl, null);
            token = null;
        }

        if (!TextUtils.isEmpty(token)) {
            showMain();
            refreshClips(false);
        } else {
            showLogin(null);
        }
        if (savedInstanceState == null) handleShareIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (loggedIn) {
            handler.removeCallbacks(autoRefresh);
            handler.postDelayed(autoRefresh, AUTO_REFRESH_MS);
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(autoRefresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        dismissBusy();
        if (imageDialog != null) {
            imageDialog.dismiss();
            imageDialog = null;
        }
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    // ---------------------------------------------------------------------
    // Chrome
    // ---------------------------------------------------------------------

    private void applySystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(App.PAPER);
        window.setNavigationBarColor(App.PAPER);
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private View screen() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(App.PAPER);
        return root;
    }

    private TextView primaryButton(String label, boolean large) {
        TextView button = Ui.text(this, label, large ? 14.5f : 12.5f, Color.WHITE, Ui.sansBold(this));
        button.setGravity(Gravity.CENTER);
        button.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
        button.setMinHeight(Ui.dp(this, large ? 52 : 38));
        button.setBackground(Ui.ripple(this, 0x33FFFFFF,
                Ui.background(App.ACCENT, large ? 12 : 10, App.ACCENT_DEEP, 1)));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private TextView toolButton(int iconRes, String label) {
        TextView button = Ui.text(this, label, 11.5f, App.MUTED, Ui.sansMedium(this));
        button.setGravity(Gravity.CENTER);
        button.setPadding(Ui.dp(this, 7), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        Drawable icon = getDrawable(iconRes);
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(App.MUTED);
            icon.setBounds(0, 0, Ui.dp(this, 15), Ui.dp(this, 15));
            button.setCompoundDrawablesRelative(icon, null, null, null);
            button.setCompoundDrawablePadding(Ui.dp(this, 5));
        }
        button.setBackground(Ui.selectableBorderless(this));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private TextView darkButton(String label, int iconRes) {
        TextView button = Ui.text(this, label, 12f, Color.WHITE, Ui.sansMedium(this));
        button.setGravity(Gravity.CENTER);
        button.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        button.setBackground(Ui.ripple(this, 0x33FFFFFF,
                Ui.background(0x22FFFFFF, 10, 0x33FFFFFF, 1)));
        if (iconRes != 0) {
            Drawable icon = getDrawable(iconRes);
            if (icon != null) {
                icon = icon.mutate();
                icon.setTint(Color.WHITE);
                icon.setBounds(0, 0, Ui.dp(this, 14), Ui.dp(this, 14));
                button.setCompoundDrawablesRelative(icon, null, null, null);
                button.setCompoundDrawablePadding(Ui.dp(this, 6));
            }
        }
        return button;
    }

    // ---------------------------------------------------------------------
    // Login
    // ---------------------------------------------------------------------

    private void showLogin(String message) {
        loggedIn = false;
        loadingClips = false;
        sending = false;
        busy = false;
        lastSignature = null;
        clips.clear();
        handler.removeCallbacks(autoRefresh);
        hideKeyboard();
        dismissBusy();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(App.PAPER);
        scroll.setClipToPadding(false);

        LinearLayout page = Ui.column(this);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(Ui.dp(this, 24), Ui.dp(this, 54), Ui.dp(this, 24), Ui.dp(this, 30));
        scroll.addView(page, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(Ui.dp(this, 66), Ui.dp(this, 66));
        logo.setLayoutParams(logoLp);
        page.addView(logo);

        TextView brand = Ui.text(this, "Pasta", 35f, App.INK, Ui.serifBold(this));
        brand.setLetterSpacing(-0.03f);
        LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        brandLp.topMargin = Ui.dp(this, 8);
        page.addView(brand, brandLp);

        TextView kicker = Ui.text(this, getString(R.string.login_kicker), 9f, App.ACCENT, Ui.sansBold(this));
        kicker.setLetterSpacing(0.2f);
        LinearLayout.LayoutParams kickerLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        kickerLp.topMargin = Ui.dp(this, 16);
        page.addView(kicker, kickerLp);

        TextView title = Ui.text(this, getString(R.string.login_title), 29f, App.INK, Ui.serifBold(this));
        title.setGravity(Gravity.CENTER);
        title.setLetterSpacing(-0.02f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        titleLp.topMargin = Ui.dp(this, 6);
        page.addView(title, titleLp);

        TextView subtitle = Ui.text(this, getString(R.string.login_subtitle), 12.5f, App.MUTED, Ui.sans(this));
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLineSpacing(Ui.dp(this, 2), 1.0f);
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP);
        subtitleLp.topMargin = Ui.dp(this, 9);
        subtitleLp.leftMargin = Ui.dp(this, 6);
        subtitleLp.rightMargin = Ui.dp(this, 6);
        page.addView(subtitle, subtitleLp);

        LinearLayout card = Ui.column(this);
        card.setPadding(Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 18));
        card.setBackground(Ui.background(App.CARD, 20, App.LINE, 1));
        card.setElevation(Ui.dp(this, 3));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP);
        cardLp.topMargin = Ui.dp(this, 26);
        page.addView(card, cardLp);

        TextView serverLabel = Ui.text(this, getString(R.string.server_label).toUpperCase(Locale.US), 9f, App.MUTED, Ui.sansBold(this));
        serverLabel.setLetterSpacing(0.13f);
        card.addView(serverLabel);
        loginServer = new EditText(this);
        loginServer.setHint("https://pasta.example.com");
        loginServer.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        loginServer.setText(baseUrl);
        loginServer.setSelection(loginServer.getText().length());
        Ui.styleField(this, loginServer);
        LinearLayout.LayoutParams serverLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP);
        serverLp.topMargin = Ui.dp(this, 7);
        card.addView(loginServer, serverLp);

        TextView passLabel = Ui.text(this, getString(R.string.passphrase_label).toUpperCase(Locale.US), 9f, App.MUTED, Ui.sansBold(this));
        passLabel.setLetterSpacing(0.13f);
        LinearLayout.LayoutParams passLabelLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        passLabelLp.topMargin = Ui.dp(this, 17);
        card.addView(passLabel, passLabelLp);

        FrameLayout passFrame = new FrameLayout(this);
        loginPassword = new EditText(this);
        loginPassword.setHint("six-words-and-a-number");
        loginPassword.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        loginPassword.setImeOptions(EditorInfo.IME_ACTION_DONE);
        Ui.styleField(this, loginPassword);
        loginPassword.setPadding(Ui.dp(this, 13), Ui.dp(this, 12), Ui.dp(this, 46), Ui.dp(this, 12));
        passFrame.addView(loginPassword, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ImageButton reveal = Ui.iconButton(this, R.drawable.ic_eye, "Show passphrase", App.MUTED);
        FrameLayout.LayoutParams revealLp = new FrameLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38), Gravity.END | Gravity.CENTER_VERTICAL);
        revealLp.rightMargin = Ui.dp(this, 4);
        passFrame.addView(reveal, revealLp);
        LinearLayout.LayoutParams passLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP);
        passLp.topMargin = Ui.dp(this, 7);
        card.addView(passFrame, passLp);

        final boolean[] showing = {false};
        reveal.setOnClickListener(v -> {
            showing[0] = !showing[0];
            int selection = loginPassword.getSelectionEnd();
            loginPassword.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | (showing[0] ? android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD));
            loginPassword.setSelection(Math.max(0, selection));
            loginPassword.setTypeface(Ui.sans(this));
            reveal.setImageResource(showing[0] ? R.drawable.ic_eye_off : R.drawable.ic_eye);
        });

        loginError = Ui.text(this, "", 11.5f, App.DANGER, Ui.sansMedium(this));
        loginError.setVisibility(message == null ? View.GONE : View.VISIBLE);
        if (message != null) loginError.setText(message);
        LinearLayout.LayoutParams errorLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP);
        errorLp.topMargin = Ui.dp(this, 8);
        card.addView(loginError, errorLp);

        loginButton = primaryButton(getString(R.string.unlock), true);
        loginButton.setOnClickListener(v -> doLogin());
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52));
        buttonLp.topMargin = Ui.dp(this, 18);
        card.addView(loginButton, buttonLp);

        loginPassword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                doLogin();
                return true;
            }
            return false;
        });

        TextView note = Ui.text(this, "One unlock lasts 30 days on this phone.", 10.5f, App.MUTED_LIGHT, Ui.sans(this));
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP);
        noteLp.topMargin = Ui.dp(this, 14);
        card.addView(note, noteLp);

        TextView footer = Ui.text(this, getString(R.string.login_footer), 9.5f, App.MUTED_LIGHT, Ui.sans(this));
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP);
        footerLp.topMargin = Ui.dp(this, 26);
        page.addView(footer, footerLp);

        setContentView(scroll);
        if (message != null) {
            loginPassword.requestFocus();
        }
    }

    private void doLogin() {
        if (loginButton == null || !loginButton.isEnabled()) return;
        final String server = loginServer.getText().toString().trim();
        final String password = loginPassword.getText().toString();
        if (Ui.isBlank(password)) {
            showLoginError("Enter your passphrase first.");
            loginPassword.requestFocus();
            return;
        }
        final String normalized;
        try {
            normalized = ApiClient.normalizeBase(server);
        } catch (IllegalArgumentException error) {
            showLoginError(error.getMessage());
            loginServer.requestFocus();
            return;
        }
        loginError.setVisibility(View.GONE);
        loginButton.setEnabled(false);
        loginButton.setText(getString(R.string.unlocking));
        loginButton.setAlpha(0.7f);
        hideKeyboard();

        io.execute(() -> {
            try {
                ApiClient candidate = new ApiClient(normalized, null);
                JSONObject response = candidate.login(password, deviceName);
                String newToken = response.optString("token", "");
                if (newToken.isEmpty()) throw new ApiClient.ApiException(500, "The server did not return a session.");
                runOnUiThread(() -> {
                    baseUrl = normalized;
                    token = newToken;
                    api = candidate;
                    api.setToken(token);
                    prefs.edit().putString(KEY_BASE, baseUrl).putString(KEY_TOKEN, token).apply();
                    showMain();
                    refreshClips(false);
                    scheduleAutoRefresh();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (loginButton != null) {
                        loginButton.setEnabled(true);
                        loginButton.setText(getString(R.string.unlock));
                        loginButton.setAlpha(1f);
                    }
                    showLoginError(friendly(error));
                });
            }
        });
    }

    private void showLoginError(String message) {
        if (loginError == null) return;
        loginError.setText(message);
        loginError.setVisibility(View.VISIBLE);
    }

    private void scheduleAutoRefresh() {
        handler.removeCallbacks(autoRefresh);
        if (loggedIn) handler.postDelayed(autoRefresh, AUTO_REFRESH_MS);
    }

    // ---------------------------------------------------------------------
    // Main screen
    // ---------------------------------------------------------------------

    private void showMain() {
        loggedIn = true;
        sending = false;
        busy = false;
        LinearLayout root = (LinearLayout) screen();

        LinearLayout topbar = buildTopbar();
        root.addView(topbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP));

        View divider = new View(this);
        divider.setBackgroundColor(0x33C9BBA6);
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)));

        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setBackgroundColor(App.PAPER);
        root.addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout content = Ui.column(this);
        content.setPadding(Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 30));
        scrollView.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP));

        TextView eyebrow = Ui.text(this, "YOUR CLIPBOARD", 9f, App.MUTED, Ui.sansBold(this));
        eyebrow.setLetterSpacing(0.19f);
        content.addView(eyebrow);

        TextView heading = Ui.text(this, "", 30f, App.INK, Ui.serifBold(this));
        SpannableString headingText = new SpannableString("Paste once. Everywhere.");
        int start = headingText.toString().indexOf("Everywhere.");
        headingText.setSpan(new ForegroundColorSpan(App.ACCENT), start, headingText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        headingText.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), start, headingText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        heading.setText(headingText);
        heading.setLetterSpacing(-0.025f);
        heading.setLineSpacing(Ui.dp(this, 3), 1.0f);
        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP);
        headingLp.topMargin = Ui.dp(this, 7);
        content.addView(heading, headingLp);

        TextView sub = Ui.text(this, "Everything you send lands here instantly, ready on your next screen.", 12.5f, App.MUTED, Ui.sans(this));
        sub.setLineSpacing(Ui.dp(this, 2), 1.0f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP);
        subLp.topMargin = Ui.dp(this, 5);
        content.addView(sub, subLp);

        LinearLayout composerCard = buildComposer();
        LinearLayout.LayoutParams composerLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP);
        composerLp.topMargin = Ui.dp(this, 18);
        content.addView(composerCard, composerLp);

        LinearLayout feedHead = Ui.row(this);
        TextView feedTitle = Ui.text(this, getString(R.string.recent_pastes), 22f, App.INK, Ui.serifBold(this));
        feedTitle.setLetterSpacing(-0.02f);
        countView = Ui.text(this, "", 9.5f, App.MUTED_LIGHT, Ui.sansMedium(this));
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        countLp.leftMargin = Ui.dp(this, 8);
        feedHead.addView(feedTitle);
        feedHead.addView(countView, countLp);
        View spacer = new View(this);
        feedHead.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        content.addView(feedHead, marginParams(0, 26, 0, 12));

        clipsContainer = Ui.column(this);
        content.addView(clipsContainer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP));
        addLoadingCard();

        setContentView(root);
        if (composer != null) composer.clearFocus();
    }

    private LinearLayout.LayoutParams marginParams(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP);
        lp.setMargins(Ui.dp(this, left), Ui.dp(this, top), Ui.dp(this, right), Ui.dp(this, bottom));
        return lp;
    }

    private LinearLayout buildTopbar() {
        LinearLayout topbar = Ui.row(this);
        topbar.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10));
        topbar.setBackgroundColor(App.PAPER);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        topbar.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 35), Ui.dp(this, 35)));

        TextView brand = Ui.text(this, "Pasta", 24f, App.INK, Ui.serifBold(this));
        brand.setLetterSpacing(-0.03f);
        LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        brandLp.leftMargin = Ui.dp(this, 8);
        topbar.addView(brand, brandLp);

        View spacer = new View(this);
        topbar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        statusPill = Ui.row(this);
        statusPill.setPadding(Ui.dp(this, 9), Ui.dp(this, 5), Ui.dp(this, 9), Ui.dp(this, 5));
        statusPill.setBackground(Ui.pill(App.GREEN_SOFT, 50));
        statusDot = new View(this);
        statusDot.setBackground(Ui.pill(App.SAGE, 50));
        statusPill.addView(statusDot, new LinearLayout.LayoutParams(Ui.dp(this, 6), Ui.dp(this, 6)));
        statusText = Ui.text(this, "Live", 9f, App.GREEN_INK, Ui.sansBold(this));
        statusText.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        statusLp.leftMargin = Ui.dp(this, 5);
        statusPill.addView(statusText, statusLp);
        topbar.addView(statusPill, new LinearLayout.LayoutParams(WRAP, WRAP));

        ImageButton refresh = Ui.iconButton(this, R.drawable.ic_refresh, getString(R.string.refresh), App.MUTED);
        refresh.setOnClickListener(v -> {
            refresh.animate().rotationBy(360f).setDuration(500).start();
            refreshClips(false);
        });
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38));
        refreshLp.leftMargin = Ui.dp(this, 4);
        topbar.addView(refresh, refreshLp);
        refreshIcon = null;

        ImageButton settings = Ui.iconButton(this, R.drawable.ic_settings, getString(R.string.settings), App.MUTED);
        settings.setOnClickListener(v -> showSettings());
        topbar.addView(settings, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38)));
        return topbar;
    }

    private LinearLayout buildComposer() {
        LinearLayout card = Ui.column(this);
        card.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 10));
        card.setBackground(Ui.background(App.CARD, 20, App.LINE, 1));
        card.setElevation(Ui.dp(this, 3));

        composer = new EditText(this);
        composer.setHint(getString(R.string.composer_hint));
        composer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        composer.setTypeface(Ui.sans(this));
        composer.setTextColor(App.INK);
        composer.setHintTextColor(App.MUTED_LIGHT);
        composer.setGravity(Gravity.TOP | Gravity.START);
        composer.setPadding(Ui.dp(this, 3), Ui.dp(this, 4), Ui.dp(this, 3), Ui.dp(this, 4));
        composer.setBackgroundColor(Color.TRANSPARENT);
        composer.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.setMinHeight(Ui.dp(this, 92));
        composer.setMaxHeight(Ui.dp(this, 180));
        composer.setVerticalScrollBarEnabled(true);
        card.addView(composer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP));

        LinearLayout bottom = Ui.row(this);
        bottom.setPadding(0, Ui.dp(this, 6), 0, 0);

        addImageButton = toolButton(R.drawable.ic_image, getString(R.string.add_image));
        addImageButton.setOnClickListener(v -> openImagePicker());
        bottom.addView(addImageButton);

        clipboardButton = toolButton(R.drawable.ic_clipboard, getString(R.string.from_clipboard));
        clipboardButton.setOnClickListener(v -> sendFromClipboard());
        bottom.addView(clipboardButton);

        View spacer = new View(this);
        bottom.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        charCount = Ui.text(this, "", 9.5f, App.MUTED_LIGHT, Ui.sans(this));
        charCount.setVisibility(View.GONE);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        countLp.rightMargin = Ui.dp(this, 8);
        bottom.addView(charCount, countLp);

        sendButton = Ui.pillButton(this, R.drawable.ic_send, getString(R.string.send), true);
        sendButton.setOnClickListener(v -> sendComposer());
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(WRAP, Ui.dp(this, 38));
        sendLp.leftMargin = Ui.dp(this, 2);
        bottom.addView(sendButton, sendLp);

        card.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP));

        composer.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (charCount == null) return;
                int length = s.length();
                if (length == 0) {
                    charCount.setVisibility(View.GONE);
                } else {
                    charCount.setVisibility(View.VISIBLE);
                    charCount.setText(length + " / 100k");
                }
            }
        });

        return card;
    }

    private void addLoadingCard() {
        if (clipsContainer == null) return;
        clipsContainer.removeAllViews();
        TextView loading = Ui.text(this, "Loading your clipboard…", 12.5f, App.MUTED, Ui.sans(this));
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(0, Ui.dp(this, 34), 0, Ui.dp(this, 34));
        clipsContainer.addView(loading);
    }

    private void renderClips() {
        if (clipsContainer == null) return;
        int scrollY = scrollView == null ? 0 : scrollView.getScrollY();
        clipsContainer.removeAllViews();
        List<Clip> sorted = new ArrayList<>(clips);
        Collections.sort(sorted, new Comparator<Clip>() {
            @Override
            public int compare(Clip a, Clip b) {
                if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
                return Long.compare(b.createdAt, a.createdAt);
            }
        });

        if (countView != null) {
            countView.setText(sorted.size() + (sorted.size() == 1 ? " paste" : " pastes"));
        }

        if (sorted.isEmpty()) {
            clipsContainer.addView(emptyCard());
        } else {
            for (Clip clip : sorted) {
                clipsContainer.addView(buildClipCard(clip));
            }
        }
        if (scrollView != null) {
            final int target = scrollY;
            scrollView.post(() -> scrollView.scrollTo(0, target));
        }
    }

    private View emptyCard() {
        LinearLayout card = Ui.column(this);
        card.setGravity(Gravity.CENTER);
        card.setPadding(Ui.dp(this, 26), Ui.dp(this, 32), Ui.dp(this, 26), Ui.dp(this, 32));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0x66FFFDF8);
        background.setCornerRadius(Ui.dp(this, 20));
        background.setStroke(Ui.dp(this, 1), App.LINE_STRONG, Ui.dp(this, 6), Ui.dp(this, 5));
        card.setBackground(background);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_clipboard);
        icon.setColorFilter(App.ACCENT);
        card.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));

        TextView title = Ui.text(this, getString(R.string.empty_title), 22f, App.INK, Ui.serifBold(this));
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        titleLp.topMargin = Ui.dp(this, 12);
        card.addView(title, titleLp);

        TextView copy = Ui.text(this, getString(R.string.empty_copy), 11.5f, App.MUTED, Ui.sans(this));
        copy.setGravity(Gravity.CENTER);
        copy.setLineSpacing(Ui.dp(this, 2), 1.0f);
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP);
        copyLp.topMargin = Ui.dp(this, 6);
        card.addView(copy, copyLp);

        TextView action = Ui.pillButton(this, R.drawable.ic_clipboard, "Paste clipboard", false);
        action.setOnClickListener(v -> sendFromClipboard());
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(WRAP, Ui.dp(this, 36));
        actionLp.topMargin = Ui.dp(this, 16);
        actionLp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(action, actionLp);
        return card;
    }

    private View buildClipCard(final Clip clip) {
        LinearLayout card = Ui.column(this);
        card.setPadding(Ui.dp(this, 13), Ui.dp(this, 12), Ui.dp(this, 13), Ui.dp(this, 11));
        GradientDrawable background = new GradientDrawable();
        background.setColor(App.CARD);
        background.setCornerRadius(Ui.dp(this, 16));
        background.setStroke(Ui.dp(this, 1), clip.pinned ? 0x99D95138 : App.LINE);
        card.setBackground(background);
        card.setElevation(Ui.dp(this, 1));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP);
        cardLp.bottomMargin = Ui.dp(this, 11);
        card.setLayoutParams(cardLp);

        LinearLayout head = Ui.row(this);
        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(Ui.background(clip.isImage() ? App.GREEN_SOFT : App.PAPER_SOFT, 8, 0, 0));
        ImageView typeIcon = new ImageView(this);
        typeIcon.setImageResource(clip.isImage() ? R.drawable.ic_image : R.drawable.ic_text);
        typeIcon.setColorFilter(clip.isImage() ? App.GREEN_INK : App.MUTED);
        FrameLayout.LayoutParams typeLp = new FrameLayout.LayoutParams(Ui.dp(this, 13), Ui.dp(this, 13), Gravity.CENTER);
        badge.addView(typeIcon, typeLp);
        head.addView(badge, new LinearLayout.LayoutParams(Ui.dp(this, 27), Ui.dp(this, 27)));

        LinearLayout meta = Ui.column(this);
        TextView device = Ui.text(this, clip.device, 11f, App.INK_SOFT, Ui.sansMedium(this));
        device.setSingleLine(true);
        device.setEllipsize(TextUtils.TruncateAt.END);
        TextView time = Ui.text(this, relativeTime(clip.createdAt), 9f, App.MUTED_LIGHT, Ui.sans(this));
        meta.addView(device);
        meta.addView(time);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        metaLp.leftMargin = Ui.dp(this, 8);
        head.addView(meta, metaLp);

        ImageButton pin = Ui.iconButton(this, R.drawable.ic_pin,
                clip.pinned ? getString(R.string.unpin) : getString(R.string.pin),
                clip.pinned ? App.ACCENT : App.MUTED_LIGHT);
        pin.setOnClickListener(v -> togglePin(clip));
        head.addView(pin, new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34)));

        ImageButton delete = Ui.iconButton(this, R.drawable.ic_trash, getString(R.string.delete), App.MUTED_LIGHT);
        delete.setOnClickListener(v -> confirmDelete(clip));
        head.addView(delete, new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34)));

        card.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP));

        if (clip.isImage()) {
            ImageView image = new ImageView(this);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setMinimumHeight(Ui.dp(this, 120));
            image.setMaxHeight(Ui.dp(this, 330));
            image.setBackground(Ui.background(0xFFF0EADE, 10, App.LINE, 1));
            image.setClipToOutline(true);
            image.setContentDescription(clip.imageName);
            LinearLayout.LayoutParams imageLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP);
            imageLp.topMargin = Ui.dp(this, 10);
            card.addView(image, imageLp);
            loadImage(clip, image);
            image.setOnClickListener(v -> openImageDialog(clip));
        } else {
            final TextView body = Ui.text(this, clip.text, 13.5f, App.INK_SOFT, Ui.sans(this));
            body.setLineSpacing(Ui.dp(this, 3), 1.05f);
            body.setTextIsSelectable(true);
            body.setMaxLines(12);
            body.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP);
            bodyLp.topMargin = Ui.dp(this, 9);
            card.addView(body, bodyLp);
            if (clip.text != null && (clip.text.length() > 620 || countNewlines(clip.text) > 12)) {
                TextView more = Ui.text(this, getString(R.string.show_more), 10.5f, App.ACCENT_DEEP, Ui.sansBold(this));
                more.setPadding(0, Ui.dp(this, 7), 0, 0);
                more.setOnClickListener(v -> {
                    body.setMaxLines(Integer.MAX_VALUE);
                    body.setEllipsize(null);
                    more.setVisibility(View.GONE);
                });
                card.addView(more);
            }
        }

        LinearLayout foot = Ui.row(this);
        foot.setPadding(0, Ui.dp(this, 10), 0, 0);
        TextView copy = Ui.pillButton(this, R.drawable.ic_copy,
                clip.isImage() ? getString(R.string.copy_image) : getString(R.string.copy),
                false);
        copy.setOnClickListener(v -> {
            if (clip.isImage()) copyImage(clip);
            else copyText(clip.text);
        });
        foot.addView(copy, new LinearLayout.LayoutParams(WRAP, Ui.dp(this, 34)));

        if (clip.isImage()) {
            ImageButton save = Ui.iconButton(this, R.drawable.ic_download, getString(R.string.save), App.MUTED);
            LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32));
            saveLp.leftMargin = Ui.dp(this, 4);
            foot.addView(save, saveLp);
            save.setOnClickListener(v -> saveImage(clip));
        }

        View spacer = new View(this);
        foot.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView size = Ui.text(this, clip.isImage() ? formatBytes(clip.imageSize)
                : ((clip.text == null ? 0 : clip.text.length()) + " chars"), 9f, App.MUTED_LIGHT, Ui.sans(this));
        foot.addView(size);
        card.addView(foot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP));
        return card;
    }

    private int countNewlines(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') count++;
        return count;
    }

    private void setOnline(boolean online) {
        if (statusPill == null || statusText == null || statusDot == null) return;
        statusPill.setBackground(Ui.pill(online ? App.GREEN_SOFT : 0xFFFBEAE6, 50));
        statusDot.setBackground(Ui.pill(online ? App.SAGE : App.DANGER, 50));
        statusText.setText(online ? "Live" : "Offline");
        statusText.setTextColor(online ? App.GREEN_INK : App.DANGER);
    }

    private void setSyncing() {
        if (statusText == null) return;
        statusText.setText("Syncing");
        statusText.setTextColor(App.MUTED);
        if (statusDot != null) statusDot.setBackground(Ui.pill(App.GOLD, 50));
    }

    // ---------------------------------------------------------------------
    // Refresh
    // ---------------------------------------------------------------------

    private String signature(List<Clip> list) {
        StringBuilder builder = new StringBuilder();
        for (Clip clip : list) {
            builder.append(clip.id).append(':').append(clip.pinned ? '1' : '0').append(';');
        }
        return builder.toString();
    }

    private void refreshClips(boolean silent) {
        if (!loggedIn || api == null || loadingClips) return;
        loadingClips = true;
        if (!silent) setSyncing();
        io.execute(() -> {
            try {
                JSONArray array = api.clips();
                List<Clip> next = new ArrayList<>();
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject item = array.optJSONObject(i);
                        Clip clip = Clip.fromJson(item);
                        if (clip != null && clip.id != null && !clip.id.isEmpty()) next.add(clip);
                    }
                }
                String sig = signature(next);
                runOnUiThread(() -> {
                    loadingClips = false;
                    if (!loggedIn) return;
                    clips = next;
                    if (!sig.equals(lastSignature)) {
                        lastSignature = sig;
                        renderClips();
                    }
                    setOnline(true);
                    processPendingShare();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    loadingClips = false;
                    if (!loggedIn) return;
                    if (error instanceof ApiClient.Unauthorized) {
                        expireSession();
                        return;
                    }
                    setOnline(false);
                    if (!silent) toast(friendly(error));
                });
            }
        });
    }

    // ---------------------------------------------------------------------
    // Text send
    // ---------------------------------------------------------------------

    private void sendComposer() {
        if (sending) return;
        String text = composer.getText().toString();
        if (Ui.isBlank(text)) {
            composer.requestFocus();
            toast("Paste something first.");
            return;
        }
        sendText(text, true);
    }

    private void sendText(final String text, final boolean clearComposer) {
        if (sending) return;
        if (text.length() > 100000) {
            toast("Text is limited to 100,000 characters.");
            return;
        }
        sending = true;
        if (sendButton != null) {
            sendButton.setEnabled(false);
            sendButton.setAlpha(0.65f);
            sendButton.setText("Pasting…");
        }
        io.execute(() -> {
            try {
                api.sendText(text, deviceName);
                runOnUiThread(() -> {
                    sending = false;
                    restoreSendButton();
                    if (clearComposer && composer != null) composer.setText("");
                    toast(getString(R.string.text_ready));
                    refreshClips(true);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    sending = false;
                    restoreSendButton();
                    if (error instanceof ApiClient.Unauthorized) expireSession();
                    else toast(friendly(error));
                });
            }
        });
    }

    private void restoreSendButton() {
        if (sendButton == null) return;
        sendButton.setEnabled(true);
        sendButton.setAlpha(1f);
        sendButton.setText(getString(R.string.send));
    }

    private void copyText(String text) {
        if (text == null) return;
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        manager.setPrimaryClip(ClipData.newPlainText("Pasta", text));
        toast(getString(R.string.copied_text));
    }

    // ---------------------------------------------------------------------
    // Image send
    // ---------------------------------------------------------------------

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            String mime = getContentResolver().getType(uri);
            uploadImage(uri, mime, queryName(uri));
        }
    }

    private void sendFromClipboard() {
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (!manager.hasPrimaryClip() || manager.getPrimaryClip() == null
                || manager.getPrimaryClip().getItemCount() == 0) {
            toast(getString(R.string.empty_clipboard));
            return;
        }
        ClipData clip = manager.getPrimaryClip();
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            Uri uri = item.getUri();
            if (uri != null) {
                String mime = getContentResolver().getType(uri);
                if (mime == null) mime = clip.getDescription().getMimeType(i);
                if (mime != null && mime.startsWith("image/")) {
                    uploadImage(uri, mime, queryName(uri));
                    return;
                }
            }
        }
        String text = null;
        for (int i = 0; i < clip.getItemCount(); i++) {
            CharSequence value = clip.getItemAt(i).coerceToText(this);
            if (!TextUtils.isEmpty(value)) {
                text = value.toString();
                break;
            }
        }
        if (TextUtils.isEmpty(text)) {
            toast(getString(R.string.empty_clipboard));
            return;
        }
        if (text.length() > 100000) {
            toast("That clipboard text is over 100,000 characters.");
            return;
        }
        if (composer != null) {
            composer.setText(text);
            composer.requestFocus();
            composer.setSelection(composer.getText().length());
            showKeyboard(composer);
        }
        toast(getString(R.string.clipboard_loaded));
    }

    private void uploadImage(Uri uri, String mime, String name) {
        if (busy) {
            toast("Pasta is still busy with the last image.");
            return;
        }
        busy = true;
        showBusy("Pasting image…");
        io.execute(() -> {
            File file = null;
            try {
                PreparedImage prepared = prepareImage(uri, mime, name);
                file = prepared.file;
                api.sendImage(file, prepared.mime, prepared.name, prepared.width, prepared.height, deviceName);
                runOnUiThread(() -> {
                    busy = false;
                    dismissBusy();
                    toast(getString(R.string.image_ready));
                    refreshClips(true);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    busy = false;
                    dismissBusy();
                    if (error instanceof ApiClient.Unauthorized) expireSession();
                    else toast(friendly(error));
                });
            } finally {
                if (file != null) file.delete();
            }
        });
    }

    private PreparedImage prepareImage(Uri uri, String mime, String originalName) throws Exception {
        ContentResolver resolver = getContentResolver();
        if (mime == null) mime = resolver.getType(uri);
        if (mime == null) mime = guessMime(originalName);
        if (mime == null || !mime.startsWith("image/")) throw new IOException("That file is not an image.");

        long size = querySize(uri);
        boolean gif = "image/gif".equals(mime) || (originalName != null && originalName.toLowerCase(Locale.US).endsWith(".gif"));
        File dir = new File(getCacheDir(), "clips");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not prepare storage.");

        if (gif) {
            if (size > 20L * 1024L * 1024L) throw new IOException("GIFs are limited to 20 MB.");
            File target = File.createTempFile("pasta-share-", ".gif", dir);
            try (InputStream input = resolver.openInputStream(uri); FileOutputStream output = new FileOutputStream(target)) {
                copy(input, output);
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = resolver.openInputStream(uri)) {
                BitmapFactory.decodeStream(input, null, bounds);
            }
            return new PreparedImage(target, "image/gif", safeName(originalName, "pasted.gif"),
                    Math.max(0, bounds.outWidth), Math.max(0, bounds.outHeight));
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("That image could not be read.");

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 2400);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try (InputStream input = resolver.openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(input, null, options);
        }
        if (bitmap == null) throw new IOException("That image could not be read.");

        int rotation = exifRotation(uri);
        if (rotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) bitmap.recycle();
            bitmap = rotated;
        }

        int maxSide = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (maxSide > 2400) {
            float scale = 2400f / maxSide;
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                    Math.max(1, Math.round(bitmap.getWidth() * scale)),
                    Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
            if (scaled != bitmap) bitmap.recycle();
            bitmap = scaled;
        }

        File target = File.createTempFile("pasta-share-", ".webp", dir);
        try (FileOutputStream output = new FileOutputStream(target)) {
            if (!bitmap.compress(Bitmap.CompressFormat.WEBP, 92, output)) {
                throw new IOException("Could not prepare the image.");
            }
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        bitmap.recycle();
        if (target.length() > 20L * 1024L * 1024L) {
            target.delete();
            throw new IOException("That image is over 20 MB. Try a smaller one.");
        }
        String base = originalName == null ? "pasted-image" : originalName.replaceAll("\\.[^.]+$", "");
        return new PreparedImage(target, "image/webp", safeName(base, "pasted-image") + ".webp", width, height);
    }

    private int exifRotation(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) return 0;
            ExifInterface exif = new ExifInterface(input);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int sampleSize(int width, int height, int target) {
        int sample = 1;
        int max = Math.max(width, height);
        while (max / (sample * 2) >= target) sample *= 2;
        return sample;
    }

    private String queryName(Uri uri) {
        if (uri == null) return null;
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private long querySize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0) return cursor.getLong(index);
            }
        } catch (Exception ignored) {
        }
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            return input == null ? 0 : input.available();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String guessMime(String name) {
        if (name == null) return null;
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return null;
    }

    private String safeName(String name, String fallback) {
        if (name == null) return fallback;
        String cleaned = new File(name).getName().replaceAll("[\\r\\n\\t]", "").trim();
        if (cleaned.isEmpty()) return fallback;
        return cleaned.length() > 100 ? cleaned.substring(0, 100) : cleaned;
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
    }

    // ---------------------------------------------------------------------
    // Image display / copy / save
    // ---------------------------------------------------------------------

    private void loadImage(Clip clip, ImageView view) {
        Bitmap cached = imageCache.get(clip.id);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setTag(clip.id);
        io.execute(() -> {
            try {
                byte[] bytes = api.image(clip.id);
                Bitmap bitmap = decodeSampled(bytes, 1600);
                if (bitmap == null) throw new IOException("Could not decode image.");
                imageCache.put(clip.id, bitmap);
                runOnUiThread(() -> {
                    if (clip.id.equals(view.getTag())) {
                        view.clearColorFilter();
                        view.setImageBitmap(bitmap);
                    }
                });
            } catch (ApiClient.Unauthorized error) {
                runOnUiThread(this::expireSession);
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    view.setImageResource(R.drawable.ic_image);
                    view.setColorFilter(App.MUTED_LIGHT);
                });
            }
        });
    }

    private Bitmap decodeSampled(byte[] bytes, int maxSide) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private void copyImage(final Clip clip) {
        io.execute(() -> {
            try {
                File file = ensureClipboardFile(clip);
                Uri uri = PastaProvider.uriFor(this, file);
                runOnUiThread(() -> {
                    try {
                        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData data = ClipData.newUri(getContentResolver(), "Pasta image", uri);
                        manager.setPrimaryClip(data);
                        toast(getString(R.string.copied_image));
                    } catch (Exception error) {
                        toast("Could not copy the image.");
                    }
                });
            } catch (ApiClient.Unauthorized error) {
                runOnUiThread(this::expireSession);
            } catch (Exception error) {
                runOnUiThread(() -> toast(friendly(error)));
            }
        });
    }

    private File ensureClipboardFile(Clip clip) throws Exception {
        File dir = new File(getCacheDir(), "clips");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not prepare storage.");
        File png = new File(dir, clip.id + ".png");
        if (png.exists() && png.length() > 0) return png;
        byte[] bytes = api.image(clip.id);
        Bitmap bitmap = decodeSampled(bytes, 2400);
        if (bitmap != null) {
            try (FileOutputStream output = new FileOutputStream(png)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            }
            bitmap.recycle();
            return png;
        }
        String extension = extensionFor(clip.imageMime);
        File raw = new File(dir, clip.id + "." + extension);
        try (FileOutputStream output = new FileOutputStream(raw)) {
            output.write(bytes);
        }
        return raw;
    }

    private File ensureOriginalFile(Clip clip) throws Exception {
        File dir = new File(getCacheDir(), "clips");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not prepare storage.");
        File file = new File(dir, clip.id + "." + extensionFor(clip.imageMime));
        if (file.exists() && file.length() > 0) return file;
        byte[] bytes = api.image(clip.id);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
        return file;
    }

    private String extensionFor(String mime) {
        if (mime == null) return "bin";
        if (mime.contains("png")) return "png";
        if (mime.contains("jpeg") || mime.contains("jpg")) return "jpg";
        if (mime.contains("gif")) return "gif";
        if (mime.contains("webp")) return "webp";
        return "bin";
    }

    private void saveImage(final Clip clip) {
        io.execute(() -> {
            try {
                File source = ensureOriginalFile(clip);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, safeName(clip.imageName, "pasta-image"));
                    values.put(MediaStore.Images.Media.MIME_TYPE, clip.imageMime == null ? "image/webp" : clip.imageMime);
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Pasta");
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);
                    Uri destination = getContentResolver().insert(
                            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
                    if (destination == null) throw new IOException("Could not create the image.");
                    try (InputStream input = new FileInputStream(source);
                         OutputStream output = getContentResolver().openOutputStream(destination)) {
                        if (output == null) throw new IOException("Could not save the image.");
                        copy(input, output);
                    }
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(destination, done, null, null);
                    runOnUiThread(() -> toast(getString(R.string.saved)));
                } else {
                    Uri uri = PastaProvider.uriFor(this, source);
                    runOnUiThread(() -> {
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType(clip.imageMime == null ? "image/*" : clip.imageMime);
                        share.putExtra(Intent.EXTRA_STREAM, uri);
                        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(share, getString(R.string.save)));
                    });
                }
            } catch (ApiClient.Unauthorized error) {
                runOnUiThread(this::expireSession);
            } catch (Exception error) {
                runOnUiThread(() -> toast(friendly(error)));
            }
        });
    }

    private void openImageDialog(final Clip clip) {
        if (imageDialog != null) imageDialog.dismiss();
        final Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding(Ui.dp(this, 6), Ui.dp(this, 48), Ui.dp(this, 6), Ui.dp(this, 66));
        frame.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageButton close = Ui.iconButton(this, R.drawable.ic_close, getString(R.string.close), Color.WHITE);
        close.setBackground(Ui.pill(0x66000000, 50));
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40), Gravity.TOP | Gravity.END);
        closeLp.topMargin = Ui.dp(this, 18);
        closeLp.rightMargin = Ui.dp(this, 16);
        frame.addView(close, closeLp);
        close.setOnClickListener(v -> dialog.dismiss());

        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 16));
        bar.setBackgroundColor(0xCC0E0D0B);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP, Gravity.BOTTOM);
        frame.addView(bar, barLp);

        TextView copy = darkButton(getString(R.string.copy_image), R.drawable.ic_copy);
        copy.setOnClickListener(v -> copyImage(clip));
        bar.addView(copy);
        TextView save = darkButton(getString(R.string.save), R.drawable.ic_download);
        save.setOnClickListener(v -> saveImage(clip));
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        saveLp.leftMargin = Ui.dp(this, 8);
        bar.addView(save, saveLp);

        dialog.setContentView(frame);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        dialog.show();
        imageDialog = dialog;

        Bitmap cached = imageCache.get(clip.id);
        if (cached != null) {
            image.setImageBitmap(cached);
        } else {
            io.execute(() -> {
                try {
                    byte[] bytes = api.image(clip.id);
                    Bitmap bitmap = decodeSampled(bytes, 2400);
                    if (bitmap == null) throw new IOException("Could not decode image.");
                    imageCache.put(clip.id, bitmap);
                    runOnUiThread(() -> image.setImageBitmap(bitmap));
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        toast(friendly(error));
                        dialog.dismiss();
                    });
                }
            });
        }
        dialog.setOnDismissListener(d -> {
            image.setImageDrawable(null);
            if (imageDialog == dialog) imageDialog = null;
        });
    }

    // ---------------------------------------------------------------------
    // Pin / delete
    // ---------------------------------------------------------------------

    private void togglePin(final Clip clip) {
        io.execute(() -> {
            try {
                api.setPinned(clip.id, !clip.pinned);
                runOnUiThread(() -> {
                    toast(clip.pinned ? "Unpinned." : "Pinned to the top.");
                    refreshClips(true);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (error instanceof ApiClient.Unauthorized) expireSession();
                    else toast(friendly(error));
                });
            }
        });
    }

    private void confirmDelete(final Clip clip) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.delete_title))
                .setMessage(getString(R.string.delete_copy))
                .setNegativeButton(getString(R.string.keep), null)
                .setPositiveButton(getString(R.string.delete), (dialog, which) -> deleteClip(clip))
                .show();
    }

    private void deleteClip(final Clip clip) {
        io.execute(() -> {
            try {
                api.delete(clip.id);
                runOnUiThread(() -> {
                    toast("Paste deleted.");
                    refreshClips(true);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (error instanceof ApiClient.Unauthorized) expireSession();
                    else toast(friendly(error));
                });
            }
        });
    }

    // ---------------------------------------------------------------------
    // Share target
    // ---------------------------------------------------------------------

    private void handleShareIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String type = intent.getType();
        if (type != null && type.startsWith("image/")) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0) {
                uri = intent.getClipData().getItemAt(0).getUri();
            }
            if (uri != null) {
                pendingShareImage = uri;
                pendingShareMime = type;
            }
        } else {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (!TextUtils.isEmpty(text)) pendingShareText = text.toString();
        }
        if (loggedIn && lastSignature != null) processPendingShare();
    }

    private void processPendingShare() {
        if (!loggedIn) return;
        if (pendingShareImage != null) {
            Uri uri = pendingShareImage;
            String mime = pendingShareMime;
            pendingShareImage = null;
            pendingShareMime = null;
            uploadImage(uri, mime, "shared-image");
        } else if (pendingShareText != null) {
            String text = pendingShareText;
            pendingShareText = null;
            if (!Ui.isBlank(text)) sendText(text, false);
        }
    }

    // ---------------------------------------------------------------------
    // Settings / session
    // ---------------------------------------------------------------------

    private void showSettings() {
        String message = "Server\n" + baseUrl + "\n\nDevice\n" + deviceName
                + "\n\nPasta for Android · v1.0";
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_title))
                .setMessage(message)
                .setPositiveButton(getString(R.string.refresh), (dialog, which) -> refreshClips(false))
                .setNeutralButton(getString(R.string.sign_out), (dialog, which) -> confirmSignOut())
                .setNegativeButton(getString(R.string.close), null)
                .show();
    }

    private void confirmSignOut() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.sign_out) + "?")
                .setMessage("You will need the passphrase to unlock Pasta again on this phone.")
                .setNegativeButton(getString(R.string.keep), null)
                .setPositiveButton(getString(R.string.sign_out), (dialog, which) -> signOut())
                .show();
    }

    private void signOut() {
        token = null;
        if (api != null) api.setToken(null);
        prefs.edit().remove(KEY_TOKEN).apply();
        loggedIn = false;
        handler.removeCallbacks(autoRefresh);
        showLogin(null);
        toast("Signed out of this phone.");
    }

    private void expireSession() {
        if (!loggedIn) return;
        loggedIn = false;
        token = null;
        if (api != null) api.setToken(null);
        prefs.edit().remove(KEY_TOKEN).apply();
        handler.removeCallbacks(autoRefresh);
        showLogin(getString(R.string.session_expired));
        toast(getString(R.string.session_expired));
    }

    // ---------------------------------------------------------------------
    // Busy dialog / helpers
    // ---------------------------------------------------------------------

    private void showBusy(String message) {
        dismissBusy();
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = Ui.column(this);
        box.setGravity(Gravity.CENTER);
        box.setPadding(Ui.dp(this, 24), Ui.dp(this, 22), Ui.dp(this, 24), Ui.dp(this, 22));
        box.setBackground(Ui.background(App.CARD, 18, App.LINE, 1));
        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(App.ACCENT));
        box.addView(progress, new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30)));
        TextView label = Ui.text(this, message, 12f, App.INK_SOFT, Ui.sansMedium(this));
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        labelLp.topMargin = Ui.dp(this, 12);
        box.addView(label, labelLp);
        dialog.setContentView(box);
        dialog.setCancelable(false);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(Ui.dp(this, 230), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        busyDialog = dialog;
    }

    private void dismissBusy() {
        if (busyDialog != null) {
            try { busyDialog.dismiss(); } catch (Exception ignored) {}
            busyDialog = null;
        }
    }

    private String friendly(Exception error) {
        if (error instanceof ApiClient.ApiException) {
            String message = error.getMessage();
            if (message != null && !message.isEmpty()) return message;
        }
        if (error instanceof java.io.IOException) return getString(R.string.network_error);
        return "Something went wrong. Try again.";
    }

    private String relativeTime(long timestamp) {
        long delta = Math.max(0, System.currentTimeMillis() - timestamp);
        if (delta < 45000) return "just now";
        if (delta < 3600000) return (delta / 60000) + "m";
        if (delta < 86400000) return (delta / 3600000) + "h";
        if (delta < 7 * 86400000L) return (delta / 86400000) + "d";
        return new java.text.SimpleDateFormat("MMM d", Locale.getDefault()).format(new java.util.Date(timestamp));
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private String deviceName() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        String name = (manufacturer + " " + model).trim();
        if (name.isEmpty() || "unknown".equalsIgnoreCase(name)) return "Android phone";
        if (name.length() > 48) name = name.substring(0, 48);
        return name;
    }

    private void toast(String message) {
        if (isFinishing() || message == null || message.isEmpty()) return;
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void hideKeyboard() {
        View view = getCurrentFocus();
        if (view == null) return;
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    private void showKeyboard(View view) {
        if (view == null) return;
        view.postDelayed(() -> {
            InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (manager != null) manager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        }, 120);
    }

    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final class PreparedImage {
        final File file;
        final String mime;
        final String name;
        final int width;
        final int height;

        PreparedImage(File file, String mime, String name, int width, int height) {
            this.file = file;
            this.mime = mime;
            this.name = name;
            this.width = width;
            this.height = height;
        }
    }
}
