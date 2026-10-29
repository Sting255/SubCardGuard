package com.subcard.guard

/**
 * 副卡视频通话能力（siminfo.volte_vt_enabled）。
 *
 * 关掉它之后，主叫方推送的视频彩铃不会建立媒体流。
 * 2026-10-29 实测：关闭前同号码拨出产生 4.6MB IMS 下行，
 * 关闭后同样拨号只增长 580 字节（纯信令）。语音 VoLTE 不受影响。
 *
 * 读和写都走 su：写 siminfo 需要系统权限，普通 App 拿不到。
 */
object SimInfoRepo {

    /** true=已关，false=还开着，null=读不到（无 root 或查询失败） */
    fun isVideoTelephonyOff(subId: Int): Boolean? {
        if (subId <= 0) return null
        val out = execSu(
            "content query --uri content://telephony/siminfo " +
                "--projection _id:volte_vt_enabled --where \"_id=$subId\"",
        ) ?: return null
        val v = Regex("volte_vt_enabled=(-?\\d+)")
            .find(out)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return v == 0
    }

    fun disableVideoTelephony(subId: Int): Boolean {
        if (subId <= 0) return false
        return execSu(
            "content update --uri content://telephony/siminfo " +
                "--where \"_id=$subId\" --bind volte_vt_enabled:i:0",
        ) != null
    }

    private fun execSu(cmd: String): String? = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val err = p.errorStream.bufferedReader().use { it.readText() }
        val code = p.waitFor()
        if (code != 0 || err.isNotBlank()) null else out
    } catch (e: Exception) {
        null
    }
}
