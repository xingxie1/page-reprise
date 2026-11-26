package app.pagereprise.core

import com.google.gson.Gson

object Codec {
    val json = Gson()
    fun encode(event: Event): String = json.toJson(event.checked())
    fun decode(raw: String): Event = requireNotNull(json.fromJson(raw, Event::class.java)).checked()
}
