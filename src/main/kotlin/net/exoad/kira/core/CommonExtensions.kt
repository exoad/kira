package net.exoad.kira.core

fun Char.isHexChar(): Boolean
{
    return this.isDigit() || (this in 'A'..'F') || (this in 'a'..'f')
}

// https://stackoverflow.com/a/45504997/14501343
fun <K, V> Map<K, V>.reversed(): HashMap<V, K>
{
    return HashMap<V, K>().also { newMap ->
        entries.forEach { newMap.put(it.value, it.key) }
    }
}
