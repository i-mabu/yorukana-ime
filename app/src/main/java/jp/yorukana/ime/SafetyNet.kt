package jp.yorukana.ime

import android.content.Context
import android.content.SharedPreferences

// ===== 1. セーフティの永続化と例外記録 (Android 側) =====
// 状態は専用の SharedPreferences (yorukana_safety) に持つ。本体の
// 設定ファイルが壊れても判定を続けられるよう、別ファイルにする。
// 書き込みは commit() で同期する: 起動直後にプロセスが死んでも、
// 「起動を開始した」事実とクラッシュ記録が残ることが要のため。
object SafetyNet {

    private const val PREFS = "yorukana_safety"
    private const val KEY_PENDING = "startup_pending"
    private const val KEY_FAILURES = "startup_failures"
    private const val KEY_TOTAL_CRASHES = "total_crashes"
    private const val KEY_LAST_CRASH = "last_crash"
    private const val KEY_MODE = "last_mode"

    @Volatile
    private var installed = false

    @Volatile
    private var appContext: Context? = null

    // 未捕捉例外ハンドラを入れる。記録だけして既定ハンドラへ委譲する
    // (プロセスの終了自体は妨げない。握り潰すとゾンビプロセス化する)。
    fun install(context: Context) {
        appContext = context.applicationContext
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                recordCrash(error)
            } catch (e: Exception) {
                // 記録の失敗で二次クラッシュさせない
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    // 起動開始: 前回起動が未完了のままなら失敗として数え、今回の
    // モードを決める。今回分として pending を立ててから返す
    // (起動が完了したら markStartupCompleted で下ろす)。
    fun beginLaunch(context: Context): SafetyMode {
        val p = prefs(context)
        val failures = SafetyGuard.failuresAtLaunch(
            p.getInt(KEY_FAILURES, 0),
            p.getBoolean(KEY_PENDING, false),
        )
        val mode = SafetyGuard.modeFor(failures)
        p.edit()
            .putInt(KEY_FAILURES, failures)
            .putBoolean(KEY_PENDING, true)
            .putString(KEY_MODE, mode.name)
            .commit()
        return mode
    }

    // 起動完了: pending を下ろし、連続失敗をリセットする
    fun markStartupCompleted(context: Context) {
        try {
            prefs(context).edit()
                .putBoolean(KEY_PENDING, false)
                .putInt(KEY_FAILURES, 0)
                .commit()
        } catch (e: Exception) {
            // 記録に失敗しても起動完了の扱いは変えない
        }
    }

    // クラッシュを記録する (ハンドラから呼ばれる)
    fun recordCrash(error: Throwable) {
        val ctx = appContext ?: return
        val p = prefs(ctx)
        val stackTop = error.stackTrace.take(10).joinToString("\n") { "  at $it" }
        val record = SafetyGuard.formatCrashRecord(
            System.currentTimeMillis(),
            error.javaClass.name,
            error.message,
            stackTop,
        )
        p.edit()
            .putInt(KEY_TOTAL_CRASHES, p.getInt(KEY_TOTAL_CRASHES, 0) + 1)
            .putString(KEY_LAST_CRASH, record)
            .commit()
    }

    fun totalCrashes(context: Context): Int {
        return prefs(context).getInt(KEY_TOTAL_CRASHES, 0)
    }

    fun lastCrashRecord(context: Context): String? {
        return prefs(context).getString(KEY_LAST_CRASH, null)
    }

    // 前回起動で使ったモード (設定画面の表示用)
    fun lastMode(context: Context): SafetyMode {
        return try {
            SafetyMode.valueOf(
                prefs(context).getString(KEY_MODE, SafetyMode.NORMAL.name)
                    ?: SafetyMode.NORMAL.name,
            )
        } catch (e: Exception) {
            SafetyMode.NORMAL
        }
    }

    // セーフモード解除 (設定画面から): 連続失敗と pending を消す
    fun reset(context: Context) {
        prefs(context).edit()
            .putInt(KEY_FAILURES, 0)
            .putBoolean(KEY_PENDING, false)
            .commit()
    }

    // クラッシュ記録の消去 (設定画面から)
    fun clearCrashLog(context: Context) {
        prefs(context).edit()
            .putInt(KEY_TOTAL_CRASHES, 0)
            .remove(KEY_LAST_CRASH)
            .commit()
    }
}
