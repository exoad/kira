package net.exoad.kira.utils

import java.lang.annotation.RetentionPolicy

@RequiresOptIn(message = "This language feature is planned and has not been triaged. Do not use except for referencing.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class PlannedLanguageFeat