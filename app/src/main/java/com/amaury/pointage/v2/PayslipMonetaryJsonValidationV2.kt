package com.amaury.pointage.v2

import android.util.JsonReader
import android.util.JsonToken
import com.amaury.pointage.v2.engine.PayslipEngineV2
import java.io.StringReader

/** Checks only monetary JSON tokens before org.json can coerce them to Double.
 * Existing decoders still validate identities, schema and metadata. No bytes are rewritten.
 */
internal object PayslipMonetaryJsonValidationV2 {
    fun records(raw: String): Boolean = validate(raw) { reader ->
        reader.beginArray()
        while (reader.hasNext()) record(reader)
        reader.endArray()
    }

    fun observed(raw: String): Boolean = validate(raw) { reader ->
        fields(reader) { amounts(reader) }
    }

    fun drafts(raw: String): Boolean = validate(raw) { reader ->
        fields(reader) { draft(reader) }
    }

    fun singleDraft(raw: String): Boolean = validate(raw, ::draft)

    private fun record(reader: JsonReader) = fields(reader) { key ->
        if (key == "gross" || key == "net") amount(reader, nullable = true) else reader.skipValue()
    }

    private fun draft(reader: JsonReader) = fields(reader) { key ->
        when (key) {
            "proposedValues" -> amounts(reader)
            "originalValues" -> optional(reader) { amounts(reader) }
            "originalRecord" -> optional(reader) { record(reader) }
            else -> reader.skipValue() // rawFields/evidence are text, including deliberately invalid drafts.
        }
    }

    private fun amounts(reader: JsonReader) = fields(reader) { amount(reader, nullable = false) }

    private fun amount(reader: JsonReader, nullable: Boolean) {
        if (nullable && reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return
        }
        require(reader.peek() == JsonToken.NUMBER)
        require(PayslipEngineV2.parseAmount(reader.nextString()) != null)
    }

    private fun optional(reader: JsonReader, read: () -> Unit) {
        if (reader.peek() == JsonToken.NULL) reader.nextNull() else read()
    }

    private fun fields(reader: JsonReader, read: (String) -> Unit) {
        // Leave non-object structural errors to the existing decoder, preserving its partial audit view.
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return
        }
        reader.beginObject()
        while (reader.hasNext()) read(reader.nextName())
        reader.endObject()
    }

    private fun validate(raw: String, read: (JsonReader) -> Unit): Boolean = runCatching {
        JsonReader(StringReader(raw)).use { reader ->
            reader.isLenient = false
            read(reader)
            require(reader.peek() == JsonToken.END_DOCUMENT)
        }
        true
    }.getOrDefault(false)
}
