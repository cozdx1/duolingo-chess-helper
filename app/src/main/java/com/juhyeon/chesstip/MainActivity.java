package com.juhyeon.chesstip;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 40, REQUEST_NOTIFICATION = 41;
    private enum Pending { NONE, OVERLAY, TOUCH, NOTIFICATION }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView overlayStatus, touchStatus, notificationStatus, screenStatus, setupHint;
    private Button startButton, manualButton;
    private Pending pending = Pending.NONE;
    private boolean preparing, prepareTouch, resumed, captureRequested;
    private long connectWaitAt;
    private String hint = "허용된 권한은 자동으로 건너뛰어요.";
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refreshStatus();
            if (preparing && pending == Pending.NONE) advanceSetup();
            handler.postDelayed(this, 800);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout content = Ui.column(this);
        scroll.addView(content);
        Ui.insets(this, content, 24, 22);
        Ui.add(content, Ui.text(this, "CHESS TIP   /   0.4.6", 12, Ui.GREEN, true), 0);
        Ui.add(content, Ui.text(this, "한 화면에서,\n한 수씩.", 30, Ui.TEXT, true), 20);
        Ui.add(content, Ui.text(this, "추천을 보며 직접 두거나, 자동 대국으로 지켜보세요.", 15, Ui.MUTED, false), 12);

        LinearLayout status = Ui.column(this);
        status.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16));
        status.setBackground(Ui.background(Ui.CARD, 20, this));
        Ui.add(status, Ui.text(this, "시작 전 자동 점검", 13, Ui.MUTED, true), 0);
        overlayStatus = Ui.text(this, "", 15, Ui.TEXT, false);
        touchStatus = Ui.text(this, "", 15, Ui.TEXT, false);
        notificationStatus = Ui.text(this, "", 15, Ui.MUTED, false);
        screenStatus = Ui.text(this, "", 14, Ui.MUTED, false);
        Ui.add(status, overlayStatus, 12);
        Ui.add(status, touchStatus, 8);
        Ui.add(status, notificationStatus, 8);
        Ui.add(status, screenStatus, 12);
        Ui.add(content, status, 24);

        startButton = Ui.button(this, "준비하고 듀오링고 연결", true);
        startButton.setOnClickListener(view -> startSetup(true));
        Ui.add(content, startButton, 20);
        setupHint = Ui.text(this, hint, 13, Ui.MUTED, false);
        Ui.add(content, setupHint, 10);
        manualButton = Ui.button(this, "Tip만 연결하기", false);
        manualButton.setOnClickListener(view -> startSetup(false));
        Ui.add(content, manualButton, 12);
        Button stop = Ui.button(this, "연결 종료", false);
        stop.setOnClickListener(view -> {
            preparing = false;
            stopService(new Intent(this, TipService.class));
            refreshStatus();
        });
        Ui.add(content, stop, 12);
        Ui.add(content, Ui.text(this,
                "화면 공유는 ‘앱 하나 → 듀오링고’를 선택하세요.\n"
                        + "대국 화면에서 TIP과 자동 대국을 각각 켜고 끌 수 있어요.\n"
                        + "자동 대국 정지를 누르면 직접 둘 수 있어요.\n"
                        + "패널 어디든 끌어서 옮길 수 있어요. 버튼은 짧게 눌러주세요.", 14, Ui.MUTED, false), 24);
        Ui.add(content, Ui.text(this,
                "오스카·친구·랜덤 대국 · 세로 화면 · 다크 테마용입니다.\n"
                        + "자동 대국은 결과 창에서도 켜진 채 기다리며, 새 대국을 열면 이어가요.\n"
                        + "화면 공유는 연결할 때마다 허용이 필요해요. 나머지 권한은 한 번 설정하면 계속 사용해요.\n\n"
                        + "화면과 추천 계산은 폰 안에서 처리하며 이미지·영상을 저장하거나 전송하지 않습니다.",
                13, Ui.MUTED, false), 16);
        setContentView(scroll);
    }

    private void startSetup(boolean requireTouch) {
        if (TipService.running || captureRequested) return;
        preparing = true;
        prepareTouch = requireTouch;
        connectWaitAt = 0;
        pending = Pending.NONE;
        advanceSetup();
        refreshStatus();
    }

    private void advanceSetup() {
        if (!preparing || !resumed || pending != Pending.NONE || captureRequested) return;
        if (!Settings.canDrawOverlays(this)) {
            pending = Pending.OVERLAY;
            hint = "Chess Tip의 ‘다른 앱 위에 표시’를 켜고 돌아오세요. 다음 단계로 이어져요.";
            refreshStatus();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            return;
        }
        if (prepareTouch && !ChessTouchService.enabled(this)) {
            pending = Pending.TOUCH;
            hint = "접근성 → 설치된 앱 → Chess Tip 자동 대국 → 사용을 켜고 돌아오세요.";
            refreshStatus();
            Toast.makeText(this, hint, Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (prepareTouch && ChessTouchService.current() == null) {
            if (connectWaitAt == 0) connectWaitAt = SystemClock.elapsedRealtime();
            hint = "터치 권한은 허용되어 있어요. 서비스 연결을 기다리고 있어요…";
            if (SystemClock.elapsedRealtime() - connectWaitAt > 10000) {
                preparing = false;
                hint = "터치 서비스 연결이 지연돼요. 잠시 후 연결 버튼을 다시 눌러주세요.";
            }
            refreshStatus();
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) {
            pending = Pending.NOTIFICATION;
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATION);
            return;
        }
        preparing = false;
        captureRequested = true;
        hint = "공유 창에서 ‘앱 하나 → 듀오링고’를 선택하세요.";
        Log.i("ChessTip", "SETUP_READY touch=" + prepareTouch);
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQUEST_NOTIFICATION) { pending = Pending.NONE; advanceSetup(); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_CAPTURE) return;
        captureRequested = false;
        if (result != RESULT_OK || data == null) {
            hint = "화면 연결을 취소했어요. 연결 버튼으로 다시 시작할 수 있어요.";
            refreshStatus();
            return;
        }
        Intent service = new Intent(this, TipService.class)
                .putExtra(TipService.RESULT_CODE, result).putExtra(TipService.RESULT_DATA, data);
        startForegroundService(service);
        handler.postDelayed(this::refreshStatus, 400);
    }

    private void refreshStatus() {
        if (startButton == null) return;
        boolean overlay = Settings.canDrawOverlays(this);
        boolean touch = ChessTouchService.enabled(this);
        boolean connected = ChessTouchService.current() != null;
        overlayStatus.setText(overlay ? "✓ 다른 앱 위에 표시 · 준비됨" : "○ 다른 앱 위에 표시 · 허용 필요");
        overlayStatus.setTextColor(overlay ? Ui.GREEN : Ui.TEXT);
        touchStatus.setText(touch ? connected ? "✓ 자동 대국 터치 · 준비됨" : "◌ 자동 대국 터치 · 허용됨, 연결 중" : "○ 자동 대국 터치 · 허용 필요");
        touchStatus.setTextColor(touch && connected ? Ui.GREEN : Ui.TEXT);
        notificationStatus.setText(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                ? "✓ 상태 알림 · 허용됨" : "○ 상태 알림 · 꺼짐 (연결 가능)");
        screenStatus.setText(TipService.running ? "✓ 화면 연결 중 · " + TipService.coachStatus
                : TipService.lastError != null ? TipService.lastError : "○ 듀오링고 화면 공유 · 연결할 때 허용");
        startButton.setEnabled(!preparing && !captureRequested && !TipService.running);
        startButton.setText(TipService.running ? "듀오링고 연결 중" : preparing ? "준비 중…"
                : overlay && touch && connected ? "듀오링고 연결" : "준비하고 듀오링고 연결");
        setupHint.setText(hint);
        manualButton.setVisibility(touch && connected ? View.GONE : View.VISIBLE);
        manualButton.setEnabled(!preparing && !captureRequested && !TipService.running);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (pending == Pending.OVERLAY || pending == Pending.TOUCH) {
            boolean allowed = pending == Pending.OVERLAY ? Settings.canDrawOverlays(this) : ChessTouchService.enabled(this);
            pending = Pending.NONE;
            if (!allowed) {
                preparing = false;
                hint = "아직 허용되지 않았어요. 연결 버튼으로 이어서 준비하거나 Tip만 연결할 수 있어요.";
            }
        }
        Log.i("ChessTip", "SETUP_CHECK overlay=" + Settings.canDrawOverlays(this) + " touchEnabled="
                + ChessTouchService.enabled(this) + " touchConnected=" + (ChessTouchService.current() != null));
        handler.post(refresh);
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
