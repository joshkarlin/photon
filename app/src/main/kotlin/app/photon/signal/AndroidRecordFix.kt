package app.photon.signal

import android.util.Log
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule

/**
 * Android's core library desugaring doesn't fully support java.lang.Record —
 * Jackson can't detect or serialize Records on Android. This fix patches
 * JsonUtil's ObjectMapper to handle specific Record classes used by
 * signal-service-java by serializing their fields via reflection.
 */
object AndroidRecordFix {
    private const val TAG = "AndroidRecordFix"

    // Record classes in signal-service-java that need serialization
    private val RECORD_CLASSES = listOf(
        "org.whispersystems.signalservice.internal.push.LinkDeviceRequest",
    )

    fun apply() {
        try {
            val jsonUtilClass = Class.forName("org.whispersystems.signalservice.internal.util.JsonUtil")
            val field = jsonUtilClass.getDeclaredField("objectMapper")
            field.isAccessible = true
            val mapper = field.get(null) as ObjectMapper

            val module = SimpleModule("AndroidRecordFix")

            for (className in RECORD_CLASSES) {
                try {
                    val clazz = Class.forName(className)
                    registerRecordSerializer(module, clazz)
                    Log.i(TAG, "Registered Record serializer for $className")
                } catch (e: ClassNotFoundException) {
                    Log.w(TAG, "Record class not found: $className")
                }
            }

            mapper.registerModule(module)
            Log.i(TAG, "Patched JsonUtil ObjectMapper")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to patch JsonUtil ObjectMapper", e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun registerRecordSerializer(module: SimpleModule, clazz: Class<*>) {
        val serializer = ReflectiveFieldSerializer(clazz)
        module.addSerializer(clazz as Class<Any>, serializer as JsonSerializer<Any>)
    }
}

/**
 * Serializes an object by reflecting over its declared (non-static) fields
 * and looking for accessor methods with matching names (Record-style: fieldName()).
 */
private class ReflectiveFieldSerializer<T>(private val targetClass: Class<T>) : JsonSerializer<T>() {

    private data class Accessor(val jsonName: String, val method: java.lang.reflect.Method)

    private val accessors: List<Accessor> by lazy {
        targetClass.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .mapNotNull { field ->
                try {
                    val method = targetClass.getMethod(field.name)
                    Accessor(field.name, method)
                } catch (_: NoSuchMethodException) {
                    // Try getter-style as fallback
                    try {
                        val getterName = "get" + field.name.replaceFirstChar { it.uppercase() }
                        val method = targetClass.getMethod(getterName)
                        Accessor(field.name, method)
                    } catch (_: NoSuchMethodException) {
                        Log.w("RecordFix", "No accessor for ${targetClass.simpleName}.${field.name}")
                        null
                    }
                }
            }
    }

    override fun serialize(value: T, gen: JsonGenerator, serializers: SerializerProvider) {
        gen.writeStartObject()
        for (accessor in accessors) {
            val fieldValue = accessor.method.invoke(value)
            gen.writeFieldName(accessor.jsonName)
            if (fieldValue == null) {
                gen.writeNull()
            } else {
                serializers.defaultSerializeValue(fieldValue, gen)
            }
        }
        gen.writeEndObject()
    }
}
