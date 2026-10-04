package com.trailnav.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigationPreferencesTest {
    @Test
    fun imuCollectionDefaultsOffAndSavedValuesRoundTrip() {
        val context = InMemoryPreferencesContext()

        assertFalse(NavigationPreferences.imuCollectEnabled(context))
        NavigationPreferences.saveImuCollectEnabled(context, true)
        assertTrue(NavigationPreferences.imuCollectEnabled(context))
        assertEquals(true, context.preferences.getAll()["imu_collect_enabled"])

        NavigationPreferences.saveImuCollectEnabled(context, false)
        assertFalse(NavigationPreferences.imuCollectEnabled(context))
    }

    @Test
    fun resetDefaultsDraftTurnsImuCollectionOffOnlyWhenSaved() {
        val context = InMemoryPreferencesContext()
        NavigationPreferences.saveImuCollectEnabled(context, true)
        val eventDraft = BooleanArray(GuideEvent.values().size) { true }

        val defaults = NavigationSettingsUi.resetToDefaults(eventDraft)
        assertTrue(NavigationPreferences.imuCollectEnabled(context))

        val cancelled = NavigationSettingsUi.resolveDialogSelection(
            eventChecked = eventDraft,
            onDemandEnabled = defaults.onDemandEnabled,
            imuCollectEnabled = defaults.imuCollectEnabled,
            accepted = false,
        )
        assertNull(cancelled)
        assertTrue(NavigationPreferences.imuCollectEnabled(context))

        val accepted = requireNotNull(
            NavigationSettingsUi.resolveDialogSelection(
                eventChecked = eventDraft,
                onDemandEnabled = defaults.onDemandEnabled,
                imuCollectEnabled = defaults.imuCollectEnabled,
                accepted = true,
            ),
        )
        NavigationPreferences.saveImuCollectEnabled(context, accepted.imuCollectEnabled)

        assertFalse(NavigationPreferences.imuCollectEnabled(context))
    }
}

private class InMemoryPreferencesContext : ContextWrapper(null) {
    val preferences = InMemorySharedPreferences()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
}

private class InMemorySharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(name: String, defValue: String?): String? = values[name] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(name: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[name] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(name: String, defValue: Int): Int = values[name] as? Int ?: defValue
    override fun getLong(name: String, defValue: Long): Long = values[name] as? Long ?: defValue
    override fun getFloat(name: String, defValue: Float): Float = values[name] as? Float ?: defValue
    override fun getBoolean(name: String, defValue: Boolean): Boolean = values[name] as? Boolean ?: defValue
    override fun contains(name: String): Boolean = values.containsKey(name)
    override fun edit(): SharedPreferences.Editor = InMemoryEditor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class InMemoryEditor : SharedPreferences.Editor {
        private val updates = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(name: String, value: String?): SharedPreferences.Editor = update(name, value)
        override fun putStringSet(name: String, values: MutableSet<String>?): SharedPreferences.Editor =
            update(name, values?.toMutableSet())
        override fun putInt(name: String, value: Int): SharedPreferences.Editor = update(name, value)
        override fun putLong(name: String, value: Long): SharedPreferences.Editor = update(name, value)
        override fun putFloat(name: String, value: Float): SharedPreferences.Editor = update(name, value)
        override fun putBoolean(name: String, value: Boolean): SharedPreferences.Editor = update(name, value)
        override fun remove(name: String): SharedPreferences.Editor = apply {
            updates.remove(name)
            removals += name
        }
        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
        override fun commit(): Boolean {
            if (clearAll) values.clear()
            removals.forEach(values::remove)
            values.putAll(updates)
            return true
        }
        override fun apply() {
            commit()
        }

        private fun update(name: String, value: Any?): SharedPreferences.Editor = apply {
            removals.remove(name)
            updates[name] = value
        }
    }
}
