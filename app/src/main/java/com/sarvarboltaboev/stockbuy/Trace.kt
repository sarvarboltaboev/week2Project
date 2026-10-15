package com.sarvarboltaboev.stockbuy

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Which hop of the buy path a step happened in. Order = path order. */
enum class Layer { UI, VM, HTTP, GATEWAY, AUTH, GRPC, TX, FIX, ASYNQ, WORKER }

data class TraceStep(val n: Int, val layer: Layer, val msg: String, val atMs: Long)

/** Collects every hop of one buy. Shown in TracePanel and logged as `adb logcat -s BuyTrace`. */
object Tracer {
    private val start = System.currentTimeMillis()
    private val _steps = MutableStateFlow<List<TraceStep>>(emptyList())
    val steps: StateFlow<List<TraceStep>> = _steps

    fun log(layer: Layer, msg: String) {
        _steps.update { it + TraceStep(it.size + 1, layer, msg, System.currentTimeMillis() - start) }
        Log.d("BuyTrace", "${layer.name.padEnd(8)} $msg")
    }

    fun clear() = _steps.update { emptyList() }
}
