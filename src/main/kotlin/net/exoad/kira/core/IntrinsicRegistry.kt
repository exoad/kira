package net.exoad.kira.core

import net.exoad.kira.core.intrinsics.DeclIntrinsic
import net.exoad.kira.core.intrinsics.ExternIntrinsic
import net.exoad.kira.core.intrinsics.GlobalIntrinsic
import net.exoad.kira.core.intrinsics.MagicIntrinsic
import net.exoad.kira.core.intrinsics.OpaqueIntrinsic

object IntrinsicRegistry {
    private val intrinsics: Map<String, CompilerIntrinsic> = listOf(
        DeclIntrinsic,
        GlobalIntrinsic,
        MagicIntrinsic,
        OpaqueIntrinsic,
        ExternIntrinsic,
    ).associateBy { it.name }

    fun find(name: String): CompilerIntrinsic? {
        return intrinsics[name]
    }
}