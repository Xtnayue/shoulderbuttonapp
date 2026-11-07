package com.shoulderbutton.mapper.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.shoulderbutton.mapper.model.AppProfile
import com.shoulderbutton.mapper.model.KeyMapping

class ProfileRepository(context: Context) {

    private val sharedPrefs = context.getSharedPreferences("shoulder_profiles_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    init {
        // 确保初始化存在通用默认方案
        if (!hasProfile(AppProfile.DEFAULT_PACKAGE)) {
            saveProfile(AppProfile.createDefaultProfile())
        }
    }

    fun hasProfile(packageName: String): Boolean {
        return sharedPrefs.contains(KEY_PROFILE_PREFIX + packageName)
    }

    fun getAllProfiles(): List<AppProfile> {
        val list = mutableListOf<AppProfile>()
        val allEntries = sharedPrefs.all
        for ((key, value) in allEntries) {
            if (key.startsWith(KEY_PROFILE_PREFIX) && value is String) {
                try {
                    val profile = gson.fromJson(value, AppProfile::class.java)
                    if (profile != null) {
                        list.add(profile)
                    }
                } catch (_: Exception) {}
            }
        }
        if (list.none { it.packageName == AppProfile.DEFAULT_PACKAGE }) {
            val def = AppProfile.createDefaultProfile()
            saveProfile(def)
            list.add(def)
        }
        return list
    }

    fun getProfileForPackage(packageName: String): AppProfile {
        val json = sharedPrefs.getString(KEY_PROFILE_PREFIX + packageName, null)
        if (json != null) {
            try {
                val profile = gson.fromJson(json, AppProfile::class.java)
                if (profile != null) return profile
            } catch (_: Exception) {}
        }
        // 如果未为该包名单独配置，返回默认全局方案副本
        val defaultProfile = getDefaultProfile()
        return defaultProfile.copy(
            packageName = packageName,
            appName = packageName,
            mappings = defaultProfile.mappings.map { it.copy() }.toMutableList()
        )
    }

    fun getDefaultProfile(): AppProfile {
        val json = sharedPrefs.getString(KEY_PROFILE_PREFIX + AppProfile.DEFAULT_PACKAGE, null)
        if (json != null) {
            try {
                return gson.fromJson(json, AppProfile::class.java) ?: AppProfile.createDefaultProfile()
            } catch (_: Exception) {}
        }
        val def = AppProfile.createDefaultProfile()
        saveProfile(def)
        return def
    }

    fun saveProfile(profile: AppProfile) {
        val json = gson.toJson(profile)
        sharedPrefs.edit().putString(KEY_PROFILE_PREFIX + profile.packageName, json).apply()
    }

    fun deleteProfile(packageName: String) {
        if (packageName == AppProfile.DEFAULT_PACKAGE) return // 默认方案不可删
        sharedPrefs.edit().remove(KEY_PROFILE_PREFIX + packageName).apply()
    }

    fun saveMappings(packageName: String, mappings: List<KeyMapping>) {
        val profile = getProfileForPackage(packageName)
        profile.mappings.clear()
        profile.mappings.addAll(mappings)
        saveProfile(profile)
    }

    companion object {
        private const val KEY_PROFILE_PREFIX = "profile_"

        @Volatile
        private var instance: ProfileRepository? = null

        fun getInstance(context: Context): ProfileRepository {
            return instance ?: synchronized(this) {
                instance ?: ProfileRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
