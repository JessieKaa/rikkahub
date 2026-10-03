package me.rerere.rikkahub.data.familymode

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File

private const val TAG = "FamilyModeStore"
private const val FAMILY_MODE_STORE_FILE = "family_mode.preferences_pb"
private val FAMILY_MODE_RECORD_KEY = stringPreferencesKey("family_mode_record")

@Volatile
private var familyModeDataStore: DataStore<Preferences>? = null

// 进程内单例，同一文件只能存在一个 DataStore 实例。放在 noBackupFilesDir，排除自动备份。
private val Context.familyModeStore: DataStore<Preferences>
    get() = familyModeDataStore ?: synchronized(FamilyModeStore::class) {
        familyModeDataStore ?: createFamilyModeDataStore(applicationContext).also {
            familyModeDataStore = it
        }
    }

private fun createFamilyModeDataStore(context: Context): DataStore<Preferences> {
    val file = File(context.noBackupFilesDir, FAMILY_MODE_STORE_FILE)
    // 不注册 corruptionHandler：文件或 JSON 损坏时保持 Error，访问状态 fail-closed。
    return PreferenceDataStoreFactory.create(
        produceFile = { file },
    )
}

/**
 * 独立的本机家人模式存储。
 *
 * 读取失败（IO/解析/损坏）进入 [FamilyModeLoad.Error]，绝不当成“家人模式关闭”从而开放完整管理。
 * 文件不存在（新安装）会得到默认记录，即家人模式关闭。
 */
class FamilyModeStore(
    context: Context,
    private val scope: CoroutineScope,
) : FamilyModeSource {
    private val dataStore = context.familyModeStore

    private val _load = MutableStateFlow<FamilyModeLoad>(FamilyModeLoad.Loading)
    override val load: StateFlow<FamilyModeLoad> = _load.asStateFlow()

    private var collectJob: Job? = null

    init {
        startCollecting()
    }

    override fun retry() {
        startCollecting()
    }

    private fun startCollecting() {
        collectJob?.cancel()
        collectJob = scope.launch {
            _load.value = FamilyModeLoad.Loading
            try {
                dataStore.data.collect { preferences ->
                    _load.value = FamilyModeLoad.Ready(decodeRecord(preferences))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to read family mode record; entering locked recovery", e)
                _load.value = FamilyModeLoad.Error(e)
            }
        }
    }

    override suspend fun read(): FamilyModeRecord = decodeRecord(dataStore.data.first())

    override suspend fun write(record: FamilyModeRecord) {
        dataStore.edit { preferences ->
            preferences[FAMILY_MODE_RECORD_KEY] = JsonInstant.encodeToString(record)
        }
        _load.value = FamilyModeLoad.Ready(record)
    }

    private fun decodeRecord(preferences: Preferences): FamilyModeRecord {
        val raw = preferences[FAMILY_MODE_RECORD_KEY] ?: return FamilyModeRecord()
        // 解析失败向上抛出，由收集器转成 Error（fail-closed）。
        return JsonInstant.decodeFromString(raw)
    }
}
