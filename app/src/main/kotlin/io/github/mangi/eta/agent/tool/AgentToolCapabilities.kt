package io.github.mangi.eta.agent.tool

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.accessibility.AccessibilityProtectionClient
import io.github.mangi.eta.agent.device.AgentNotificationHistoryService
import io.github.mangi.eta.agent.device.RootAccessState
import io.github.mangi.eta.agent.device.RootAccess
import java.util.Locale
import org.json.JSONArray

/** 每轮冻结的运行条件；不包含用户开关，也不触发授权请求。 */
internal data class AgentToolCapabilities(
    val rootAvailable: Boolean,
    val rootState: RootAccessState? = null,
    val lsposedAvailable: Boolean = false,
    val accessibilityAvailable: Boolean = true,
    val accessibilityRecoveryAvailable: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val usageAllowed: Boolean = true,
    val locationAllowed: Boolean = true,
    val colorOs: Boolean = true,
    val calendarReadable: Boolean = true,
    val calendarWritable: Boolean = true,
    val flashlightAvailable: Boolean = true,
    val hotspotAvailable: Boolean = true,
    val nfcAvailable: Boolean = true,
    val nightLightAvailable: Boolean = true,
) {
    fun unavailableCode(name: String): String? {
        if (name in setOf("get_flashlight", "set_flashlight") && !flashlightAvailable) return "DEVICE_UNSUPPORTED"
        if (name in setOf("get_hotspot", "set_hotspot") && !hotspotAvailable) return "DEVICE_UNSUPPORTED"
        val requirement = AgentToolRequirements.find(name) ?: return "UNKNOWN_TOOL"
        if (requirement.rootRequirement == RootRequirement.REQUIRED && !rootAvailable) return "ROOT_REQUIRED"
        if (requirement.lsposedRequirement == LsposedRequirement.REQUIRED && !lsposedAvailable) return "LSPOSED_REQUIRED"
        if (requirement.colorOs && !colorOs) return "DEVICE_UNSUPPORTED"
        if (requirement.accessibility && !accessibilityAvailable && !accessibilityRecoveryAvailable) {
            return "ACCESSIBILITY_UNAVAILABLE"
        }
        return when (requirement.systemAccess) {
            ToolSystemAccess.CALENDAR_READ -> if (rootAvailable || calendarReadable) null else "CALENDAR_PERMISSION_REQUIRED"
            ToolSystemAccess.CALENDAR_WRITE -> if (rootAvailable || calendarWritable) null else "CALENDAR_PERMISSION_REQUIRED"
            ToolSystemAccess.NONE -> null
            ToolSystemAccess.NOTIFICATIONS -> if (notificationsAllowed ||
                (rootAvailable && (name == "recent_notifications" ||
                    (name == "search_personal_orders" && colorOs)))
            ) null else "NOTIFICATION_ACCESS_REQUIRED"
            ToolSystemAccess.USAGE -> if (usageAllowed) null else "APP_USAGE_ACCESS_REQUIRED"
            ToolSystemAccess.LOCATION -> if (locationAllowed) null else "LOCATION_PERMISSION_REQUIRED"
        }
    }

    fun project(tools: JSONArray): JSONArray {
        val rootProjected = AgentToolRequirements.project(tools, rootAvailable)
        return JSONArray().also { visible ->
            for (index in 0 until rootProjected.length()) {
                val tool = rootProjected.getJSONObject(index)
                val name = tool.getJSONObject("function").getString("name")
                if (unavailableCode(name) == null) {
                    if (name in setOf("get_device_state", "set_device_state") && (!nfcAvailable || !nightLightAvailable)) {
                        val target = tool.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties").getJSONObject("target")
                        target.put("enum", JSONArray(SystemStateControls.targets.filterNot { it == "nfc" && !nfcAvailable || it == "night_light" && !nightLightAvailable }))
                    }
                    visible.put(tool)
                }
            }
        }
    }

    companion object {
        private data class HardwareKey(val packageManager: Int, val resources: Int, val manufacturer: String, val sdk: Int)
        private data class HardwareFeatures(
            val colorOs: Boolean,
            val flashlight: Boolean,
            val hotspot: Boolean,
            val nfc: Boolean,
            val nightLight: Boolean,
        )
        private var hardwareCache: Pair<HardwareKey, HardwareFeatures>? = null

        /** 不持有 Context；不同 PackageManager / Resources（含 Robolectric sandbox）分别探测。 */
        @Synchronized
        private fun hardware(context: Context): HardwareFeatures {
            val manager = context.packageManager
            val resources = context.resources
            val key = HardwareKey(System.identityHashCode(manager), System.identityHashCode(resources),
                Build.MANUFACTURER, Build.VERSION.SDK_INT)
            hardwareCache?.takeIf { it.first == key }?.let { return it.second }
            return HardwareFeatures(
                colorOs = isColorOsDevice(),
                flashlight = manager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH),
                hotspot = Build.VERSION.SDK_INT >= 36,
                nfc = manager.hasSystemFeature(PackageManager.FEATURE_NFC),
                nightLight = resources.getIdentifier("config_nightDisplayAvailable", "bool", "android")
                    .let { it != 0 && resources.getBoolean(it) },
            ).also { hardwareCache = key to it }
        }

        fun isColorOsDevice(): Boolean = Build.MANUFACTURER.lowercase(Locale.ROOT) in
            setOf("oppo", "oneplus", "realme")

        fun capture(context: Context): AgentToolCapabilities {
            val root = RootAccess.state.value
            val hardware = hardware(context)
            return AgentToolCapabilities(
                rootAvailable = root.isGranted,
                rootState = root,
                lsposedAvailable = EtaApp.serviceInstance != null,
                accessibilityAvailable = AgentAccessibilityService.isAvailable(),
                accessibilityRecoveryAvailable = EtaApp.serviceInstance != null &&
                    AccessibilityProtectionClient.isEnabled(context),
                notificationsAllowed = AgentNotificationHistoryService.isEnabled(context),
                usageAllowed = AgentPersonalContextTools.hasUsageAccess(context),
                locationAllowed = context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED &&
                    (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED),
                colorOs = hardware.colorOs,
                calendarReadable = io.github.mangi.eta.agent.device.CalendarPermissions.granted(context, false),
                calendarWritable = io.github.mangi.eta.agent.device.CalendarPermissions.granted(context, true),
                flashlightAvailable = hardware.flashlight,
                hotspotAvailable = hardware.hotspot,
                nfcAvailable = hardware.nfc,
                nightLightAvailable = hardware.nightLight,
            )
        }
    }
}

/** 单次 run 共享；调用频率不变，仅合并短时间内的权限/能力探测。 */
internal class AgentToolCapabilitiesCache(
    private val capture: () -> AgentToolCapabilities,
    private val rootGranted: () -> Boolean = { RootAccess.state.value.isGranted },
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val ttlMs: Long = 2_000,
) : () -> AgentToolCapabilities {
    private var snapshot: AgentToolCapabilities? = null
    private var capturedAt = 0L

    @Synchronized
    override fun invoke(): AgentToolCapabilities {
        val time = now()
        snapshot?.takeIf { it.rootAvailable == rootGranted() && time - capturedAt in 0 until ttlMs }
            ?.let { return it }
        return capture().also {
            snapshot = it
            capturedAt = time
        }
    }

    @Synchronized
    fun invalidate() {
        snapshot = null
    }
}
