// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.protocol

/**
 * The charging state the car reports: one value per member of the `oneof` in
 * `ChargeState.ChargingState`. Read it with [chargingStateKind] in `StateViews.kt`.
 */
enum class ChargingStateKind {
    Charging,
    Complete,
    Stopped,
    Disconnected,
    NoPower,
    Starting,
    Calibrating,
    Unknown,
}

/**
 * The gear the car reports: one value per member of the `oneof` in `ShiftState`.
 * Read it with [shiftStateKind] in `StateViews.kt`.
 */
enum class ShiftStateKind {
    P,
    R,
    N,
    D,
    Invalid,
    SNA,
}
