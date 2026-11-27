package com.juhyeon.chesstip;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.move.Move;

final class MoveTip {
    record Tip(String move, String title, String feature, String continuation, boolean capture) {}

    static Tip explain(String fen, StockfishEngine.Result result) {
        Board board = new Board();
        board.loadFromFen(fen);
        Move move;
        try { move = new Move(result.move(), board.getSideToMove()); }
        catch (RuntimeException error) { return null; }
        if (!board.legalMoves().contains(move)) return null;
        Piece piece = board.getPiece(move.getFrom());
        Piece captured = board.getPiece(move.getTo());
        String pieceName = name(piece.getFenSymbol().charAt(0));
        String from = result.move().substring(0, 2);
        String to = result.move().substring(2, 4);
        char type = Character.toLowerCase(piece.getFenSymbol().charAt(0));
        boolean capture = captured != Piece.NONE || (type == 'p' && from.charAt(0) != to.charAt(0));
        String feature;
        if (type == 'k' && Math.abs(from.charAt(0) - to.charAt(0)) == 2) feature = "캐슬링으로 왕을 옮기고 룩을 전개해요.";
        else if (captured != Piece.NONE) feature = "상대 " + name(captured.getFenSymbol().charAt(0)) + "을 잡는 수예요.";
        else if (type == 'p' && from.charAt(0) != to.charAt(0)) feature = "앙파상으로 상대 폰을 잡아요.";
        else if ((type == 'n' || type == 'b') && (from.charAt(1) == '1' || from.charAt(1) == '8')) feature = pieceName + "을 전개해 활동할 공간을 넓혀요.";
        else if (type == 'p' && "de".indexOf(to.charAt(0)) >= 0) feature = "중앙 파일에 폰을 전진시키는 수예요.";
        else feature = "현재 판에서 엔진이 가장 높게 평가한 수예요.";
        board.doMove(move);
        if (board.isMated()) feature = "이 수로 체크메이트예요.";
        else if (board.isKingAttacked()) feature = "상대 왕에게 체크를 거는 수예요.";
        if (result.move().length() == 5) feature = name(result.move().charAt(4)) + "으로 폰을 승격해요.";
        String[] variation = result.variation().split(" ");
        String continuation = variation.length >= 3 ? "예상 " + variation[1] + " → " + variation[2] : "분석 깊이 " + result.depth();
        return new Tip(result.move(), pieceName + " " + from + " → " + to, feature, continuation, capture);
    }

    private static String name(char piece) {
        return switch (Character.toLowerCase(piece)) {
            case 'p' -> "폰";
            case 'n' -> "나이트";
            case 'b' -> "비숍";
            case 'r' -> "룩";
            case 'q' -> "퀸";
            case 'k' -> "킹";
            default -> "기물";
        };
    }
}
