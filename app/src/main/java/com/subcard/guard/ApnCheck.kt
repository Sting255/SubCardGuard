package com.subcard.guard

/**
 * 副卡 APN 防线自检。
 *
 * 普通 App 读不到 carriers 表（READ_APN_SETTINGS 是签名级），这里借已授权的 su
 * 执行 content query，确认副卡专属 APN 仍处于 carrier_enabled=0。
 *
 * 只读，不写。发现异常时给出 adb 修复命令，由用户决定是否执行。
 */
object ApnCheck {

    data class Result(
        val ok: Boolean?,
        val detail: String,
        val fixCmd: String? = null,
    )

    /** edited 字段里代表"删除类状态"的取值，系统更新时可能被清理 */
    private const val EDITED_DELETED = 4

    fun run(subId: Int): Result {
        if (subId <= 0) {
            return Result(null, "副卡不在位，无法检查")
        }
        val out = execSu(
            "content query --uri content://telephony/carriers " +
                "--projection _id:name:carrier_enabled:edited:sub_id --where \"sub_id=$subId\"",
        ) ?: return Result(
            null,
            "无法读取 APN：需要 root 授权（KernelSU 里给「副卡卫士」授权后重试）",
        )

        if (out.isBlank() || out.contains("No result", ignoreCase = true)) {
            return Result(
                false,
                "副卡没有专属 APN 条目，系统可能已回退到通用 APN，防线存在缺口",
                "adb shell content query --uri content://telephony/carriers --where \"sub_id=$subId\"",
            )
        }

        val rows = out.lineSequence()
            .filter { it.trim().startsWith("Row:") }
            .mapNotNull { parseRow(it) }
            .toList()

        if (rows.isEmpty()) {
            return Result(null, "APN 输出无法解析，请手动确认")
        }

        val enabled = rows.filter { it.enabled != 0 }
        if (enabled.isNotEmpty()) {
            val first = enabled.first()
            return Result(
                false,
                "有 ${enabled.size} 条 APN 处于启用状态：" +
                    enabled.joinToString("、") { "${it.name}(_id=${it.id})" } +
                    "。副卡数据开关一旦被打开就可能上网",
                "adb shell content update --uri content://telephony/carriers " +
                    "--where \"_id=${first.id}\" --bind carrier_enabled:i:0",
            )
        }

        val deleted = rows.filter { it.edited == EDITED_DELETED }
        val detail = buildString {
            append("${rows.size} 条 APN 均已禁用：")
            rows.forEach { append("\n      _id=${it.id} ${it.name} enabled=${it.enabled} edited=${it.edited}") }
            if (deleted.isNotEmpty()) {
                append(
                    "\n      ⚠️ 存在 edited=4 的条目（删除类状态），系统更新时可能被清理，" +
                        "届时副卡会回退通用 APN，请留意",
                )
            }
        }
        return Result(true, detail)
    }

    private data class Row(val id: Int, val name: String, val enabled: Int, val edited: Int)

    private fun parseRow(line: String): Row? {
        val id = Regex("_id=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val name = Regex("name=([^,]*)").find(line)?.groupValues?.get(1)?.trim().orEmpty()
        val enabled = Regex("carrier_enabled=(-?\\d)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val edited = Regex("edited=(\\d)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        return Row(id, name, enabled, edited)
    }

    private fun execSu(cmd: String): String? = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val err = p.errorStream.bufferedReader().use { it.readText() }
        p.waitFor()
        if (out.isBlank() && err.isNotBlank()) null else out
    } catch (e: Exception) {
        null
    }
}
