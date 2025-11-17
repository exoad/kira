package net.exoad.kira.core

import net.exoad.kira.core.intrinsics.DeclIntrinsic
import net.exoad.kira.core.intrinsics.GlobalIntrinsic
import net.exoad.kira.core.intrinsics.MagicIntrinsic

object IntrinsicRegistry {
    private val intrinsics: Map<String, CompilerIntrinsic> = listOf(
        DeclIntrinsic,
        GlobalIntrinsic,
        MagicIntrinsic
    ).associateBy { it.name }

    fun find(name: String): CompilerIntrinsic? {
        return intrinsics[name]
    }
}