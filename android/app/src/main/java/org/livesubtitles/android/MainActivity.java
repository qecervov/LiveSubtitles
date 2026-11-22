package org.livesubtitles.android;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class MainActivity extends Activity {
    private static final int AUDIO = 101, CAPTURE = 102;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<AppEntry> apps = new ArrayList<>();
    private static final String[] CODES = {"en", "auto", "ru", "de", "fr", "es", "it", "pt", "uk", "ja", "ko", "zh"};
    private TextView status, modelsLabel, permissionLabel, fontLabel;
    private Button download, start, stop;
    private ProgressBar progress;
    private Spinner appPicker, language;
    private CheckBox original;
    private SeekBar fontSize;
    private SharedPreferences prefs;
    private AppEntry selected;
    private boolean resumed;
    private final Runnable update = new Runnable() {
        @Override public void run() { refresh(); if (resumed) ui.postDelayed(this, 500); }
    };
    private static final class AppEntry {
        final String label, packageName; final int uid;
        AppEntry(String label, String packageName, int uid) { this.label = label; this.packageName = packageName; this.uid = uid; }
        @Override public String toString() { return label; }
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        if (Models.ready(this) && !State.capturing && !State.downloading && State.status.equals("Подготовьте модели и выберите приложение с видео.")) State.status = "Модели готовы. Выберите приложение с видео.";
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(0xFF111721);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(22), dp(22), dp(22), dp(28)); scroll.addView(page);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop(); right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(dp(22) + left, dp(22) + top, dp(22) + right, dp(28) + bottom); return insets;
        });
        TextView title = text("CC  LiveSubtitles", 27, 0xFF83E4C4); title.setTypeface(null, Typeface.BOLD); page.addView(title);
        page.addView(text("Перевод видео на вашем телефоне", 14, 0xFFAEBBD0));
        status = text(State.status, 15, Color.WHITE); status.setPadding(dp(15), dp(14), dp(15), dp(14)); status.setBackground(background(0xFF1C2938, 12)); add(page, status, 20, 14);

        section(page, "1. Локальные модели");
        modelsLabel = text("Для первого запуска потребуется около 230 МБ загрузки и 700 МБ свободного места. После установки модели работают без сети.", 13, 0xFFAEBBD0); page.addView(modelsLabel);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100); progress.setProgressTintList(ColorStateList.valueOf(0xFF83E4C4)); add(page, progress, 8, 6);
        download = button("Скачать модели", true); download.setOnClickListener(v -> {
            if (State.downloading) startService(new Intent(this, DownloadService.class).setAction("STOP"));
            else startForegroundService(new Intent(this, DownloadService.class));
        }); add(page, download, 6, 18);

        section(page, "2. Приложение с видео");
        appPicker = new Spinner(this); loadApps(); add(page, appPicker, 4, 5);
        TextView refreshApps = text("Обновить список приложений", 12, 0xFF83E4C4); refreshApps.setPadding(0, dp(9), 0, dp(9)); refreshApps.setOnClickListener(v -> loadApps()); page.addView(refreshApps);
        section(page, "3. Язык речи → русские субтитры");
        language = new Spinner(this);
        String[] labels = {"Английский", "Авто — через английский", "Русский — без перевода", "Немецкий — через английский", "Французский — через английский", "Испанский — через английский", "Итальянский — через английский", "Португальский — через английский", "Украинский — через английский", "Японский — через английский", "Корейский — через английский", "Китайский — через английский"};
        ArrayAdapter<String> languages = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels); language.setAdapter(languages);
        String source = prefs.getString("language", "en"); for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(source)) language.setSelection(i);
        add(page, language, 4, 10);
        original = new CheckBox(this); original.setText("Показывать распознанный текст"); original.setTextColor(0xFFDCE6F3); original.setTextSize(14); original.setChecked(prefs.getBoolean("original", false)); page.addView(original);
        page.addView(text("Для других языков распознанный текст будет промежуточным английским переводом.", 11, 0xFF8D9EB6));
        fontLabel = text("", 13, 0xFFAEBBD0); add(page, fontLabel, 13, 0);
        fontSize = new SeekBar(this); fontSize.setMax(18); fontSize.setProgress(Math.max(0, Math.min(18, prefs.getInt("font", 24) - 18)));
        fontLabel.setText("Размер субтитров: " + (fontSize.getProgress() + 18));
        fontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) { fontLabel.setText("Размер субтитров: " + (value + 18)); }
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) { saveSettings(); }
        }); add(page, fontSize, 0, 12);
        permissionLabel = text("", 12, 0xFFAEBBD0); page.addView(permissionLabel);
        Button overlayPermission = button("Настроить показ поверх приложений", false);
        overlayPermission.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())))); add(page, overlayPermission, 7, 14);
        LinearLayout buttons = new LinearLayout(this); start = button("Начать перевод", true); stop = button("Стоп", false);
        buttons.addView(start, new LinearLayout.LayoutParams(0, dp(52), 2)); LinearLayout.LayoutParams stopLayout = new LinearLayout.LayoutParams(0, dp(52), 1); stopLayout.leftMargin = dp(10); buttons.addView(stop, stopLayout); page.addView(buttons);
        start.setOnClickListener(v -> begin()); stop.setOnClickListener(v -> startService(new Intent(this, CaptureService.class).setAction("STOP")));
        add(page, text("Захват начинается только с вашего разрешения. Микрофон не используется: обрабатывается звук выбранного приложения. Некоторым плеерам запрещено отдавать звук — в таком случае субтитры не появятся.", 12, 0xFF8D9EB6), 12, 10);
        add(page, text("Белый текст с чёрной обводкой показывается без фона. Нажмите на субтитры для кнопок ↻ и ✕; они скроются через 3 секунды. Перетаскивайте текст вверх или вниз. После перемотки нажмите ↻. Задержка зависит от речи и мощности устройства; нулевой задержки нет.", 12, 0xFF8D9EB6), 0, 16);
        section(page, "О проекте");
        page.addView(text("0.1.2 Android alpha · Локальная обработка\nПриложение не сохраняет аудио и историю субтитров. Хранятся модели и настройки. При загрузке моделей используются внешние хранилища; звук им не передаётся.", 12, 0xFFAEBBD0));
        LinearLayout links = new LinearLayout(this);
        Button telegram = button("Telegram", false), github = button("GitHub", false);
        telegram.setOnClickListener(v -> open("https://t.me/qecervov")); github.setOnClickListener(v -> open("https://github.com/qecervov/LiveSubtitles"));
        links.addView(telegram, new LinearLayout.LayoutParams(0, dp(48), 1)); LinearLayout.LayoutParams next = new LinearLayout.LayoutParams(0, dp(48), 1); next.leftMargin = dp(10); links.addView(github, next); add(page, links, 10, 0);
        TextView licenses = text("Лицензии и сторонние компоненты", 12, 0xFF83E4C4); licenses.setPadding(0, dp(12), 0, dp(8)); licenses.setOnClickListener(v -> showLicenses()); page.addView(licenses);
        setContentView(scroll);
    }

    private void loadApps() {
        apps.clear(); apps.add(new AppEntry("Выберите приложение…", "", -1));
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, AppEntry> unique = new HashMap<>();
        for (ResolveInfo info : getPackageManager().queryIntentActivities(launcher, 0)) {
            ApplicationInfo app = info.activityInfo.applicationInfo;
            if (!app.packageName.equals(getPackageName())) unique.put(app.packageName, new AppEntry(app.loadLabel(getPackageManager()).toString(), app.packageName, app.uid));
        }
        List<AppEntry> sorted = new ArrayList<>(unique.values()); sorted.sort(Comparator.comparing(a -> a.label.toLowerCase(Locale.ROOT))); apps.addAll(sorted);
        appPicker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, apps));
        String previous = prefs.getString("package", ""); for (int i = 0; i < apps.size(); i++) if (apps.get(i).packageName.equals(previous)) appPicker.setSelection(i);
    }
    private void refresh() {
        if (status == null) return;
        status.setText(State.status);
        boolean ready = Models.ready(this);
        modelsLabel.setText(ready ? "Модели установлены. Распознавание и перевод выполняются локально." : "Первый запуск: около 230 МБ загрузки, нужно 700 МБ свободного места. После установки модели работают без сети.");
        download.setText(State.downloading ? "Отменить загрузку" : ready ? "Модели установлены" : "Скачать модели");
        download.setEnabled(!State.capturing && (!ready || State.downloading));
        progress.setVisibility(State.downloading ? View.VISIBLE : View.GONE); progress.setProgress(Math.max(0, State.downloadProgress));
        start.setEnabled(ready && !State.downloading && !State.capturing); stop.setEnabled(State.capturing);
        appPicker.setEnabled(!State.capturing); language.setEnabled(!State.capturing); original.setEnabled(!State.capturing); fontSize.setEnabled(!State.capturing);
        permissionLabel.setText(Settings.canDrawOverlays(this) ? "✓ Показ поверх приложений разрешён" : "Для субтитров потребуется разрешение на показ поверх приложений.");
    }
    private void saveSettings() {
        if (language == null || fontSize == null) return;
        SharedPreferences.Editor editor = prefs.edit().putString("language", CODES[language.getSelectedItemPosition()]).putBoolean("original", original.isChecked()).putInt("font", fontSize.getProgress() + 18);
        int selectedIndex = appPicker.getSelectedItemPosition(); if (selectedIndex > 0 && selectedIndex < apps.size()) editor.putString("package", apps.get(selectedIndex).packageName);
        editor.apply();
    }
    private void begin() {
        int index = appPicker.getSelectedItemPosition();
        if (index <= 0 || index >= apps.size()) { toast("Выберите приложение с видео."); return; }
        if (!Models.ready(this)) { toast("Сначала скачайте модели."); return; }
        if (!Settings.canDrawOverlays(this)) { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); toast("Разрешите показ поверх окон, затем нажмите «Начать» ещё раз."); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, AUDIO);
            else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO);
            return;
        }
        selected = apps.get(index); saveSettings();
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        if (Build.VERSION.SDK_INT >= 34) startActivityForResult(manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()), CAPTURE);
        else startActivityForResult(manager.createScreenCaptureIntent(), CAPTURE);
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == AUDIO) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin();
            else toast("Без разрешения на аудио захват звука недоступен.");
        }
    }
    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != CAPTURE) return;
        if (result != RESULT_OK || data == null || selected == null) { toast("Захват отменён."); return; }
        Intent service = new Intent(this, CaptureService.class).putExtra("consent", data).putExtra("result", result)
            .putExtra("uid", selected.uid).putExtra("package", selected.packageName).putExtra("language", CODES[language.getSelectedItemPosition()])
            .putExtra("original", original.isChecked()).putExtra("font", fontSize.getProgress() + 18);
        startForegroundService(service);
        Intent launch = getPackageManager().getLaunchIntentForPackage(selected.packageName);
        if (launch != null) { try { startActivity(launch); } catch (ActivityNotFoundException ignored) { } }
    }
    @Override protected void onResume() { super.onResume(); resumed = true; ui.removeCallbacks(update); ui.post(update); }
    @Override protected void onPause() { saveSettings(); resumed = false; ui.removeCallbacks(update); super.onPause(); }
    private void open(String url) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (ActivityNotFoundException ignored) { toast("Не найден браузер для ссылки."); } }
    private void showLicenses() {
        try (java.io.InputStream input = getAssets().open("third-party-licenses.txt")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            TextView content = text(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8), 12, 0xFFDCE6F3); content.setPadding(dp(16), dp(8), dp(16), dp(8)); content.setTextIsSelectable(true);
            ScrollView view = new ScrollView(this); view.addView(content); new AlertDialog.Builder(this).setTitle("Лицензии").setView(view).setPositiveButton("Закрыть", null).show();
        } catch (java.io.IOException ignored) { toast("Файл лицензий недоступен."); }
    }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return (int) (getResources().getDisplayMetrics().density * value + .5f); }
    private TextView text(String value, int size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view; }
    private void section(LinearLayout page, String title) { TextView label = text(title, 16, Color.WHITE); label.setTypeface(null, Typeface.BOLD); add(page, label, 4, 8); }
    private Button button(String title, boolean primary) {
        Button view = new Button(this); view.setText(title); view.setAllCaps(false); view.setTextSize(14); view.setTextColor(primary ? 0xFF102B23 : 0xFFDCE6F3); view.setBackgroundTintList(ColorStateList.valueOf(primary ? 0xFF83E4C4 : 0xFF263447)); return view;
    }
    private GradientDrawable background(int color, int radius) { GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape; }
    private void add(LinearLayout page, View child, int above, int below) { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(above); params.bottomMargin = dp(below); page.addView(child, params); }
}
