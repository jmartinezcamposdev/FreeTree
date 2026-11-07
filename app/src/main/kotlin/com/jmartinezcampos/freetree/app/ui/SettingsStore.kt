package com.jmartinezcampos.freetree.app.ui

import com.jmartinezcampos.freetree.core.persistence.AppDataPaths
import com.jmartinezcampos.freetree.core.persistence.JsonFileStore
import java.nio.file.Path

internal interface SettingsStore {
    fun load(): AppSettings?
    fun save(settings: AppSettings)
}

internal class JsonSettingsStore(
    file: Path = AppDataPaths.preferencesFile(),
) : SettingsStore {
    private val store = JsonFileStore(file, AppSettings.serializer())

    override fun load(): AppSettings? = store.load()

    override fun save(settings: AppSettings) {
        store.save(settings)
    }
}

internal class InMemorySettingsStore(
    initial: AppSettings? = null,
) : SettingsStore {
    var saved: AppSettings? = initial
        private set

    override fun load(): AppSettings? = saved

    override fun save(settings: AppSettings) {
        saved = settings
    }
}
