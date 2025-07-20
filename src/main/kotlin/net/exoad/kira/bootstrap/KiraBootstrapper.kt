package net.exoad.kira.bootstrap

import net.exoad.kira.bootstrap.lang.KAny
import net.exoad.kira.compiler.front.Token
import net.exoad.kira.compiler.front.elements.DataLiteral
import kotlin.reflect.KClass

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class KiraIntrinsicFunction(
    val rep: String,
    val replace: String,
    val parameters: Array<KClass<out KAny>>,
    val returnType: KClass<out Any>
)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class KiraClass(val rep: String)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class KiraSubClass(val parent: KClass<out KAny>)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class KiraNoTailRecursion

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class KiraCannotInstantiate

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class KiraSugarLiteralInstantiate<E, T : DataLiteral<E>>(val astNodeRep: KClass<T>)

@Target(AnnotationTarget.LOCAL_VARIABLE)
@Retention(AnnotationRetention.RUNTIME)
annotation class KiraPragmaUseStackInliner

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class KiraCoerceLiteralToken(val token: Array<Token.Type>)

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
annotation class KiraRefLiteral(val target: KClass<*>)

@Target(AnnotationTarget.EXPRESSION)
@Retention(AnnotationRetention.SOURCE)
annotation class KiraVectorizeNoExtract(val intVector: Boolean)