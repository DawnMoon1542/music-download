package com.dawnmoon.music_download.smoke

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

@Serializable
data class SmokeProbe(
    val code: Int = 0,
    val msg: String = "",
    @SerialName("data") val data: SmokeData? = null,
)

@Serializable
data class SmokeData(val level: String = "", val size: String = "")

@Entity(tableName = "smoke_task")
data class SmokeTask(
    @PrimaryKey val id: Long,
    val name: String,
    val status: String,
)

@Dao
interface SmokeDao {
    @Query("SELECT * FROM smoke_task")
    fun observe(): Flow<List<SmokeTask>>

    @Insert
    suspend fun insert(task: SmokeTask)
}

@Database(entities = [SmokeTask::class], version = 1)
abstract class SmokeDatabase : RoomDatabase() {
    abstract fun dao(): SmokeDao
}

val Context.smokeDataStore by preferencesDataStore(name = "smoke")

class SmokeSettings(private val context: Context) {
    private val keyName = stringPreferencesKey("api_key")

    val apiKey: Flow<String?> = context.smokeDataStore.data.map { it[keyName] }

    suspend fun setApiKey(value: String) {
        context.smokeDataStore.edit { it[keyName] = value }
    }
}

class SmokeScope(context: Context) {
    val json = Json { ignoreUnknownKeys = true }
    val client = OkHttpClient()
    val db = Room.databaseBuilder(context, SmokeDatabase::class.java, "smoke.db").build()
    val settings = SmokeSettings(context)
}

@Composable
fun SmokeScreen(onClick: () -> Unit) {
    Column {
        Text("Material 3 smoke", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = onClick, modifier = Modifier) { Text("Tap") }
    }
}
