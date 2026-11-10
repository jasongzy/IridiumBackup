package com.xayah.libnative

object NativeLib {
    fun calculateSize(path: String, excludedChildren: Array<String>, excludedPrefixes: Array<String>): Long =
        measure(path, excludedChildren.joinToString("/"), excludedPrefixes.joinToString("/"))

    private external fun measure(path: String, children: String, prefixes: String): Long
}
