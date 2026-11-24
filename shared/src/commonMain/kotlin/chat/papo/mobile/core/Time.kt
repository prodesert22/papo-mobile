package chat.papo.mobile.core

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

fun parseInstant(iso: String?): Instant? = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun Int.pad() = toString().padStart(2, '0')

/** `dd/MM/aaaa HH:mm` no fuso do aparelho. */
fun Instant.formatDateTime(zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val t = toLocalDateTime(zone)
    return "${t.day.pad()}/${t.month.ordinal.plus(1).pad()}/${t.year} ${t.hour.pad()}:${t.minute.pad()}"
}

/** `HH:mm` no fuso do aparelho. */
fun Instant.formatTime(zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val t = toLocalDateTime(zone)
    return "${t.hour.pad()}:${t.minute.pad()}"
}

/** Dia local como número `aaaammdd`, para comparar se duas mensagens são do mesmo dia. */
fun Instant.dayKey(zone: TimeZone = TimeZone.currentSystemDefault()): Int {
    val t = toLocalDateTime(zone)
    return t.year * 10_000 + (t.month.ordinal + 1) * 100 + t.day
}

/** `dd/MM/aaaa` no fuso do aparelho. */
fun Instant.formatDate(zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val t = toLocalDateTime(zone)
    return "${t.day.pad()}/${t.month.ordinal.plus(1).pad()}/${t.year}"
}
