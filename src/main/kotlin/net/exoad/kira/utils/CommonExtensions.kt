package net.exoad.kira.utils

fun Char.isHexChar(): Boolean
{
    return this.isDigit() || (this in 'A'..'F') || (this in 'a'..'f')
}
