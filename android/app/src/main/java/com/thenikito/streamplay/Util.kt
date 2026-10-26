package com.thenikito.streamplay

import java.util.Calendar
import java.util.TimeZone

/** 1:02:03 / 4:05 */
fun tempo(ms0: Long): String {
    val s = maxOf(0L, ms0) / 1000
    val h = s / 3600
    val m = (s / 60) % 60
    val ss = s % 60
    return if (h >= 1) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
}

/** "Faltam 1h 20min" / "Faltam 5min" */
fun faltam(ms: Long): String {
    val s = maxOf(0L, ms) / 1000
    val h = s / 3600
    val m = (s / 60) % 60
    return if (h >= 1) "Faltam ${h}h ${m}min" else "Faltam ${maxOf(1, m)}min"
}

/** Datas sem hora no formato aaaammdd (o app roda em Android antigo, sem java.time). */
object Dia {
    fun de(ms: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        return c[Calendar.YEAR] * 10000 + (c[Calendar.MONTH] + 1) * 100 + c[Calendar.DAY_OF_MONTH]
    }

    fun hoje() = de(System.currentTimeMillis())

    /** "2026-10-25" -> 20261025 */
    fun parse(s: String?): Int? {
        if (s == null || !Regex("\\d{4}-\\d{2}-\\d{2}").matches(s)) return null
        val a = s.substring(0, 4).toInt()
        val m = s.substring(5, 7).toInt()
        val d = s.substring(8, 10).toInt()
        if (m !in 1..12 || d !in 1..31) return null
        return a * 10000 + m * 100 + d
    }

    private fun epoca(d: Int): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(d / 10000, (d / 100) % 100 - 1, d % 100)
        return c.timeInMillis / 86_400_000L
    }

    /** Quantos dias de [de] até [ate] (negativo se já passou). */
    fun dias(de: Int, ate: Int): Int = (epoca(ate) - epoca(de)).toInt()

    fun fmt(d: Int) = "%02d/%02d/%04d".format(d % 100, (d / 100) % 100, d / 10000)
    fun fmtCurto(d: Int) = "%02d/%02d".format(d % 100, (d / 100) % 100)
}
