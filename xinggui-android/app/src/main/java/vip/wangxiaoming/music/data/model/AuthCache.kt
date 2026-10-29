package vip.wangxiaoming.music.data.model

/**
 * 服务端下发的账户信息缓存（非机密，可明文存 DataStore）。
 * accessToken / refreshToken 不放这里，见 TokenStore。
 */
data class AuthCache(
    val username: String = "",
    val vipType: String = "free",
    /** ISO-8601 或服务端原样字符串；仅用于 UI 展示「到期时间」 */
    val expireAt: String = "",
    val deviceCount: Int = 0,
    val maxDevices: Int = 2,
    val deviceId: String = "",
) {
    val isLoggedIn: Boolean get() = username.isNotEmpty()
}