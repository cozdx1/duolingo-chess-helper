package com.juhyeon.chesstip;

import static org.junit.Assert.*;
import org.junit.Test;

public class PanelStatusTest {
    private static final PanelStatus.Text OPPONENT = new PanelStatus.Text("나: 백 · 상대: 흑\n상대 차례", "");
    private static final PanelStatus.Text OWN = new PanelStatus.Text("나: 백 · 상대: 흑\n내 차례", "추천 수 계산 중…");
    private static final PanelStatus.Text WAIT = new PanelStatus.Text("자동 대국 · 확인 대기", "판을 계속 다시 읽고 있어요.");

    @Test public void repeatedBriefReadingGapsKeepTheOpponentTextAndColors() {
        PanelStatus display = new PanelStatus();
        display.update(0, OPPONENT, false);
        for (long now = 100; now <= 10000; now += 100) {
            boolean readingGap = now % 1000 >= 700;
            assertEquals(OPPONENT, display.update(now, readingGap ? WAIT : OPPONENT, readingGap));
        }
    }

    @Test public void sustainedWaitAppearsAfterThreeSecondsWithOneStableExplanation() {
        PanelStatus display = new PanelStatus();
        display.update(0, OPPONENT, false);
        assertEquals(OPPONENT, display.update(100, WAIT, true));
        assertEquals(OPPONENT, display.update(3099, WAIT, true));
        assertEquals(WAIT, display.update(3100, WAIT, true));
        PanelStatus.Text movement = new PanelStatus.Text("자동 대국 · 확인 대기", "이동 반영을 확인하고 있어요.");
        assertEquals(WAIT, display.update(3200, movement, true));
        assertEquals(OWN, display.update(3300, OWN, false));
    }

    @Test public void confirmedTurnAndPlayerColorChangesAreImmediate() {
        PanelStatus display = new PanelStatus();
        display.update(0, OPPONENT, false);
        display.update(100, WAIT, true);
        assertEquals(OWN, display.update(200, OWN, false));
        PanelStatus.Text black = new PanelStatus.Text("나: 흑 · 상대: 백\n상대 차례", "");
        assertEquals(black, display.update(300, black, false));
    }

    @Test public void resetDropsThePreviousGameAndAllowsImmediateInstructions() {
        PanelStatus display = new PanelStatus();
        display.update(0, OPPONENT, false);
        display.reset();
        assertEquals(WAIT, display.update(100, WAIT, true));
        assertEquals(OWN, display.update(200, OWN, false));
        display.reset();
        PanelStatus.Text permission = new PanelStatus.Text("화면 연결 대기", "화면을 다시 연결해 주세요.");
        assertEquals(permission, display.update(300, permission, true));
    }
}
