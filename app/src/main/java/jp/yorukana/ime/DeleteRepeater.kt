package jp.yorukana.ime

import android.os.Handler

// 削除キー長押しリピート。
// 削除キーを長押ししている間、一定間隔で削除処理を繰り返す Runnable。
// inner class ではなくトップレベルクラスにしているのは、この手動ビルドの
// d8 が inner class のクラスファイルで NPE を起こすため。
class DeleteRepeater(
    private val handler: Handler,
    private val action: () -> Unit,
) : Runnable {
    override fun run() {
        action()
        handler.postDelayed(this, 50)
    }
}
