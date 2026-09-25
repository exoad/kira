package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.source.SourceContext

/**
 * What every part of the C++ emitter sees while one module is emitted. The
 * plumbing defines it; the emitter implements it (`CppEmitContextImpl`) and
 * extends it with the typed program and the parts (type spelling,
 * expressions, statements, lambdas, classes, generics, externs, bindings).
 */
interface CppEmitContext {
    val options: CppOptions
    val module: SourceContext
    val names: CppNames
    val layout: CppModuleLayout

    /** Adds `#include "<header>"` to the module's header, once. */
    fun includeInHeader(header: String)

    /** Adds `#include "<header>"` to the module's source, once. */
    fun includeInSource(header: String)

    /** Reports `code` (`cpp.unsupported`, `cpp.macro-name`, ...) at `node`. */
    fun diag(node: ASTNode, code: String, message: String)

    /**
     * `#line 42 "firmware/pilot/src/proto.kira"` for `node`, or null when
     * line directives are off or the node has no recorded origin.
     */
    fun lineDirective(node: ASTNode): String?

    /** A synthesized name: `t0_`, `i_end`, `c_k`. Never a Kira name. */
    fun fresh(stem: String): String
}
