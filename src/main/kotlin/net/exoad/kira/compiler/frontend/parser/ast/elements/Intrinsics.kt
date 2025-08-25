package net.exoad.kira.compiler.frontend.parser.ast.elements

import net.exoad.kira.core.Builtin
import net.exoad.kira.source.SourceLocation

/**
 * Compile time injection of certain symbols
 *
 * Mostly just a way to inject functions and symbols that cannot be easily represented at source level like operator overloading
 * or potentially platform independent functions that require compile time information like the line number the intrinsic was mentioned.
 *
 * It is similar to preprocessor directives, but they are evaluated after it and handles additional cases that may require static analysis
 *
 * - See [Builtin.Intrinsics] for actual available intrinsics
 */
data class Intrinsic(val intrinsicKey: Builtin.Intrinsics, val sourceLocation: SourceLocation)
