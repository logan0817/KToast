package com.logan.ktoast.runtime

data class KToastBehavior(
    var displayMode: KToastDisplayMode = KToastDisplayMode.REPLACE,
    var windowMillis: Long = 300L,
    var groupKey: String? = null,
    var tag: String? = null
)
