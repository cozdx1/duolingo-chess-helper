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

public final class TipService extends Service {
    static final String RESULT_CODE = "capture.resultCode";
    static final String RESULT_DATA = "capture.data";
    private static final String STOP = "com.juhyeon.chesstip.STOP";
    private static final String TAG = "ChessTip";
    private static final String CHANNEL = "chesstip_capture";
    private static final String OSCAR_GAME = "oscar_game";
    static volatile boolean running;
    private volatile boolean tipOn;
    static volatile long frames;
    static volatile int frameWidth;
    static volatile int frameHeight;
    static volatile boolean frameHasContent;
    static volatile String lastError;
    static volatile String coachStatus = "대국 화면 연결 전";

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread imageThread;
    private Handler imageHandler;
    private WindowManager windows;
    private LinearLayout panel;
    private Button toggle;
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
    private String savedFen;
    private boolean savedWhiteBottom;
    // Each uninterrupted capture of a verified Oscar window gets its own identity.
    private record OscarCapture(ChessScreens.ActivityWindow window) {}
    private record BoardRead(OscarCapture capture, BoardVision.Observation board, String fen,
                             boolean whiteBottom, boolean myTurn, boolean terminal, long seenAt) {}
    private volatile OscarCapture oscarCapture;
    private OscarCapture trackerCapture;
    private BoardRead boardRead;
    private ChessScreenService.Screen screen = new ChessScreenService.Screen(ChessScreens.Foreground.UNKNOWN, null);
    private final ExecutorService engineWorker = Executors.newSingleThreadExecutor();
    private boolean analyzing;
    private volatile StockfishEngine engine;
    private record Analysis(String fen, MoveTip.Tip tip) {}
    private Analysis cached;
    private TextView reasonLabel;
    private boolean capturedVisible = true;
    private volatile int probeX = -1, probeY = -1;
    private volatile boolean wholeScreenDetected;
    private final PanelStatus panelStatus = new PanelStatus();
    private String visionIssue = "";
    private long lastVisionIssueAt;
    private long boardReads;
    private final Runnable tipLoop = new Runnable() {
        @Override public void run() {
            if (!running || releasing) return;
            refreshScreen();
            showCurrentTip();
            main.postDelayed(this, 250);
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
            screenChanged();
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
            lastError = null;
            coachStatus = "첫 화면 대기 중";
            vision = new BoardVision(getAssets().open("pieces.bin"));
            android.content.SharedPreferences saved = getSharedPreferences(OSCAR_GAME, MODE_PRIVATE);
            savedFen = saved.getString("fen", null);
            savedWhiteBottom = saved.getBoolean("whiteBottom", true);
            tracker = new GameTracker(savedFen, savedWhiteBottom);
            releasing = false;
            imageThread = new HandlerThread("ChessTip-Frames");
            imageThread.start();
            imageHandler = new Handler(imageThread.getLooper());
            Rect bounds = windows.getMaximumWindowMetrics().getBounds();
            reader = newReader(bounds.width(), bounds.height());
            display = projection.createVirtualDisplay("ChessTip-BoardCapture", bounds.width(), bounds.height(),
                    getResources().getDisplayMetrics().densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, imageHandler);
            running = true;
            showPanel();
            ChessScreenService.setWindowListener(this::screenChanged);
            screenChanged();
            main.postDelayed(tipLoop, 200);
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
                .setContentText("오스카 봇전 전용 · 패널 제목을 길게 누르면 종료")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "종료", stop).build())
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private ImageReader newReader(int width, int height) {
        ImageReader result = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        result.setOnImageAvailableListener(source -> {
            OscarCapture capture = oscarCapture;
            try (Image image = source.acquireLatestImage()) {
                if (image == null) return;
                long now = SystemClock.elapsedRealtime();
                if (now - lastFrameCheck < 500) return;
                lastFrameCheck = now;
                frameWidth = image.getWidth();
                frameHeight = image.getHeight();
                frameHasContent = hasVisiblePixels(image);
                frames++;
                if (frames == 1) Log.i(TAG, "FRAME_READY " + frameWidth + "x" + frameHeight + " content=" + frameHasContent);
                if (capture != null) readBoard(image, capture);
            } catch (IllegalStateException error) {
                if (!releasing) Log.w(TAG, "Frame reader closed while resizing", error);
            }
        }, imageHandler);
        return result;
    }

