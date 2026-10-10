package com.lanfileserver.app

import java.io.IOException

internal object UniqueFileName {
    fun choose(requestedName: String, existingNames: Set<String>): String {
        if (requestedName !in existingNames) return requestedName

        val dot = requestedName.lastIndexOf('.')
        val hasExtension = dot > 0 && dot < requestedName.lastIndex
        val base = if (hasExtension) requestedName.substring(0, dot) else requestedName
        val extension = if (hasExtension) requestedName.substring(dot) else ""
        for (index in 1..9_999) {
            val candidate = "$base ($index)$extension"
            if (candidate !in existingNames) return candidate
        }
        throw IOException("同名文件过多")
    }
}
