package io.github.mangi.eta.data.repository

import android.app.Application
import androidx.room.withTransaction
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.OpenVikingCacheEntity
import io.github.mangi.eta.data.db.OpenVikingSyncStateEntity
import io.github.mangi.eta.data.model.OpenVikingSettings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class OpenVikingBackupHygieneTest {
    private val app = RuntimeEnvironment.getApplication()
    @Before fun setup() {
        EtaDatabase.closeForTests(); app.deleteDatabase("eta.db")
        SettingsDataStore.init(app); AgentMemoryRepository.init(app); ProviderRepository.init(app)
    }
    @OptIn(ExperimentalSerializationApi::class)
    @Test fun typedBackupContractHasNoRemoteSettingsSecretsOrOperationalTables() = runBlocking {
        val fields = EtaBackupDocument.serializer().descriptor.let { d -> (0 until d.elementsCount).map(d::getElementName) }
        assertTrue(fields.none { it in OpenVikingBackupPolicy.excludedNames })
        SettingsDataStore.setOpenVikingSettings(OpenVikingSettings(enabled = true, baseUrl = "https://private-server.invalid", secretRevision = "private-slot"))
        app.getSharedPreferences("eta_openviking_secrets", 0).edit().putString("credential_private-slot", "private-secret-fixture").commit()
        EtaDatabase.get(app).openVikingDao().putCache(OpenVikingCacheEntity("connection", "profile", "v1", "private-payload-fixture".toByteArray(), Long.MAX_VALUE))
        val output = ByteArrayOutputStream()
        EtaBackupRepository.export(app, output)
        val text = output.toString(Charsets.UTF_8.name())
        for (name in OpenVikingBackupPolicy.excludedNames + setOf("private-server.invalid", "private-slot", "private-secret-fixture", "private-payload-fixture")) assertFalse(name, text.contains(name))
        assertEquals(1, EtaDatabase.get(app).openVikingDao().cacheCount("connection", "profile"))
    }
    @Test fun restoreIgnoresInjectedRemoteFieldsDisablesLocalSyncAndClearsOldOperationalState() = runBlocking {
        SettingsDataStore.setOpenVikingSettings(OpenVikingSettings(enabled = true, baseUrl = "https://local.invalid", secretRevision = "local-slot"))
        val dao = EtaDatabase.get(app).openVikingDao()
        dao.putCache(OpenVikingCacheEntity("old", "capture_draft", "draft", byteArrayOf(1), Long.MAX_VALUE))
        dao.insertSyncState(OpenVikingSyncStateEntity("old"))
        val injected = """{"format":"eta-backup","schemaVersion":4,"exportedAt":0,"openviking_settings_v1":{"enabled":true,"secretRevision":"attacker-slot"},"eta_openviking_secrets":"attacker","openviking_outbox":["replay"]}"""
        EtaBackupRepository.import(app, ByteArrayInputStream(injected.toByteArray()))
        val local = OpenVikingSettingsRepository.settings()
        assertFalse(local.enabled)
        assertEquals("local-slot", local.secretRevision)
        assertEquals("https://local.invalid", local.baseUrl)
        assertTrue(dao.connectionIds().isEmpty())
    }
    @Test fun restoreRefusesToEraseEvidenceWhileASyncLeaseIsLive() = runBlocking {
        val db = EtaDatabase.get(app)
        db.openVikingDao().insertSyncState(OpenVikingSyncStateEntity("active", leaseOwner = "worker", leaseUntil = System.currentTimeMillis() + 60_000))
        db.openVikingDao().putCache(OpenVikingCacheEntity("active", "epoch", "scope", byteArrayOf(1), Long.MAX_VALUE))
        try { db.withTransaction { OpenVikingBackupPolicy.clearForRestore(db) }; fail("Live worker must stop before restore") }
        catch (_: IllegalStateException) { }
        assertNotNull(db.openVikingDao().syncState("active"))
        assertNotNull(db.openVikingDao().cache("active", "epoch", "scope"))
    }
}
