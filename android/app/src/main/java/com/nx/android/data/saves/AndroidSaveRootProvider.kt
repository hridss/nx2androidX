package com.nx.android.data.saves

import com.nx.android.data.PrefsStore
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The Android implementation reads the one preference that points at the save root. */
@Singleton
class AndroidSaveRootProvider @Inject constructor(private val prefs: PrefsStore) : SaveRootProvider {
    override fun resolve(filesDir: File): SaveRoot = SaveRoot.of(prefs.saveRoot, filesDir)
}
