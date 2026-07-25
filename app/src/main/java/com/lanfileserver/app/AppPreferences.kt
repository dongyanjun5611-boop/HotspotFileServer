package com.lanfileserver.app

import android.content.Context
import androidx.core.content.edit
import java.security.SecureRandom

object AppPreferences {
    private const val PREFS_NAME = "hotspot_file_server"
    private const val KEY_TREE_URI = "tree_uri"
    private const val KEY_PORT = "port"
    private const val KEY_PIN = "pin"
    private const val KEY_REMOTE_ENABLED = "remote_enabled"
    private const val KEY_REMOTE_NETWORK_POLICY = "remote_network_policy"
    private const val KEY_APPROVED_METERED_JOB = "approved_metered_job"
    private const val KEY_COMPLETED_JOB = "completed_job"
    private const val KEY_COMPLETED_FILE_NAME = "completed_file_name"
    private const val KEY_COMPLETED_FILE_SIZE = "completed_file_size"
    private const val KEY_PENDING_CANCELED_JOB = "pending_canceled_job"

    const val DEFAULT_PORT = 8080

    fun treeUri(context: Context): String? =
        prefs(context).getString(KEY_TREE_URI, null)

    fun port(context: Context): Int =
        prefs(context).getInt(KEY_PORT, DEFAULT_PORT)

    fun pin(context: Context): String {
        val stored = prefs(context).getString(KEY_PIN, null)
        if (!stored.isNullOrBlank()) return stored

        val generated = generatePin()
        prefs(context).edit { putString(KEY_PIN, generated) }
        return generated
    }

    fun saveTreeUri(context: Context, uri: String) {
        prefs(context).edit { putString(KEY_TREE_URI, uri) }
    }

    fun saveConfiguration(context: Context, port: Int, pin: String) {
        prefs(context).edit {
            putInt(KEY_PORT, port)
            putString(KEY_PIN, pin)
        }
    }

    fun remoteEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REMOTE_ENABLED, false)

    fun saveRemoteEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_REMOTE_ENABLED, enabled) }
    }

    fun remoteNetworkPolicy(context: Context): RemoteNetworkPolicy {
        val value = prefs(context).getString(
            KEY_REMOTE_NETWORK_POLICY,
            RemoteNetworkPolicy.ASK.name,
        )
        return runCatching { RemoteNetworkPolicy.valueOf(value.orEmpty()) }
            .getOrDefault(RemoteNetworkPolicy.ASK)
    }

    fun saveRemoteNetworkPolicy(context: Context, policy: RemoteNetworkPolicy) {
        prefs(context).edit { putString(KEY_REMOTE_NETWORK_POLICY, policy.name) }
    }

    fun approvedMeteredJob(context: Context): String? =
        prefs(context).getString(KEY_APPROVED_METERED_JOB, null)

    fun approveMeteredJob(context: Context, jobId: String) {
        prefs(context).edit { putString(KEY_APPROVED_METERED_JOB, jobId) }
    }

    fun clearApprovedMeteredJob(context: Context, jobId: String) {
        if (approvedMeteredJob(context) == jobId) {
            prefs(context).edit { remove(KEY_APPROVED_METERED_JOB) }
        }
    }

    fun markLocallyCompleted(
        context: Context,
        jobId: String,
        fileName: String,
        fileSize: Long,
    ) {
        prefs(context).edit {
            putString(KEY_COMPLETED_JOB, jobId)
            putString(KEY_COMPLETED_FILE_NAME, fileName)
            putLong(KEY_COMPLETED_FILE_SIZE, fileSize)
        }
    }

    fun locallyCompleted(context: Context, jobId: String): Pair<String, Long>? {
        if (prefs(context).getString(KEY_COMPLETED_JOB, null) != jobId) return null
        val fileName = prefs(context).getString(KEY_COMPLETED_FILE_NAME, null)
            ?: return null
        return fileName to prefs(context).getLong(KEY_COMPLETED_FILE_SIZE, 0L)
    }

    fun clearLocallyCompleted(context: Context, jobId: String) {
        if (prefs(context).getString(KEY_COMPLETED_JOB, null) == jobId) {
            prefs(context).edit {
                remove(KEY_COMPLETED_JOB)
                remove(KEY_COMPLETED_FILE_NAME)
                remove(KEY_COMPLETED_FILE_SIZE)
            }
        }
    }

    fun pendingCanceledJob(context: Context): String? =
        prefs(context).getString(KEY_PENDING_CANCELED_JOB, null)

    fun requestCancelJob(context: Context, jobId: String) {
        prefs(context).edit { putString(KEY_PENDING_CANCELED_JOB, jobId) }
    }

    fun clearPendingCanceledJob(context: Context, jobId: String) {
        if (pendingCanceledJob(context) == jobId) {
            prefs(context).edit { remove(KEY_PENDING_CANCELED_JOB) }
        }
    }

    fun generatePin(): String =
        (SecureRandom().nextInt(900_000) + 100_000).toString()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

enum class RemoteNetworkPolicy {
    UNMETERED_ONLY,
    ASK,
    ALWAYS,
}
