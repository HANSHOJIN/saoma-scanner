package com.saoma.scanner;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.Image;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Size;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity {

    private static final int REQ_CAMERA = 101;
    private static final int REQ_SAVE_CSV = 102;

    /** 扫描结果持久化，避免切到别的界面/被系统回收后丢数据 */
    private static final String PREFS_NAME = "saoma_prefs";
    private static final String KEY_RESULTS = "results";
    private static final String KEY_DEDUP = "dedup";
    private static final String KEY_FEEDBACK = "feedback";

    /** 记录成功时的提示方式 */
    private static final int FB_NONE = 0;
    private static final int FB_SOUND = 1;
    private static final int FB_SOUND_VIBRATE = 2;
    private static final int FB_VIBRATE = 3;

    /** 单条码持续识别多久后自动记录（毫秒） */
    private static final long AUTO_COMMIT_MS = 550L;
    /** 已记录的条码离开画面多久后才允许再次记录（毫秒） */
    private static final long SUPPRESS_RELEASE_MS = 2000L;
    private static final long FLASH_MS = 1300L;
    /** 重复条码提示的最小间隔，避免一直响 */
    private static final long DUPLICATE_SIGNAL_GAP_MS = 2000L;

    private PreviewView previewView;
    private OverlayView overlay;
    private TextView hintText;
    private TextView countText;
    private TextView torchButton;
    private TextView dedupButton;
    private TextView feedbackButton;
    private EditText resultEdit;

    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private BarcodeScanner scanner;
    private ProcessCameraProvider cameraProvider;
    private androidx.camera.core.Camera camera;
    private boolean torchOn;
    /** 是否去重：同一个条码值只记录一次 */
    private boolean dedupEnabled = true;
    /** 记录成功的提示方式：无 / 声音 / 声音+震动 / 震动 */
    private int feedbackMode = FB_SOUND_VIBRATE;
    private SharedPreferences prefs;
    private Vibrator vibrator;
    private ToneGenerator toneGenerator;

    /** 每个条码值最近一次出现在画面里的时间 */
    private final Map<String, Long> lastSeen = new HashMap<>();
    /** 已经记录过的条码值，短暂屏蔽避免重复记录 */
    private final Map<String, Long> suppressed = new HashMap<>();
    /** 当前这一帧画面里出现的条码（归一化 key），用于清除后不马上又被记回来 */
    private final Set<String> lastFrameKeys = new HashSet<>();

    private String pendingValue;
    private long pendingSince;
    private String flashMessage;
    private long flashUntil;
    private long lastDuplicateSignalAt;

    private static class RawDetection {
        final String value;
        final Rect box;

        RawDetection(String value, Rect box) {
            this.value = value;
            this.box = box;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        previewView = findViewById(R.id.previewView);
        overlay = findViewById(R.id.overlay);
        hintText = findViewById(R.id.hintText);
        countText = findViewById(R.id.countText);
        resultEdit = findViewById(R.id.resultEdit);
        torchButton = findViewById(R.id.torchButton);
        dedupButton = findViewById(R.id.dedupButton);
        feedbackButton = findViewById(R.id.feedbackButton);
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        dedupEnabled = prefs.getBoolean(KEY_DEDUP, true);
        updateDedupButton();
        feedbackMode = prefs.getInt(KEY_FEEDBACK, FB_SOUND_VIBRATE);
        updateFeedbackButton();
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
        } catch (Exception ignored) {
        }

        Button undoButton = findViewById(R.id.undoButton);
        Button clearButton = findViewById(R.id.clearButton);
        Button saveButton = findViewById(R.id.saveButton);
        Button copyButton = findViewById(R.id.copyButton);

        undoButton.setOnClickListener(v -> undoLast());
        clearButton.setOnClickListener(v -> confirmClearAll());
        saveButton.setOnClickListener(v -> saveCsv());
        copyButton.setOnClickListener(v -> copyToClipboard());
        torchButton.setOnClickListener(v -> toggleTorch());
        dedupButton.setOnClickListener(v -> {
            dedupEnabled = !dedupEnabled;
            prefs.edit().putBoolean(KEY_DEDUP, dedupEnabled).apply();
            updateDedupButton();
            pendingValue = null;
            toast(dedupEnabled ? "已开启去重：同一个条码只记录一次" : "已关闭去重：同一个条码可以记录多次");
        });
        feedbackButton.setOnClickListener(v -> {
            feedbackMode = (feedbackMode + 1) % 4;
            prefs.edit().putInt(KEY_FEEDBACK, feedbackMode).apply();
            updateFeedbackButton();
            switch (feedbackMode) {
                case FB_NONE:
                    toast("记录成功时不提示");
                    break;
                case FB_SOUND:
                    toast("记录成功时：响一声");
                    break;
                case FB_SOUND_VIBRATE:
                    toast("记录成功时：响一声 + 震动");
                    break;
                default:
                    toast("记录成功时：震动");
                    feedback(); // 让用户马上感受到效果
                    break;
            }
        });

        overlay.setTapListener(detection -> {
            if (detection != null && detection.value != null) {
                commit(detection.value.trim());
            }
        });

        resultEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateCount(s.toString());
                if (prefs != null) {
                    prefs.edit().putString(KEY_RESULTS, s.toString()).apply();
                }
            }
        });

        if (savedInstanceState == null) {
            String restored = prefs.getString(KEY_RESULTS, "");
            if (restored != null && !restored.isEmpty()) {
                resultEdit.setText(restored);
                resultEdit.setSelection(resultEdit.getText().length());
            }
        }

        scanner = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                        Barcode.FORMAT_CODE_128,
                        Barcode.FORMAT_CODE_39,
                        Barcode.FORMAT_CODE_93,
                        Barcode.FORMAT_CODABAR,
                        Barcode.FORMAT_EAN_13,
                        Barcode.FORMAT_EAN_8,
                        Barcode.FORMAT_ITF,
                        Barcode.FORMAT_UPC_A,
                        Barcode.FORMAT_UPC_E)
                .build());

        updateCount(resultEdit.getText().toString());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    // ---------------------------------------------------------------- 相机

    private void startCamera() {
        final ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCamera();
            } catch (Exception e) {
                setHint("相机启动失败：" + e, true);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera() {
        if (cameraProvider == null) {
            return;
        }
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(analysisExecutor, this::analyze);

        try {
            cameraProvider.unbindAll();
            camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, analysis);
            boolean hasFlash = camera.getCameraInfo().hasFlashUnit();
            torchButton.setVisibility(hasFlash ? View.VISIBLE : View.GONE);
            if (hasFlash && torchOn) {
                camera.getCameraControl().enableTorch(true);
                torchButton.setText(R.string.torch_off);
            }
        } catch (Exception e) {
            setHint("相机启动失败：" + e, true);
        }
    }

    /** 开关手机闪光灯（手电筒） */
    private void toggleTorch() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) {
            toast("这台设备没有可用的闪光灯");
            return;
        }
        final boolean target = !torchOn;
        final ListenableFuture<Void> future = camera.getCameraControl().enableTorch(target);
        future.addListener(() -> {
            try {
                future.get();
                torchOn = target;
                torchButton.setText(target ? R.string.torch_off : R.string.torch_on);
            } catch (Exception e) {
                toast("闪光灯切换失败：" + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyze(@NonNull ImageProxy image) {
        Image mediaImage = image.getImage();
        if (mediaImage == null) {
            image.close();
            return;
        }
        int rotation = image.getImageInfo().getRotationDegrees();
        boolean swapped = (rotation == 90 || rotation == 270);
        final int uprightWidth = swapped ? image.getHeight() : image.getWidth();
        final int uprightHeight = swapped ? image.getWidth() : image.getHeight();

        InputImage inputImage = InputImage.fromMediaImage(mediaImage, rotation);
        scanner.process(inputImage)
                .addOnSuccessListener(analysisExecutor, barcodes -> {
                    List<RawDetection> list = new ArrayList<>();
                    if (barcodes != null) {
                        for (Barcode barcode : barcodes) {
                            String value = barcode.getRawValue();
                            if (value == null) {
                                value = barcode.getDisplayValue();
                            }
                            if (value == null || value.trim().isEmpty()) {
                                continue;
                            }
                            list.add(new RawDetection(value.trim(), barcode.getBoundingBox()));
                        }
                    }
                    runOnUiThread(() -> handleDetections(list, uprightWidth, uprightHeight));
                })
                .addOnFailureListener(analysisExecutor, e -> {
                })
                .addOnCompleteListener(analysisExecutor, task -> image.close());
    }

    private void handleDetections(List<RawDetection> raw, int uprightWidth, int uprightHeight) {
        long now = SystemClock.elapsedRealtime();

        for (RawDetection d : raw) {
            lastSeen.put(dedupKey(d.value), now);
        }
        lastFrameKeys.clear();
        for (RawDetection d : raw) {
            lastFrameKeys.add(dedupKey(d.value));
        }
        // 已记录过的条码离开画面足够久后，解除屏蔽
        Iterator<Map.Entry<String, Long>> it = suppressed.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            long seen = lastSeen.containsKey(entry.getKey()) ? lastSeen.get(entry.getKey()) : 0L;
            if (now - seen > SUPPRESS_RELEASE_MS) {
                it.remove();
            }
        }

        int viewWidth = overlay.getWidth();
        int viewHeight = overlay.getHeight();

        // 已经记录过的值（去重时用）
        Set<String> recorded = new HashSet<>();
        if (dedupEnabled) {
            for (String line : splitLines(resultEdit.getText().toString())) {
                recorded.add(dedupKey(line));
            }
        }

        boolean sawDuplicate = false;
        List<OverlayView.Detection> visible = new ArrayList<>();
        for (RawDetection d : raw) {
            String key = dedupKey(d.value);
            if (suppressed.containsKey(key)) {
                continue;
            }
            if (dedupEnabled && recorded.contains(key)) {
                sawDuplicate = true;
                continue;
            }
            boolean duplicate = false;
            for (OverlayView.Detection existing : visible) {
                if (dedupKey(existing.value).equals(key)) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) {
                continue;
            }
            RectF box = mapToView(d.box, uprightWidth, uprightHeight, viewWidth, viewHeight);
            if (box == null) {
                continue;
            }
            visible.add(new OverlayView.Detection(d.value, box));
        }

        boolean multi = visible.size() > 1;

        if (visible.size() == 1) {
            String value = visible.get(0).value;
            if (!value.equals(pendingValue)) {
                pendingValue = value;
                pendingSince = now;
            } else if (now - pendingSince >= AUTO_COMMIT_MS) {
                commit(value);
            }
        } else {
            pendingValue = null;
        }

        overlay.setDetections(visible, multi);

        if (visible.isEmpty() && sawDuplicate) {
            flashMessage = null;
            setHint(getString(R.string.hint_duplicate), true);
            // 重复的条码也给提示音/震动（和“记录成功”的声音、震动模式不同），避免用户反复扫
            if (now - lastDuplicateSignalAt > DUPLICATE_SIGNAL_GAP_MS) {
                lastDuplicateSignalAt = now;
                duplicateFeedback();
            }
        } else {
            updateHint(visible.size(), multi);
        }
    }

    /** 文字提示，warn=true 时用红底，更容易注意到 */
    private void setHint(CharSequence text, boolean warn) {
        hintText.setText(text);
        hintText.setBackgroundColor(warn ? 0xCCD32F2F : 0x99000000);
    }

    private void updateDedupButton() {
        dedupButton.setText(dedupEnabled ? R.string.dedup_on : R.string.dedup_off);
        dedupButton.setTextColor(dedupEnabled ? 0xFF2E7D32 : 0xFF9E9E9E);
    }

    private void updateFeedbackButton() {
        switch (feedbackMode) {
            case FB_NONE:
                feedbackButton.setText(R.string.fb_none);
                feedbackButton.setTextColor(0xFF9E9E9E);
                break;
            case FB_SOUND:
                feedbackButton.setText(R.string.fb_sound);
                feedbackButton.setTextColor(0xFF2E7D32);
                break;
            case FB_VIBRATE:
                feedbackButton.setText(R.string.fb_vibrate);
                feedbackButton.setTextColor(0xFF2E7D32);
                break;
            default:
                feedbackButton.setText(R.string.fb_sound_vibrate);
                feedbackButton.setTextColor(0xFF2E7D32);
                break;
        }
    }

    /** 把 ML Kit 的「图像坐标」换算成 PreviewView 的「视图坐标」（PreviewView 默认 FILL_CENTER） */
    private RectF mapToView(Rect box, int imageWidth, int imageHeight, int viewWidth, int viewHeight) {
        if (box == null || imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return null;
        }
        float scale = Math.max((float) viewWidth / imageWidth, (float) viewHeight / imageHeight);
        float dx = (viewWidth - imageWidth * scale) / 2f;
        float dy = (viewHeight - imageHeight * scale) / 2f;
        return new RectF(
                dx + box.left * scale,
                dy + box.top * scale,
                dx + box.right * scale,
                dy + box.bottom * scale);
    }

    // ---------------------------------------------------------------- 记录

    private void commit(String value) {
        if (value == null) {
            return;
        }
        value = value.trim();
        if (value.isEmpty()) {
            return;
        }
        appendLine(value);
        long now = SystemClock.elapsedRealtime();
        suppressed.put(dedupKey(value), now);
        lastSeen.put(dedupKey(value), now);
        pendingValue = null;

        String shown = value.length() > 22 ? value.substring(0, 22) + "…" : value;
        flashMessage = "已记录：" + shown;
        flashUntil = now + FLASH_MS;
        setHint(flashMessage, false);
        feedback();
    }

    /** 记录成功提示：按设置的提示方式出声/震动 */
    private void feedback() {
        if (feedbackMode == FB_NONE) {
            return;
        }
        boolean wantVibrate = (feedbackMode == FB_VIBRATE || feedbackMode == FB_SOUND_VIBRATE);
        boolean wantSound = (feedbackMode == FB_SOUND || feedbackMode == FB_SOUND_VIBRATE);

        if (wantVibrate && vibrator != null && vibrator.hasVibrator()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(45, 110));
                } else {
                    vibrator.vibrate(45);
                }
            } catch (Exception ignored) {
            }
        }
        if (wantSound && toneGenerator != null) {
            try {
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 130);
            } catch (Exception ignored) {
            }
        }
    }

    /** 重复条码提示：两声短震 + 与“记录成功”不同的提示音 */
    private void duplicateFeedback() {
        if (feedbackMode == FB_NONE) {
            return;
        }
        boolean wantVibrate = (feedbackMode == FB_VIBRATE || feedbackMode == FB_SOUND_VIBRATE);
        boolean wantSound = (feedbackMode == FB_SOUND || feedbackMode == FB_SOUND_VIBRATE);

        if (wantVibrate && vibrator != null && vibrator.hasVibrator()) {
            try {
                long[] pattern = new long[]{0, 40, 90, 40};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
                } else {
                    vibrator.vibrate(pattern, -1);
                }
            } catch (Exception ignored) {
            }
        }
        if (wantSound && toneGenerator != null) {
            try {
                toneGenerator.startTone(ToneGenerator.TONE_PROP_NACK, 200);
            } catch (Exception ignored) {
            }
        }
    }

    private void appendLine(String value) {
        String current = resultEdit.getText().toString();
        StringBuilder sb = new StringBuilder(current);
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        sb.append(value).append('\n');
        resultEdit.setText(sb.toString());
        resultEdit.setSelection(resultEdit.getText().length());
    }

    private void undoLast() {
        String s = resultEdit.getText().toString();
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        if (end == 0) {
            toast("还没有可撤回的记录");
            return;
        }
        int newline = s.lastIndexOf('\n', end - 1);
        String removed = s.substring(newline + 1, end);
        String kept = newline < 0 ? "" : s.substring(0, newline + 1);
        resultEdit.setText(kept);
        resultEdit.setSelection(resultEdit.getText().length());
        // 撤回后短时间内（且条码还在画面里时）不要马上又被记录回来
        suppressed.put(dedupKey(removed), SystemClock.elapsedRealtime());
        pendingValue = null;
        updateHint(0, false);
        toast("已撤回：" + (removed.length() > 22 ? removed.substring(0, 22) + "…" : removed));
    }

    /**
     * 去重用的归一化 key。
     * 同一个条码，ML Kit 有时报成 UPC-A（12 位），有时报成带前导 0 的 EAN-13（13 位），
     * 去掉前导 0 之后是同一个 key，可以避免列表里出现“一模一样的两条”。
     */
    private static String dedupKey(String value) {
        if (value == null) {
            return "";
        }
        String v = value.trim();
        int i = 0;
        while (i < v.length() - 1 && v.charAt(i) == '0') {
            i++;
        }
        return v.substring(i);
    }

    /** 清空全部记录（二次确认，避免误点丢数据） */
    private void confirmClearAll() {
        final int count = splitLines(resultEdit.getText().toString()).size();
        if (count == 0) {
            toast("还没有记录");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_title)
                .setMessage(getString(R.string.clear_message, count))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.clear_ok, (dialog, which) -> clearAll())
                .show();
    }

    private void clearAll() {
        resultEdit.setText("");
        pendingValue = null;
        flashMessage = null;
        long now = SystemClock.elapsedRealtime();
        // 画面里还在的条码先屏蔽住，避免刚清空马上又自动记回来
        for (String key : lastFrameKeys) {
            suppressed.put(key, now);
            lastSeen.put(key, now);
        }
        updateHint(0, false);
        toast("已清空");
    }

    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\r?\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private void updateCount(String text) {
        countText.setText("已记录 " + splitLines(text).size() + " 条");
    }

    private void updateHint(int count, boolean multi) {
        long now = SystemClock.elapsedRealtime();
        if (flashMessage != null && now < flashUntil) {
            setHint(flashMessage, false);
            return;
        }
        flashMessage = null;
        if (count == 0) {
            setHint(getString(R.string.hint_idle), false);
        } else if (multi) {
            setHint(getString(R.string.hint_multi), false);
        } else {
            setHint(getString(R.string.hint_single), false);
        }
    }

    // ---------------------------------------------------------------- 导出

    private void saveCsv() {
        List<String> lines = splitLines(resultEdit.getText().toString());
        if (lines.isEmpty()) {
            toast("还没有扫描记录");
            return;
        }
        String name = "barcodes_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + ".csv";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(intent, REQ_SAVE_CSV);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SAVE_CSV && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            writeCsv(data.getData());
        }
    }

    private void writeCsv(Uri uri) {
        List<String> lines = splitLines(resultEdit.getText().toString());
        OutputStream out = null;
        try {
            out = getContentResolver().openOutputStream(uri);
            if (out == null) {
                toast("保存失败：无法写入该位置");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append('\uFEFF'); // BOM，方便 Excel 打开中文
            for (String line : lines) {
                sb.append(csvCell(line)).append("\r\n");
            }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            toast("已保存 " + lines.size() + " 条记录到 CSV");
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String csvCell(String value) {
        boolean needQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needQuote) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private void copyToClipboard() {
        String text = resultEdit.getText().toString().trim();
        if (text.isEmpty()) {
            toast("还没有扫描记录");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("barcodes", text));
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            toast("已复制 " + splitLines(text).size() + " 条记录");
        }
    }

    // ---------------------------------------------------------------- 权限 & 生命周期

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                setHint(getString(R.string.hint_no_permission), true);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        analysisExecutor.shutdown();
        if (scanner != null) {
            scanner.close();
        }
        if (toneGenerator != null) {
            toneGenerator.release();
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
