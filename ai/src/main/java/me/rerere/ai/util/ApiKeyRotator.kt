package me.rerere.ai.util

import android.content.Context
import android.content.SharedPreferences

/**
 * API Key顺序轮询工具类
 * 实现类似cherry-studio的顺序轮询机制
 */
object ApiKeyRotator {
    private const val PREF_NAME = "api_key_rotator"
    private const val KEY_PREFIX = "last_key_index_"
    
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * 获取下一个API Key
     * 
     * @param context 上下文
     * @param providerId 提供商ID，用于区分不同提供商的API Key
     * @param apiKeyString 包含多个API Key的字符串，以逗号分隔
     * @return 按顺序选择的下一个API Key
     */
    fun getNextApiKey(context: Context, providerId: String, apiKeyString: String): String {
        // 如果API Key字符串为空或只有一个Key，直接返回
        val keys = apiKeyString.split(",").filter { it.isNotBlank() }
        if (keys.isEmpty()) return apiKeyString
        if (keys.size == 1) return keys[0]
        
        // 获取上次使用的索引
        val prefKey = "$KEY_PREFIX$providerId"
        val prefs = getPreferences(context)
        val lastIndex = prefs.getInt(prefKey, -1)
        
        // 计算下一个索引
        val nextIndex = (lastIndex + 1) % keys.size
        
        // 保存新的索引
        prefs.edit().putInt(prefKey, nextIndex).apply()
        
        // 返回对应的API Key
        return keys[nextIndex]
    }
}
