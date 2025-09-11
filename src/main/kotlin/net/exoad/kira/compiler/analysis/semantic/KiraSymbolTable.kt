package net.exoad.kira.compiler.analysis.semantic

class KiraSymbolTable : Iterable<KiraScopeFrame> {

    private val scopeStack = ArrayDeque<KiraScopeFrame>()

    init {
        enter(SemanticScope.MODULE) // global scope!
    }

    fun enter(kind: SemanticScope) {
        scopeStack.addFirst(KiraScopeFrame(kind))
    }

    fun totalSymbols(): Int {
        return scopeStack.sumOf { it.symbols.size }
    }

    fun clean() {
        scopeStack.clear()
    }

    fun exit() {
        if (scopeStack.isEmpty()) {
            throw IllegalStateException("Cannot exit scope: no scope to exit!")
        }
        scopeStack.removeFirst()
    }

    fun declare(identifier: String, symbol: SemanticSymbol): Boolean {
        val current = scopeStack.first().symbols
        if (current.containsKey(identifier)) return false
        current[identifier] = symbol
        return true
    }

    fun declareGlobal(identifier: String, symbol: SemanticSymbol): Boolean {
        for (scope in scopeStack) {
            if (scope.symbols.containsKey(identifier)) return false
        }
        scopeStack.last().symbols[identifier] = symbol
        return true
    }

    fun resolve(identifier: String): SemanticSymbol? {
        for (scope in scopeStack) {
            scope.symbols[identifier]?.let { return it }
        }
        return null
    }

    fun containsInCurrentScope(identifier: String): Boolean {
        return scopeStack.first().symbols.containsKey(identifier)
    }

    fun peek(): Map<String, SemanticSymbol> {
        return scopeStack.first().symbols.toMap()
    }

    fun where(): SemanticScope {
        return scopeStack.first().kind
    }

    override fun iterator(): Iterator<KiraScopeFrame> {
        return scopeStack.iterator()
    }
}