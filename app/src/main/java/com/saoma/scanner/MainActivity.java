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
import android.media.SoundPool;
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
    private static final String KEY_PRIORITY = "priority";
    private static final String KEY_LAST_SIG = "last_sig";
    private static final String KEY_LAST_VALUE = "last_value";
    private static final String KEY_SCOPE = "scope";
    private static final String KEY_ROI = "roi";
    private static final String KEY_LINE = "line";

    /** 同屏多个条码时，自动判定的那个要稳定这么久才记录（给人工干预留时间） */
    private static final long MULTI_AUTO_COMMIT_MS = 500L;
    /** 智能模式兜底：与上次记录的条码至少要有这么多位相同前缀才敢自动选 */
    private static final int MIN_COMMON_PREFIX = 4;

    /** 同屏多码时的优先策略 */
    private static final int PRIORITY_SMART = 0;
    private static final int PRIORITY_EAN13 = 1;
    private static final int PRIORITY_CODE128 = 2;
    private static final int PRIORITY_MANUAL = 3;

    /** 记录成功时的提示方式 */
    private static final int FB_NONE = 0;
    private static final int FB_SOUND = 1;
    private static final int FB_SOUND_VIBRATE = 2;
    private static final int FB_VIBRATE = 3;

    /** 单个条码：稳定这么久就自动记录（毫秒） */
    private static final long AUTO_COMMIT_MS = 300L;
    /**
     * 允许的"漏检间隙"：手抖/对焦时 ML Kit 会跳帧（这帧认出、下帧没认出），
     * 只要在这个时间内又看到同一个条码，就认为没中断，计时不清零。
     */
    private static final long DETECTION_GAP_MS = 600L;
    /** 至少被看到几次才记录，防止单帧误读 */
    private static final int MIN_SIGHTINGS = 2;
    /**
     * 已记录的条码离开画面多久后允许再次记录。
     * 针对"一整批同款商品"：把手机从上一箱移开、这期间条码离开画面，0.9 秒后即可记下一箱。
     * 注意必须大于 DETECTION_GAP_MS，否则同一次对着不动会被记两遍。
     */
    private static final long SUPPRESS_RELEASE_MS = 900L;
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
    private TextView priorityButton;
    private TextView scopeButton;
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
    /** 同屏多码时的优先策略 */
    private int priorityMode = PRIORITY_SMART;
    /** 上一次记录/点选的条码指纹（"格式:长度"），智能模式据此自动选码 */
    private String lastPickSignature = "";
    /** 上一次记录的条码内容，用于前缀相似度兜底 */
    private String lastCommittedValue = "";
    /** 多码模式下"即将自动记录"的候选，以及它开始稳定的时间 */
    private String autoCandidateKey;
    private long autoSince;
    private long autoLastSeen;
    private int autoSeen;
    /** 识别范围限定：全部 / 取景框 / 扫描线 */
    private int scopeMode = OverlayView.SCOPE_OFF;
    private final RectF scopeRoi = new RectF(0.05f, 0.42f, 0.95f, 0.58f);
    private float scopeLineY = 0.5f;
    private SharedPreferences prefs;
    private Vibrator vibrator;
    private ToneGenerator toneGenerator;
    private SoundPool soundPool;
    private int soundOk;
    private int soundDup;

    /** 每个条码值最近一次出现在画面里的时间 */
    private final Map<String, Long> lastSeen = new HashMap<>();
    /** 已经记录过的条码值，短暂屏蔽避免重复记录 */
    private final Map<String, Long> suppressed = new HashMap<>();
    /** 当前这一帧画面里出现的条码（归一化 key），用于清除后不马上又被记回来 */
    private final Set<String> lastFrameKeys = new HashSet<>();

    private String pendingValue;
    private String pendingKey;
    private long pendingSince;
    private long pendingLastSeen;
    private int pendingSeen;
    private String flashMessage;
    private long flashUntil;
    private long lastDuplicateSignalAt;

    private static class RawDetection {
        final String value;
        final Rect box;
        final int format;

        RawDetection(String value, Rect box, int format) {
            this.value = value;
            this.box = box;
            this.format = format;
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
        priorityButton = findViewById(R.id.priorityButton);
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        dedupEnabled = prefs.getBoolean(KEY_DEDUP, true);
        updateDedupButton();
        feedbackMode = prefs.getInt(KEY_FEEDBACK, FB_SOUND_VIBRATE);
        updateFeedbackButton();
        priorityMode = prefs.getInt(KEY_PRIORITY, PRIORITY_SMART);
        lastPickSignature = prefs.getString(KEY_LAST_SIG, "");
        lastCommittedValue = prefs.getString(KEY_LAST_VALUE, "");
        updatePriorityButton();

        scopeButton = findViewById(R.id.scopeButton);
        scopeMode = prefs.getInt(KEY_SCOPE, OverlayView.SCOPE_OFF);
        loadScopeGeometry();
        overlay.setRoiChangeListener(() -> {
            scopeRoi.set(overlay.getRoi());
            scopeLineY = overlay.getLineY();
            saveScopeGeometry();
            resetScanState();
        });
        applyScopeToOverlay();
        updateScopeButton();
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        try {
            soundPool = new SoundPool.Builder().setMaxStreams(2).build();
            soundOk = soundPool.load(this, R.raw.beep_ok, 1);
            soundDup = soundPool.load(this, R.raw.beep_dup, 1);
        } catch (Exception ignored) {
        }
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
        priorityButton.setOnClickListener(v -> {
            priorityMode = (priorityMode + 1) % 4;
            prefs.edit().putInt(KEY_PRIORITY, priorityMode).apply();
            updatePriorityButton();
            autoCandidateKey = null;
            pendingValue = null;
            switch (priorityMode) {
                case PRIORITY_EAN13:
                    toast("同屏多码时：优先自动记商品条码（EAN-13/UPC）");
                    break;
                case PRIORITY_CODE128:
                    toast("同屏多码时：优先自动记 Code128");
                    break;
                case PRIORITY_MANUAL:
                    toast("同屏多码时：全部手动点选");
                    break;
                default:
                    toast("同屏多码时：智能（记住你上次点的那种码）");
                    break;
            }
        });
        scopeButton.setOnClickListener(v -> {
            scopeMode = (scopeMode + 1) % 3;
            prefs.edit().putInt(KEY_SCOPE, scopeMode).apply();
            applyScopeToOverlay();
            updateScopeButton();
            resetScanState();
            switch (scopeMode) {
                case OverlayView.SCOPE_BOX:
                    toast("只识别取景框里的条码（拖动移动、拉四角缩放）");
                    break;
                case OverlayView.SCOPE_LINE:
                    toast("只识别压到红线上的条码（上下拖动可调）");
                    break;
                default:
                    toast("不限范围：画面里所有条码都会识别");
                    break;
            }
        });
        scopeButton.setOnLongClickListener(v -> {
            overlay.resetScope();
            scopeRoi.set(overlay.getRoi());
            scopeLineY = overlay.getLineY();
            saveScopeGeometry();
            resetScanState();
            toast("范围已恢复默认位置");
            return true;
        });

        overlay.setTapListener(detection -> {
            if (detection != null && detection.value != null) {
                // 手点以这次为准，顺便重新学习"以后自动记哪种"
                autoCandidateKey = null;
                commit(detection.value.trim(), detection.format);
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
                            list.add(new RawDetection(value.trim(), barcode.getBoundingBox(),
                                    barcode.getFormat()));
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
        boolean sawOutside = false;
        List<OverlayView.Detection> visible = new ArrayList<>();
        List<OverlayView.Detection> ignored = new ArrayList<>();
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
            OverlayView.Detection det = new OverlayView.Detection(d.value, box, d.format);
            if (!isInScope(box, viewWidth, viewHeight)) {
                // 取景框/扫描线之外的：画灰框提示，但绝不记录
                det.ignored = true;
                ignored.add(det);
                sawOutside = true;
                continue;
            }
            visible.add(det);
        }

        boolean multi = visible.size() > 1;
        String autoValue = null;

        if (multi) {
            // 同屏多个条码：按"优先"策略挑一个当作将自动记录的对象
            pendingKey = null;
            pendingValue = null;
            OverlayView.Detection autoPick = pickAutoCandidate(visible);
            if (autoPick != null) {
                autoPick.auto = true;
                autoValue = autoPick.value;
                String key = dedupKey(autoPick.value);
                // 容忍漏检：只要间隔不超过 DETECTION_GAP_MS，就认为还是"同一个一直在看"
                if (key.equals(autoCandidateKey) && now - autoLastSeen <= DETECTION_GAP_MS) {
                    autoLastSeen = now;
                    autoSeen++;
                } else {
                    autoCandidateKey = key;
                    autoSince = now;
                    autoLastSeen = now;
                    autoSeen = 1;
                }
                if (autoSeen >= MIN_SIGHTINGS && now - autoSince >= MULTI_AUTO_COMMIT_MS) {
                    commit(autoPick.value, autoPick.format);
                    autoCandidateKey = null;
                    autoValue = null;
                }
            } else {
                autoCandidateKey = null;
            }
        } else {
            autoCandidateKey = null;
            if (visible.size() == 1) {
                OverlayView.Detection only = visible.get(0);
                String key = dedupKey(only.value);
                // 同样容忍漏检，避免"绿框一闪一闪但永远攒不满时间"
                if (key.equals(pendingKey) && now - pendingLastSeen <= DETECTION_GAP_MS) {
                    pendingLastSeen = now;
                    pendingSeen++;
                } else {
                    pendingKey = key;
                    pendingValue = only.value;
                    pendingSince = now;
                    pendingLastSeen = now;
                    pendingSeen = 1;
                }
                if (pendingSeen >= MIN_SIGHTINGS && now - pendingSince >= AUTO_COMMIT_MS) {
                    commit(only.value, only.format);
                }
            } else {
                pendingKey = null;
                pendingValue = null;
            }
        }

        List<OverlayView.Detection> all = new ArrayList<>(visible);
        all.addAll(ignored);
        overlay.setDetections(all, multi);

        if (visible.isEmpty() && sawDuplicate) {
            flashMessage = null;
            setHint(getString(R.string.hint_duplicate), true);
            // 重复的条码也给提示音/震动（和“记录成功”的声音、震动模式不同），避免用户反复扫
            if (now - lastDuplicateSignalAt > DUPLICATE_SIGNAL_GAP_MS) {
                lastDuplicateSignalAt = now;
                duplicateFeedback();
            }
        } else if (visible.isEmpty() && sawOutside && (flashMessage == null || now >= flashUntil)) {
            flashMessage = null;
            setHint(scopeMode == OverlayView.SCOPE_LINE
                    ? "只记录压到红线上的条码，把条码对准红线"
                    : "只记录取景框里的条码，把条码放进框里", false);
        } else if (autoValue != null) {
            flashMessage = null;
            String shown = autoValue.length() > 16 ? autoValue.substring(0, 16) + "…" : autoValue;
            setHint("将自动记录：" + shown + "（点别的框可改）", false);
        } else {
            updateHint(visible.size(), multi);
        }
    }

    /**
     * 同屏多个条码时，挑出"应该自动记录"的那个；挑不出就返回 null（等人工点）。
     * 智能模式：优先挑和上次记录同一种（格式+长度）的码；
     * 分不出来时用"和上次记录的值前缀最像"兜底；再分不出就不猜。
     */
    private OverlayView.Detection pickAutoCandidate(List<OverlayView.Detection> visible) {
        if (priorityMode == PRIORITY_MANUAL || visible.isEmpty()) {
            return null;
        }
        List<OverlayView.Detection> matches = new ArrayList<>();
        for (OverlayView.Detection d : visible) {
            if (matchesPriority(d)) {
                matches.add(d);
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.isEmpty() && priorityMode == PRIORITY_SMART) {
            return bestByPrefix(visible);
        }
        if (matches.size() > 1) {
            return bestByPrefix(matches);
        }
        return null;
    }

    private boolean matchesPriority(OverlayView.Detection d) {
        switch (priorityMode) {
            case PRIORITY_EAN13:
                return d.format == Barcode.FORMAT_EAN_13
                        || d.format == Barcode.FORMAT_EAN_8
                        || d.format == Barcode.FORMAT_UPC_A
                        || d.format == Barcode.FORMAT_UPC_E;
            case PRIORITY_CODE128:
                return d.format == Barcode.FORMAT_CODE_128;
            default:
                return lastPickSignature != null
                        && !lastPickSignature.isEmpty()
                        && signatureOf(d.value, d.format).equals(lastPickSignature);
        }
    }

    /** 在候选里挑"和上次记录的值前缀最像"的那个；并列或太不像就返回 null */
    private OverlayView.Detection bestByPrefix(List<OverlayView.Detection> candidates) {
        if (lastCommittedValue == null || lastCommittedValue.isEmpty()) {
            return null;
        }
        int bestLen = MIN_COMMON_PREFIX - 1;
        OverlayView.Detection best = null;
        boolean tie = false;
        for (OverlayView.Detection d : candidates) {
            int len = commonPrefixLen(d.value, lastCommittedValue);
            if (len > bestLen) {
                bestLen = len;
                best = d;
                tie = false;
            } else if (len == bestLen && best != null) {
                tie = true;
            }
        }
        return tie ? null : best;
    }

    /** 条码"指纹"：编码格式 + 长度（同款箱子上的不同条码长度不同，据此区分） */
    private static String signatureOf(String value, int format) {
        return format + ":" + (value == null ? 0 : value.length());
    }

    private static int commonPrefixLen(String a, String b) {
        if (a == null || b == null) {
            return 0;
        }
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
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

    // ---------------------------------------------------------------- 识别范围

    private void loadScopeGeometry() {
        String s = prefs.getString(KEY_ROI, "");
        if (s != null && !s.isEmpty()) {
            String[] parts = s.split(",");
            if (parts.length == 4) {
                try {
                    scopeRoi.set(Float.parseFloat(parts[0]), Float.parseFloat(parts[1]),
                            Float.parseFloat(parts[2]), Float.parseFloat(parts[3]));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        scopeLineY = prefs.getFloat(KEY_LINE, 0.5f);
    }

    private void saveScopeGeometry() {
        prefs.edit()
                .putString(KEY_ROI, scopeRoi.left + "," + scopeRoi.top + "," + scopeRoi.right + "," + scopeRoi.bottom)
                .putFloat(KEY_LINE, scopeLineY)
                .apply();
    }

    private void applyScopeToOverlay() {
        overlay.setRoi(scopeRoi);
        overlay.setLineY(scopeLineY);
        overlay.setScopeMode(scopeMode);
    }

    /** 改了取景框/扫描线之后，清掉临时的判定状态，避免刚挪完就误记一条 */
    private void resetScanState() {
        pendingValue = null;
        pendingKey = null;
        pendingSeen = 0;
        autoCandidateKey = null;
        autoSeen = 0;
        suppressed.clear();
        overlay.setDetections(new ArrayList<>(), false);
    }

    private void updateScopeButton() {
        switch (scopeMode) {
            case OverlayView.SCOPE_BOX:
                scopeButton.setText(R.string.scope_box);
                scopeButton.setTextColor(0xFF00E5FF);
                break;
            case OverlayView.SCOPE_LINE:
                scopeButton.setText(R.string.scope_line);
                scopeButton.setTextColor(0xFFFF5252);
                break;
            default:
                scopeButton.setText(R.string.scope_off);
                scopeButton.setTextColor(0xFFFFFFFF);
                break;
        }
    }

    /** 条码是否落在限定的识别范围内 */
    private boolean isInScope(RectF box, int viewWidth, int viewHeight) {
        if (scopeMode == OverlayView.SCOPE_OFF || box == null) {
            return true;
        }
        if (scopeMode == OverlayView.SCOPE_BOX) {
            RectF r = new RectF(scopeRoi.left * viewWidth, scopeRoi.top * viewHeight,
                    scopeRoi.right * viewWidth, scopeRoi.bottom * viewHeight);
            return r.contains(box.centerX(), box.centerY());
        }
        // 扫描线：条码的竖直范围要压到这条线
        float y = scopeLineY * viewHeight;
        return box.top <= y && box.bottom >= y;
    }

    private void updatePriorityButton() {
        switch (priorityMode) {
            case PRIORITY_EAN13:
                priorityButton.setText(R.string.priority_ean13);
                priorityButton.setTextColor(0xFF00E5FF);
                break;
            case PRIORITY_CODE128:
                priorityButton.setText(R.string.priority_code128);
                priorityButton.setTextColor(0xFF00E5FF);
                break;
            case PRIORITY_MANUAL:
                priorityButton.setText(R.string.priority_manual);
                priorityButton.setTextColor(0xFFFFD54F);
                break;
            default:
                priorityButton.setText(R.string.priority_smart);
                priorityButton.setTextColor(0xFFFFFFFF);
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

    private void commit(String value, int format) {
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
        pendingKey = null;
        autoCandidateKey = null;

        // 记住这次记的是"哪种码"，智能模式下次同屏多码时优先自动选它
        lastPickSignature = signatureOf(value, format);
        lastCommittedValue = value;
        if (prefs != null) {
            prefs.edit()
                    .putString(KEY_LAST_SIG, lastPickSignature)
                    .putString(KEY_LAST_VALUE, lastCommittedValue)
                    .apply();
        }

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
        if (wantSound) {
            playSound(soundOk, ToneGenerator.TONE_PROP_BEEP);
        }
    }

    /** 播放合成好的提示音（WAV），还没加载好时退回系统的短提示音 */
    private void playSound(int soundId, int fallbackTone) {
        boolean played = false;
        if (soundPool != null && soundId != 0) {
            try {
                played = soundPool.play(soundId, 1f, 1f, 1, 0, 1f) != 0;
            } catch (Exception ignored) {
            }
        }
        if (!played && toneGenerator != null) {
            try {
                toneGenerator.startTone(fallbackTone, 130);
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
        if (wantSound) {
            playSound(soundDup, ToneGenerator.TONE_PROP_NACK);
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
        if (soundPool != null) {
            soundPool.release();
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
