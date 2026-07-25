package com.lanfileserver.app

object SafePath {
    fun segments(path: String): List<String> {
        if (path.indexOf('\u0000') >= 0) {
            throw IllegalArgumentException("路径包含无效字符")
        }

        val trimmed = path.trim('/')
        if (trimmed.isEmpty()) return emptyList()

        return trimmed.split('/').map { segment ->
            if (segment.isEmpty() || segment == "." || segment == "..") {
                throw IllegalArgumentException("路径无效")
            }
            segment
        }
    }

    fun normalize(path: String): String = segments(path).joinToString("/")

    fun join(parent: String, child: String): String {
        val safeChild = sanitizeName(child)
        val safeParent = normalize(parent)
        return if (safeParent.isEmpty()) safeChild else "$safeParent/$safeChild"
    }

    fun parent(path: String): String =
        segments(path).dropLast(1).joinToString("/")

    fun sanitizeName(input: String): String {
        val cleaned = buildString {
            input.trim().forEach { char ->
                when {
                    char.code < 32 || char.code == 127 -> Unit
                    char == '/' || char == '\\' -> append('_')
                    else -> append(char)
                }
            }
        }.trim().take(180)

        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
            throw IllegalArgumentException("名称无效")
        }
        return cleaned
    }
}

