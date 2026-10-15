package com.logan.ktoast.runtime

/** 主动治理导致的取消原因；原 dismissReason 仍为 CANCELED。 */
enum class ToastDropReason {
    CAPACITY_EXCEEDED,
    EXPIRED
}