    private void readBoard(Image image, OscarCapture capture) {
        if (oscarCapture != capture || wholeScreenDetected) return;
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
            main.post(this::screenChanged);
            return;
        }
        if (trackerCapture != capture) {
            if (trackerCapture != null && trackerCapture.window() != capture.window()) tracker = new GameTracker();
            tracker.accept(null);
            trackerCapture = capture;
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
        if (oscarCapture != capture) return;
        if (!tracker.accept(observation)) {
            String issue = observation == null ? vision.lastIssue : "판 안정화 또는 합법적인 수순 확인 중";
            long now = SystemClock.elapsedRealtime();
            if (!issue.equals(visionIssue) || now - lastVisionIssueAt > 5000) {
                Log.i(TAG, "BOARD_WAIT " + issue + " reads=" + boardReads + " readMs=" + readMillis);
                lastVisionIssueAt = now;
            }
            visionIssue = issue;
            BoardRead read = new BoardRead(capture, observation, null, false, false, false, readStarted);
            main.post(() -> acceptRead(read));
            return;
        }
        if (!visionIssue.isEmpty()) { Log.i(TAG, "BOARD_RECOVERED reads=" + boardReads + " readMs=" + readMillis); visionIssue = ""; }
        BoardRead read = new BoardRead(capture, observation, tracker.fen(),
                tracker.whiteBottom(), tracker.myTurn(), tracker.terminal(), readStarted);
        main.post(() -> acceptRead(read));
    }

    private void acceptRead(BoardRead read) {
        if (!running || releasing) return;
        refreshScreen();
        if (oscarCapture != read.capture()) {
            showCurrentTip();
            return;
        }
        boardRead = read;
        if (read.fen() != null && (!read.fen().equals(savedFen) || read.whiteBottom() != savedWhiteBottom)) {
            savedFen = read.fen();
            savedWhiteBottom = read.whiteBottom();
            getSharedPreferences(OSCAR_GAME, MODE_PRIVATE).edit().putString("fen", savedFen).putBoolean("whiteBottom", savedWhiteBottom).apply();
            Log.i(TAG, "BOARD_CONFIRMED " + (savedWhiteBottom ? "백 " : "흑 ") + savedFen);
        }
        requestAnalysis();
        showCurrentTip();
    }

    private void requestAnalysis() {
        BoardRead read = boardRead;
        if (!running || releasing || analyzing || !tipOn || read == null || read.capture() != oscarCapture) return;
        if (read.fen() == null || !read.myTurn() || read.terminal()
                || SystemClock.elapsedRealtime() - read.seenAt() >= 1500) return;
        String fen = read.fen();
        if (cached != null && fen.equals(cached.fen())) return;
        OscarCapture capture = read.capture();
        analyzing = true;
        engineWorker.execute(() -> {
            try {
                if (releasing || oscarCapture != capture) return;
                if (engine == null) engine = new StockfishEngine(new File(getApplicationInfo().nativeLibraryDir, "libstockfish.so"));
                if (releasing || oscarCapture != capture) return;
                StockfishEngine.Result result = engine.analyze(fen);
                MoveTip.Tip tip = MoveTip.explain(fen, result);
                main.post(() -> finishAnalysis(capture, fen, tip, result, null));
            } catch (IOException | InterruptedException | RuntimeException error) {
                main.post(() -> finishAnalysis(capture, fen, null, null, error));
            } finally {
                if (releasing && engine != null) engine.close();
                main.post(() -> analyzing = false);
            }
        });
    }

    private void finishAnalysis(OscarCapture capture, String fen, MoveTip.Tip tip, StockfishEngine.Result result, Exception error) {
        if (!running || releasing) return;
        refreshScreen();
        if (oscarCapture == capture) {
            if (error != null) {
                lastError = "엔진 연결 오류 · 연결을 다시 시작해 주세요";
                Log.e(TAG, "ENGINE_ERROR", error);
            } else if (tip != null) {
                cached = new Analysis(fen, tip);
                Log.i(TAG, "ENGINE_BESTMOVE " + result.move() + " depth=" + result.depth());
            }
        }
        showCurrentTip();
    }

    private void screenChanged() {
        if (!running || releasing) return;
        refreshScreen();
        showCurrentTip();
    }

    private void refreshScreen() {
        ChessScreenService detector = ChessScreenService.current();
        ChessScreens.Foreground previous = screen.kind();
        screen = detector == null ? new ChessScreenService.Screen(ChessScreens.Foreground.UNKNOWN, null) : detector.screen();
        boolean blocked = screen.kind() == ChessScreens.Foreground.PLAYER_MATCH;
        if (blocked) {
            if (tipOn) updateTipButton(false);
            if (previous != screen.kind()) Log.i(TAG, "PLAYER_MATCH_BLOCKED");
        }
        boolean verified = screen.kind() == ChessScreens.Foreground.OSCAR_MATCH && capturedVisible && !wholeScreenDetected;
        changeCapture(verified ? screen.activity() : null);
        toggle.setVisibility(blocked ? View.GONE : View.VISIBLE);
    }

    private void changeCapture(ChessScreens.ActivityWindow window) {
        OscarCapture previous = oscarCapture;
        if (window == (previous == null ? null : previous.window())) return;
        oscarCapture = window == null ? null : new OscarCapture(window);
        boardRead = null;
        cached = null;
        panelStatus.reset();
        if (window != null) Log.i(TAG, "OSCAR_WINDOW_CONFIRMED id=" + window.id());
    }

