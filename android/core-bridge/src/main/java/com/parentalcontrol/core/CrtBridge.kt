package com.parentalcontrol.core

typealias CrtChildEngine = uniffi.parental_control_core.CrtChildEngine
typealias CrtParentEngine = uniffi.parental_control_core.CrtParentEngine
typealias CrtProcessedResult = uniffi.parental_control_core.CrtProcessedResult
typealias CrtDeviceLockStatus = uniffi.parental_control_core.CrtDeviceLockStatus
typealias CrtAppDecision = uniffi.parental_control_core.CrtAppDecision
typealias CrtPairingResult = uniffi.parental_control_core.CrtPairingResult
typealias CrtError = uniffi.parental_control_core.CrtException

object CrtNativeLoader {
    fun load() {
        // UniFFI auto-loads libparental_control_core via JNA
    }
}
