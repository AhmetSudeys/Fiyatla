package com.ahmetsudeys.dogalgazteklif.ui.util

import androidx.annotation.IdRes
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController

/**
 * Navigates only if [fromDestinationId] is still the current destination.
 *
 * A NavController updates its back stack immediately while the fragment transaction runs later, so
 * two navigations issued in the same main-loop pass — a double tap, or a billing callback landing
 * right after the user pressed a button — resolve the action against a destination that no longer
 * owns it and crash with IllegalArgumentException. This makes the second one a no-op instead.
 */
fun Fragment.navigateOnceFrom(@IdRes fromDestinationId: Int, @IdRes actionId: Int) {
    if (!isAdded) return
    val navController = findNavController()
    if (navController.currentDestination?.id != fromDestinationId) return
    navController.navigate(actionId)
}
