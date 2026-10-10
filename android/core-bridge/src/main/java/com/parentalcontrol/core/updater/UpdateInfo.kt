package com.parentalcontrol.core.updater

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelog: String = "",
    val isMandatory: Boolean = false,
    val sha256: String? = null
)
