package com.juhyeon.chesstip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TipService extends Service {
    static final String RESULT_CODE = "capture.resultCode";
    static final String RESULT_DATA = "capture.data";
    private static final String STOP = "com.juhyeon.chesstip.STOP";
    private static final String TAG = "ChessTip";
    private static final String CHANNEL = "chesstip_capture";
    static volatile boolean running;
    static volatile boolean tipOn;
    static volatile boolean autoPlaying;
    static volatile long frames;
    static volatile int frameWidth;
    static volatile int frameHeight;
    static volatile boolean frameHasContent;
    static volatile String lastError;
    static volatile String coachStatus = "대국 화면 연결 전";
    private volatile String coachDetail = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread imageThread;
    private Handler imageHandler;
    private WindowManager windows;
    private LinearLayout panel;
    private Button toggle;
    private Button autoButton;
    private TextView frameLabel;
    private ArrowView arrow;
    private WindowManager.LayoutParams panelParams;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private long lastFrameCheck;
    private volatile boolean releasing;
    private BoardVision vision;
    private GameTracker tracker;
    private volatile BoardVision.Observation observedBoard;
    private volatile String observedFen;
    private volatile boolean observedWhiteBottom;
    private volatile boolean reliable;
    private volatile long observedAt;
    private volatile boolean myTurn;
    private final ExecutorService engineWorker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean analyzing = new AtomicBoolean();
    private volatile StockfishEngine engine;
    private record Analysis(String fen, MoveTip.Tip tip) {}
    private volatile Analysis cached;
    private TextView reasonLabel;
    private volatile boolean capturedVisible = true;
    private volatile int probeX = -1, probeY = -1;
    private volatile boolean wholeScreenDetected;
    private final AutoPlay auto = new AutoPlay();
    private final PanelStatus panelStatus = new PanelStatus();
    private long statusWindowAt = -1;
    private volatile AutoPlay.Position autoPosition;
    private boolean observedTerminal;
    private boolean autoButtonActive;
    private String promotionHandledFen;
    private String inputWait = "";
    private long inputWaitAt = -1;
    private String visionIssue = "";
    private long lastVisionIssueAt;
    private long boardReads;
    private boolean panelInteracting;
    private final Runnable autoLoop = new Runnable() {
        @Override public void run() {
            if (!running || releasing) return;
            runAuto();
            showCurrentTip();
            main.postDelayed(this, auto.enabled() ? 100 : 250);
        }
    };

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() {
            Log.i(TAG, "CAPTURE_STOPPED_BY_SYSTEM");
            stopSelf();
        }

        @Override public void onCapturedContentResize(int width, int height) {
            if (imageHandler != null) imageHandler.post(() -> resizeCapture(width, height));
        }

        @Override public void onCapturedContentVisibilityChanged(boolean visible) {
            capturedVisible = visible;
            Log.i(TAG, "CAPTURE_VISIBILITY " + visible);
            if (!visible) {
                reliable = false;
                autoPosition = null;
                if (auto.enabled()) waitForInput("대국 화면을 기다리고 있어요.\n돌아오면 이어가요.", SystemClock.elapsedRealtime());
                coachStatus = "듀오링고 화면으로 돌아가 주세요";
                coachDetail = "";
                showCurrentTip();
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        windows = getSystemService(WindowManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "화면 연결 상태", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        if (!Settings.canDrawOverlays(this)) {
            fail("표시 권한이 필요해요. 앱에서 다시 허용해 주세요.", null);
            return START_NOT_STICKY;
        }
        Intent captureData = intent.getParcelableExtra(RESULT_DATA, Intent.class);
        if (captureData == null) {
            fail("화면 연결을 다시 시작해 주세요.", null);
            return START_NOT_STICKY;
        }
        try {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(
                    intent.getIntExtra(RESULT_CODE, 0), captureData);
            projection.registerCallback(projectionCallback, main);
            frames = 0;
            frameWidth = 0;
            frameHeight = 0;
            frameHasContent = false;
            tipOn = false;
            autoPlaying = false;
            lastError = null;
            coachStatus = "첫 화면 대기 중";
            coachDetail = "";
            vision = new BoardVision(getAssets().open("pieces.bin"));
            android.content.SharedPreferences saved = getSharedPreferences("game", MODE_PRIVATE);
            tracker = new GameTracker(saved.getString("fen", null), saved.getBoolean("whiteBottom", true));
            releasing = false;
            imageThread = new HandlerThread("ChessTip-Frames");
            imageThread.start();
            imageHandler = new Handler(imageThread.getLooper());
            Rect bounds = windows.getMaximumWindowMetrics().getBounds();
            reader = newReader(bounds.width(), bounds.height());
            display = projection.createVirtualDisplay("ChessTip-ConnectionTest", bounds.width(), bounds.height(),
                    getResources().getDisplayMetrics().densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, imageHandler);
            running = true;
            showPanel();
            main.postDelayed(autoLoop, 200);
            Log.i(TAG, "CAPTURE_STARTED");
        } catch (RuntimeException | IOException error) {
            fail("화면 연결에 실패했어요. 연결을 다시 시작해 주세요.", error);
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, TipService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_chess_tip)
                .setContentTitle("Chess Tip · 체스 코치")
                .setContentText("화면 수신 중 · Tip 버튼을 길게 누르면 종료")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "종료", stop).build())
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private ImageReader newReader(int width, int height) {
        ImageReader result = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        result.setOnImageAvailableListener(source -> {
            try (Image image = source.acquireLatestImage()) {
                if (image == null) return;
                long now = SystemClock.elapsedRealtime();
                if (now - lastFrameCheck < (autoPlaying ? 250 : 500)) return;
                lastFrameCheck = now;
                frameWidth = image.getWidth();
                frameHeight = image.getHeight();
                frameHasContent = hasVisiblePixels(image);
                frames++;
                if (frames == 1) Log.i(TAG, "FRAME_READY " + frameWidth + "x" + frameHeight + " content=" + frameHasContent);
                readBoard(image);
            } catch (IllegalStateException error) {
                if (!releasing) Log.w(TAG, "Frame reader closed while resizing", error);
            }
        }, imageHandler);
        return result;
    }

    private void readBoard(Image image) {
        if (!capturedVisible) return;
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer pixels = plane.getBuffer();
        BoardVision.Frame frame = new BoardVision.Frame() {
            @Override public int width() { return image.getWidth(); }
            @Override public int height() { return image.getHeight(); }
            @Override public int rgb(int x, int y) {
                int offset = y * plane.getRowStride() + x * plane.getPixelStride();
                return (pixels.get(offset) & 255) << 16 | (pixels.get(offset + 1) & 255) << 8 | pixels.get(offset + 2) & 255;
            }
        };
        // Only the selected Duolingo task may be captured. A full-display capture includes our
        // mint button, then our arrows, creating a recognition feedback loop. Fail with a stable
        // instruction instead of repeatedly hiding and repainting a move.
        if (tipOn && probeX >= 0 && probeX < frame.width() && probeY >= 0 && probeY < frame.height()) {
            int pixel = frame.rgb(probeX, probeY);
            if (Math.abs(((pixel >> 16) & 255) - 176) < 8
                    && Math.abs(((pixel >> 8) & 255) - 237) < 8 && Math.abs((pixel & 255) - 122) < 8) {
                wholeScreenDetected = true;
            }
        }
        if (wholeScreenDetected) {
            reliable = false;
            autoPosition = null;
            coachStatus = "전체 화면 공유가 선택되어 있어요";
            main.post(this::showCurrentTip);
            return;
        }
        long readStarted = SystemClock.elapsedRealtime();
        boardReads++;
        BoardVision.Observation observation = vision.read(frame);
        long readMillis = SystemClock.elapsedRealtime() - readStarted;
        if (observation != null) {
            android.view.WindowMetrics metrics = windows.getMaximumWindowMetrics();
            Rect screen = metrics.getBounds();
            android.graphics.Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            float scale = screen.width() / (float) image.getWidth();
            int captureHeight = Math.round(image.getHeight() * scale);
            int offset = Math.abs(captureHeight - (screen.height() - insets.top - insets.bottom)) <= 3
                    || Math.abs(captureHeight - (screen.height() - insets.top)) <= 3 ? insets.top : 0;
            observation = new BoardVision.Observation(Math.round(observation.left() * scale),
                    Math.round(observation.top() * scale) + offset, Math.round(observation.size() * scale),
                    observation.cells(), observation.error(), observation.selectedCell());
        }
        observedAt = SystemClock.elapsedRealtime();
        reliable = tracker.accept(observation);
        if (!reliable) {
            autoPosition = null;
            coachStatus = observation == null ? "판을 읽는 중" : "수순 확인 중";
            coachDetail = observation == null ? "판을 가리는 화면을\n닫아 주세요" : "새 대국 첫 화면에서\n연결해 주세요";
            String issue = observation == null ? vision.lastIssue : "판 안정화 또는 합법적인 수순 확인 중";
            long now = SystemClock.elapsedRealtime();
            if (!issue.equals(visionIssue) || now - lastVisionIssueAt > 5000) {
                Log.i(TAG, "BOARD_WAIT " + issue + " reads=" + boardReads + " readMs=" + readMillis);
                lastVisionIssueAt = now;
            }
            visionIssue = issue;
            main.post(this::showCurrentTip);
            return;
        }
        if (!visionIssue.isEmpty()) { Log.i(TAG, "BOARD_RECOVERED reads=" + boardReads + " readMs=" + readMillis); visionIssue = ""; }
        observedBoard = observation;
        observedWhiteBottom = tracker.whiteBottom();
        myTurn = tracker.myTurn();
        String fen = tracker.fen();
        if (!fen.equals(observedFen)) {
            observedFen = fen;
            observedTerminal = tracker.terminal();
            getSharedPreferences("game", MODE_PRIVATE).edit().putString("fen", fen).putBoolean("whiteBottom", observedWhiteBottom).apply();
            Log.i(TAG, "BOARD_CONFIRMED " + tracker.sideName() + " " + fen);
        }
        coachStatus = observedTerminal ? "대국 종료" : myTurn ? "내 차례" : "상대 차례";
        coachDetail = "";
        autoPosition = new AutoPlay.Position(fen, observedWhiteBottom, myTurn, observedAt,
                observation.left(), observation.top(), observation.size(), observation.selectedCell(), observedTerminal);
        main.post(this::showCurrentTip);
        if ((tipOn || autoPlaying) && myTurn && !observedTerminal) requestAnalysis();
    }

    private void requestAnalysis() {
        String fen = observedFen;
        Analysis previous = cached;
        if (!running || !reliable || !myTurn || fen == null || previous != null && fen.equals(previous.fen()) || !analyzing.compareAndSet(false, true)) return;
        engineWorker.execute(() -> {
            try {
                if (engine == null) engine = new StockfishEngine(new File(getApplicationInfo().nativeLibraryDir, "libstockfish.so"));
                if (releasing) return;
                StockfishEngine.Result result = engine.analyze(fen);
                MoveTip.Tip tip = MoveTip.explain(fen, result);
                if (tip != null && !releasing) {
                    cached = new Analysis(fen, tip);
                    Log.i(TAG, "ENGINE_BESTMOVE " + result.move() + " depth=" + result.depth());
                }
            } catch (IOException | InterruptedException | RuntimeException error) {
                if (!releasing) {
                    lastError = "엔진 연결 오류 · 연결을 다시 시작해 주세요";
                    Log.e(TAG, "ENGINE_ERROR", error);
                }
            } finally {
                analyzing.set(false);
                if (releasing && engine != null) engine.close();
                main.post(() -> { runAuto(); showCurrentTip(); });
            }
        });
    }

    private void showCurrentTip() {
        if (!running || releasing || frameLabel == null) return;
        long now = SystemClock.elapsedRealtime();
        Analysis analysis = cached;
        boolean fresh = now - observedAt < 1500;
        boolean ready = tipOn && reliable && fresh && myTurn && inputWait.isEmpty() && analysis != null && observedFen != null && observedFen.equals(analysis.fen());
        boolean waiting = auto.enabled() && (!inputWait.isEmpty() || auto.waitingForBoard() || auto.waitingForMove());
        AutoPlay.Position position = autoPosition;
        ChessTouchService touch = ChessTouchService.current();
        long windowAt = touch == null ? 0 : touch.windowChangedAt();
        if (statusWindowAt != windowAt || !capturedVisible || wholeScreenDetected || !inputWait.isEmpty()) {
            panelStatus.reset();
            statusWindowAt = windowAt;
        }
        boolean colorsKnown = position != null && capturedVisible && !wholeScreenDetected
                && now - position.seenAt() < 1500
                && (touch == null || position.seenAt() > windowAt);
        String players = colorsKnown ? (position.whiteBottom() ? "나: 백 · 상대: 흑\n" : "나: 흑 · 상대: 백\n") : "";
        String label = players + (auto.waitingForNextGame() ? "자동 대국 · 다음 대국 대기" : waiting ? "자동 대국 · 확인 대기" : coachStatus);
        String detail = wholeScreenDetected ? "연결 종료 후 ‘앱 하나 → 듀오링고’로 다시 연결해 주세요."
                : !inputWait.isEmpty() ? inputWait : auto.promotionPending() ? "승격 기물을 확인하고 있어요.\n직접 선택해도 이어갈 수 있어요."
                : auto.waitingForNextGame() ? "대국이 끝났어요.\n새 대국을 열면 이어가요."
                : auto.waitingForBoard() ? "판을 계속 다시 읽고 있어요.\n확인되면 자동으로 이어가요."
                : auto.waitingForMove() ? "이동 반영을 확인하고 있어요.\n미반영이면 다시 시도해요."
                : ready ? analysis.tip().title() + "\n" + analysis.tip().feature() + "\n" + analysis.tip().continuation()
                : lastError != null ? lastError : !auto.enabled() && !auto.note().isEmpty() ? auto.note()
                : !tipOn ? (auto.enabled() ? "자동 대국 진행 중" : "Tip을 켜면 추천 수가 보여요")
                : reliable && myTurn ? "추천 수 계산 중…" : coachDetail;
        PanelStatus.Text shown = panelStatus.update(now, new PanelStatus.Text(label, detail), waiting || !reliable || !fresh);
        if (!android.text.TextUtils.equals(frameLabel.getText(), shown.label())) {
            frameLabel.setText(shown.label());
            Log.i(TAG, "PANEL_STATUS " + shown.label().replace('\n', '|'));
        }
        if (!android.text.TextUtils.equals(reasonLabel.getText(), shown.detail())) reasonLabel.setText(shown.detail());
        reasonLabel.setVisibility(shown.detail().isEmpty() ? View.GONE : View.VISIBLE);
        if (ready) {
            ensureArrow();
            if (arrow != null) arrow.update(observedBoard, analysis.tip(), observedWhiteBottom);
        } else if (arrow != null) arrow.update(null, null, true);
        updateAutoButton();
    }

    private void runAuto() {
        if (!running || releasing || !auto.enabled() || panelInteracting) return;
        long now = SystemClock.elapsedRealtime();
        if (!capturedVisible || wholeScreenDetected) {
            waitForInput(wholeScreenDetected ? "‘앱 하나 → 듀오링고’로\n화면을 다시 연결해 주세요." : "대국 화면을 기다리고 있어요.\n돌아오면 이어가요.", now);
            return;
        }
        ChessTouchService touch = ChessTouchService.current();
        if (touch == null) {
            waitForInput(ChessTouchService.enabled(this) ? "터치 서비스를 다시 연결하고 있어요…" : "자동 대국 터치 권한을\n앱에서 켜 주세요.", now);
            return;
        }
        ChessTouchService.Foreground state = touch.foreground();
        if (state != ChessTouchService.Foreground.MATCH && !(auto.promotionPending() && state == ChessTouchService.Foreground.PROMOTION)) {
            waitForInput("체스 대국 화면을 기다려요.\n열리면 자동으로 이어가요.", now);
            return;
        }
        AutoPlay.Position position = autoPosition;
        if (position != null && position.seenAt() <= touch.windowChangedAt()) {
            waitForInput("새 대국 화면의 판을\n다시 확인하고 있어요.", now);
            return;
        }
        if (!inputWait.isEmpty()) Log.i(TAG, "AUTO_INPUT_READY " + state);
        inputWait = "";
        inputWaitAt = -1;
        if (auto.promotionPending() && !java.util.Objects.equals(promotionHandledFen, auto.pendingFen())
                && touch.choosePromotion(auto.promotionPiece())) promotionHandledFen = auto.pendingFen();
        Analysis analysis = cached;
        AutoPlay.Action action = auto.next(now, position,
                analysis == null ? null : analysis.fen(), analysis == null ? null : analysis.tip(), true);
        autoPlaying = auto.enabled();
        if (action == null) return;
        if (panelCovers(action.fromX(), action.fromY()) || panelCovers(action.toX(), action.toY())) {
            auto.defer(action, now);
            waitForInput("패널을 체스판 밖으로\n옮기면 이어가요.", now);
            return;
        }
        Log.i(TAG, "AUTO_TAP_MOVE " + action.move() + " selected=" + action.sourceSelected() + " attempt=" + action.attempt());
        Runnable destination = () -> {
            if (!auto.pending(action) || releasing) return;
            if (panelInteracting || !capturedVisible || wholeScreenDetected || panelCovers(action.toX(), action.toY())) {
                finishMove(action, false);
                return;
            }
            touch.tap(action.toX(), action.toY(), success -> finishMove(action, success));
        };
        if (action.sourceSelected()) destination.run();
        else touch.tap(action.fromX(), action.fromY(), success -> {
            if (!auto.pending(action)) return;
            if (!success) finishMove(action, false);
            else main.postDelayed(destination, 300);
        });
    }

    private boolean panelCovers(int x, int y) {
        int[] location = new int[2];
        panel.getLocationOnScreen(location);
        return new Rect(location[0], location[1], location[0] + panel.getWidth(), location[1] + panel.getHeight()).contains(x, y);
    }

    private void finishMove(AutoPlay.Action action, boolean success) {
        boolean pending = auto.pending(action);
        auto.complete(action, success, SystemClock.elapsedRealtime());
        autoPlaying = auto.enabled();
        if (success && pending) Log.i(TAG, "AUTO_MOVE_SENT " + action.move());
        else if (pending) Log.i(TAG, "AUTO_TOUCH_WAIT " + action.move());
        showCurrentTip();
    }

    private void waitForInput(String message, long now) {
        auto.waitForInput(now);
        if (inputWaitAt < 0) { inputWaitAt = now; Log.i(TAG, "AUTO_WAIT_INPUT " + message); }
        inputWait = message;
    }

    private void toggleAuto() {
        if (auto.enabled()) { pauseAuto("자동 대국을 멈췄어요 · 직접 둘 수 있어요"); return; }
        auto.start(SystemClock.elapsedRealtime());
        panelStatus.reset();
        autoPlaying = true;
        inputWait = "";
        inputWaitAt = -1;
        promotionHandledFen = null;
        requestAnalysis();
        Log.i(TAG, "AUTO_STARTED");
        runAuto();
        showCurrentTip();
    }

    private void pauseAuto(String message) {
        if (!auto.enabled()) return;
        auto.pause(message);
        panelStatus.reset();
        autoPlaying = false;
        inputWait = "";
        inputWaitAt = -1;
        Log.i(TAG, "AUTO_PAUSED " + message);
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
        showCurrentTip();
    }

    private void updateAutoButton() {
        if (autoButton == null) return;
        if (autoButtonActive != auto.enabled()) {
            autoButtonActive = auto.enabled();
            autoButton.setText(autoButtonActive ? "자동 대국 정지" : "자동 대국 시작");
            autoButton.setTextColor(Ui.TEXT);
            autoButton.setBackground(Ui.overlayButton(this, Ui.CAPTURE, autoButtonActive ? Ui.CAPTURE : Ui.LINE, autoButtonActive));
        }
    }

    private static boolean hasVisiblePixels(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer pixels = plane.getBuffer();
        int stride = plane.getRowStride();
        int step = plane.getPixelStride();
        for (int row = 1; row <= 8; row++) {
            for (int column = 1; column <= 8; column++) {
                int offset = (image.getHeight() * row / 9) * stride + (image.getWidth() * column / 9) * step;
                if (offset + 2 < pixels.limit()
                        && ((pixels.get(offset) & 255) > 20 || (pixels.get(offset + 1) & 255) > 20 || (pixels.get(offset + 2) & 255) > 20)) {
                    return true;
                }
            }
        }
        return false;
    }

    private synchronized void resizeCapture(int width, int height) {
        if (releasing || display == null || width <= 0 || height <= 0) return;
        if (reader != null && reader.getWidth() == width && reader.getHeight() == height) return;
        autoPosition = null;
        main.post(() -> { if (auto.enabled()) waitForInput("화면 크기가 바뀌어\n판을 다시 확인하고 있어요.", SystemClock.elapsedRealtime()); });
        ImageReader previous = reader;
        reader = newReader(width, height);
        display.resize(width, height, getResources().getDisplayMetrics().densityDpi);
        display.setSurface(reader.getSurface());
        if (previous != null) previous.close();
        Log.i(TAG, "CAPTURE_RESIZED " + width + "x" + height);
    }

    private void showPanel() {
        panel = Ui.column(this);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        panel.setBackground(Ui.background((Ui.CARD & 0xffffff) | 0x8c000000, 18, this));
        TextView header = Ui.text(this, "CHESS TIP", 10, Ui.MUTED, true);
        centerLabel(header);
        header.setMinHeight(Ui.dp(this, 16));
        header.setClickable(true);
        Ui.add(panel, header, 0);
        toggle = Ui.button(this, "TIP OFF", false);
        toggle.setGravity(Gravity.CENTER);
        toggle.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        toggle.setIncludeFontPadding(false);
        toggle.setMinHeight(Ui.dp(this, 44));
        toggle.setMinimumHeight(Ui.dp(this, 44));
        toggle.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        toggle.setTextSize(17);
        toggle.setTextColor(Ui.GREEN);
        toggle.setBackground(Ui.overlayButton(this, Ui.GREEN, Ui.GREEN, false));
        Ui.add(panel, toggle, 8);
        autoButton = Ui.button(this, "자동 대국 시작", false);
        centerLabel(autoButton);
        autoButton.setTextSize(13);
        autoButton.setMinHeight(Ui.dp(this, 40));
        autoButton.setMinimumHeight(Ui.dp(this, 40));
        autoButton.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        autoButton.setBackground(Ui.overlayButton(this, Ui.CAPTURE, Ui.LINE, false));
        autoButton.setOnClickListener(view -> toggleAuto());
        Ui.add(panel, autoButton, 8);
        frameLabel = Ui.text(this, "첫 화면 대기 중", 11, Ui.MUTED, false);
        centerLabel(frameLabel);
        frameLabel.setMinLines(2);
        Ui.add(panel, frameLabel, 8);
        reasonLabel = Ui.text(this, "Tip을 켜면 추천 수가 보여요", 11, Ui.MUTED, false);
        centerLabel(reasonLabel);
        Ui.add(panel, reasonLabel, 6);

        panelParams = new WindowManager.LayoutParams(Ui.dp(this, 164), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.LEFT;
        panelParams.x = Math.max(0, windows.getMaximumWindowMetrics().getBounds().width() - Ui.dp(this, 180));
        panelParams.y = Ui.dp(this, 12);
        windows.addView(panel, panelParams);
        toggle.setOnClickListener(view -> setTip(!tipOn));
        toggle.setOnLongClickListener(view -> {
            stopSelf();
            return true;
        });
        DragListener drag = new DragListener();
        for (View surface : new View[]{panel, header, toggle, autoButton, frameLabel, reasonLabel}) {
            surface.setClickable(true);
            surface.setOnTouchListener(drag);
        }
        panel.addOnLayoutChangeListener((view, a, b, c, d, e, f, g, h) -> updateProbe());
        panel.post(this::updateProbe);
        Log.i(TAG, "OVERLAY_VISIBLE");
    }

    private void centerLabel(TextView label) {
        label.setGravity(Gravity.CENTER);
        label.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        label.setIncludeFontPadding(false);
        label.setPadding(0, 0, 0, 0);
        label.setLineSpacing(Ui.dp(this, 2), 1f);
    }

    private final class DragListener implements android.view.View.OnTouchListener {
        private float startX, startY;
        private int originalX, originalY;
        private boolean moved;

        @Override public boolean onTouch(android.view.View view, MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                panelInteracting = true;
                startX = event.getRawX();
                startY = event.getRawY();
                originalX = panelParams.x;
                originalY = panelParams.y;
                moved = false;
                return false;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = event.getRawX() - startX;
                float dy = event.getRawY() - startY;
                if (Math.hypot(dx, dy) > ViewConfiguration.get(TipService.this).getScaledTouchSlop()) {
                    moved = true;
                    view.cancelLongPress();
                }
                if (moved) {
                    android.view.WindowMetrics metrics = windows.getMaximumWindowMetrics();
                    Rect bounds = metrics.getBounds();
                    android.graphics.Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                            android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                    panelParams.x = Math.max(0, Math.min(bounds.width() - panel.getWidth(), originalX + (int) dx));
                    panelParams.y = Math.max(0, Math.min(bounds.height() - insets.top - insets.bottom - panel.getHeight(), originalY + (int) dy));
                    windows.updateViewLayout(panel, panelParams);
                    panel.post(TipService.this::updateProbe);
                    return true;
                }
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                panelInteracting = false;
                if (moved) {
                    view.setPressed(false);
                    Log.i(TAG, "PANEL_MOVED x=" + panelParams.x + " y=" + panelParams.y);
                    return true;
                }
            }
            return false;
        }
    }

    private void setTip(boolean enabled) {
        panelStatus.reset();
        tipOn = enabled;
        toggle.setText(enabled ? "TIP ON" : "TIP OFF");
        toggle.setTextColor(enabled ? Ui.TEXT : Ui.GREEN);
        toggle.setBackground(Ui.overlayButton(this, Ui.GREEN, Ui.GREEN, enabled));
        if (enabled) requestAnalysis();
        if (!enabled && arrow != null) {
            if (arrow.isAttachedToWindow()) windows.removeView(arrow);
            arrow = null;
        }
        showCurrentTip();
        Log.i(TAG, "TIP_" + (enabled ? "ON" : "OFF"));
    }

    private void updateProbe() {
        if (toggle == null || !toggle.isAttachedToWindow()) return;
        int[] point = new int[2];
        toggle.getLocationOnScreen(point);
        // The solid border remains opaque while the panel and button fills are translucent.
        probeX = point[0] + Math.max(1, Ui.dp(this, .5f));
        probeY = point[1] + toggle.getHeight() / 2;
    }

    private void ensureArrow() {
        if (arrow == null) {
            arrow = new ArrowView(this);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.alpha = .7f;
            params.alpha = Math.min(params.alpha, getSystemService(InputManager.class).getMaximumObscuringOpacityForTouch());
            try {
                windows.addView(arrow, params);
            } catch (RuntimeException error) {
                fail("표시 권한을 다시 확인해 주세요.", error);
                return;
            }
        }
    }

    private void fail(String message, Throwable error) {
        lastError = message;
        Log.e(TAG, message, error);
        stopSelf();
    }

    @Override public synchronized void onDestroy() {
        releasing = true;
        auto.pause("");
        autoPlaying = false;
        running = false;
        tipOn = false;
        reliable = false;
        engineWorker.shutdownNow();
        if (engine != null) engine.close();
        if (arrow != null) {
            if (arrow.isAttachedToWindow()) windows.removeViewImmediate(arrow);
            arrow = null;
        }
        if (panel != null) {
            if (panel.isAttachedToWindow()) windows.removeViewImmediate(panel);
            panel = null;
        }
        if (projection != null) projection.unregisterCallback(projectionCallback);
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        if (imageThread != null) imageThread.quitSafely();
        main.removeCallbacksAndMessages(null);
        frameLabel = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        Log.i(TAG, "SERVICE_STOPPED");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
