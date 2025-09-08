package net.exoad.kira.utils

import kotlin.reflect.KClass

@RequiresOptIn(message = "This language feature is planned and has not been triaged. Do not use except for referencing.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
annotation class ObsoleteLanguageFeat