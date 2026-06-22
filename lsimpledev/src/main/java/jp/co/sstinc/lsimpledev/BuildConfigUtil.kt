package jp.co.sstinc.lsimpledev

fun buildConfigIsDemo() : Boolean {
    @Suppress("KotlinConstantConditions")
    return BuildConfig.FLAVOR == "demo"
}
