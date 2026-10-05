// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

/**
 * What the UI should say about BLE permissions: granted, askable, or blocked
 * because Android will no longer show the dialog.
 */
enum class PermissionState {
    GRANTED,
    MISSING,
    DENIED_FOREVER,
}
