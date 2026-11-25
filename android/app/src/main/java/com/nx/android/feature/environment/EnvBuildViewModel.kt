package com.nx.android.feature.environment

import androidx.lifecycle.ViewModel
import com.nx.android.appstate.EnvBuildState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * The environment's build, for the screens.
 *
 * The state itself is a Compose state (the dialog reads it while it draws), so the view model only carries it.
 */
@HiltViewModel
class EnvBuildViewModel @Inject constructor(
    val build: EnvBuildState,
) : ViewModel()
