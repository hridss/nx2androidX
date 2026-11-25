package com.nx.android.agent.runtime

import android.content.Context
import com.nx.android.agent.AgentChat
import com.nx.android.agent.AgentSettings
import com.nx.android.agent.env.PiAgentChat
import com.nx.android.core.storage.AppPaths
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The panel's chat is the env agent (ADR-009); the settings are the store. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AgentModule {
    @Binds abstract fun bindChat(impl: PiAgentChat): AgentChat
    @Binds abstract fun bindSettings(impl: AgentSettingsStore): AgentSettings

    companion object {
        /** The prompt material comes out of the App's own assets. */
        @Provides
        @Singleton
        fun provideSeedSource(@ApplicationContext context: Context): AgentSeedSource =
            AssetSeedSource(context.assets)

        /** The material is planted on the player's nx2android folder: the engine reads it there. */
        @Provides
        @Singleton
        fun providePlanter(source: AgentSeedSource, paths: AppPaths): AgentPlanter =
            AgentPlanter(source, paths.nxHome)
    }
}