    private PanelStatus.Text waitingForOscar() {
        if (screen.kind() == ChessScreens.Foreground.PLAYER_MATCH) {
            return new PanelStatus.Text("오스카 봇전 전용", "듀오링고에서\n‘오스카와 대결하기’를 열어 주세요.");
        }
        if (ChessScreenService.current() == null) {
            return new PanelStatus.Text("대국 확인 권한 필요", "Chess Tip에서 접근성 권한을\n켜 주세요.");
        }
        if (wholeScreenDetected) {
            return new PanelStatus.Text("전체 화면 공유가 선택되어 있어요", "연결 종료 후 ‘앱 하나 → 듀오링고’로 다시 연결해 주세요.");
        }
        if (!capturedVisible) {
            return new PanelStatus.Text("듀오링고 화면을 기다려요", "돌아오면 판을 다시 확인해요.");
        }
        return new PanelStatus.Text("오스카 봇전 대기", "듀오링고에서\n‘오스카와 대결하기’를 열어 주세요.");
    }

    private void showCurrentTip() {
        if (!running || releasing || frameLabel == null) return;
        if (oscarCapture == null) {
            PanelStatus.Text text = waitingForOscar();
            coachStatus = text.label();
            showPanelText(text);
            if (arrow != null) arrow.update(null, null, true);
            return;
        }

        long now = SystemClock.elapsedRealtime();
        Analysis analysis = cached;
        BoardRead read = boardRead;
        boolean confirmed = read != null && read.fen() != null;
        boolean fresh = confirmed && now - read.seenAt() < 1500;
        boolean ready = tipOn && fresh && read.myTurn() && !read.terminal()
                && analysis != null && read.fen().equals(analysis.fen());
        boolean boardVisible = read != null && read.board() != null;
        coachStatus = !confirmed ? (boardVisible ? "수순 확인 중" : "판을 읽는 중")
                : read.terminal() ? "대국 종료" : read.myTurn() ? "내 차례" : "상대 차례";
        String players = fresh ? (read.whiteBottom() ? "나: 백 · 상대: 흑\n" : "나: 흑 · 상대: 백\n") : "";
        String detail = ready ? analysis.tip().title() + "\n" + analysis.tip().feature() + "\n" + analysis.tip().continuation()
                : !tipOn ? "Tip을 켜면 추천 수가 보여요"
                : lastError != null ? lastError
                : !confirmed ? (boardVisible ? "새 대국 첫 화면에서\n연결해 주세요" : "판을 가리는 화면을\n닫아 주세요")
                : fresh && read.myTurn() && !read.terminal() ? "추천 수 계산 중…" : "";
        showPanelText(panelStatus.update(now, new PanelStatus.Text(players + coachStatus, detail), !fresh));
        if (ready) {
            ensureArrow();
            if (arrow != null) arrow.update(read.board(), analysis.tip(), read.whiteBottom());
        } else if (arrow != null) arrow.update(null, null, true);
    }

    private void showPanelText(PanelStatus.Text shown) {
        if (!android.text.TextUtils.equals(frameLabel.getText(), shown.label())) {
            frameLabel.setText(shown.label());
            Log.i(TAG, "PANEL_STATUS " + shown.label().replace('\n', '|'));
        }
        if (!android.text.TextUtils.equals(reasonLabel.getText(), shown.detail())) reasonLabel.setText(shown.detail());
        reasonLabel.setVisibility(shown.detail().isEmpty() ? View.GONE : View.VISIBLE);
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
        main.post(() -> {
            if (!running || releasing) return;
            changeCapture(null);
            screenChanged();
        });
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
        header.setOnLongClickListener(view -> {
            stopSelf();
            return true;
        });
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
        for (View surface : new View[]{panel, header, toggle, frameLabel, reasonLabel}) {
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
        refreshScreen();
        if (enabled && oscarCapture == null) { showCurrentTip(); return; }
        panelStatus.reset();
        updateTipButton(enabled);
        if (enabled) requestAnalysis();
        showCurrentTip();
        Log.i(TAG, "TIP_" + (enabled ? "ON" : "OFF"));
    }

    private void updateTipButton(boolean enabled) {
        tipOn = enabled;
        toggle.setText(enabled ? "TIP ON" : "TIP OFF");
        toggle.setTextColor(enabled ? Ui.TEXT : Ui.GREEN);
        toggle.setBackground(Ui.overlayButton(this, Ui.GREEN, Ui.GREEN, enabled));
        if (!enabled && arrow != null) {
            if (arrow.isAttachedToWindow()) windows.removeView(arrow);
            arrow = null;
        }
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
        running = false;
        tipOn = false;
        oscarCapture = null;
        ChessScreenService.setWindowListener(null);
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
