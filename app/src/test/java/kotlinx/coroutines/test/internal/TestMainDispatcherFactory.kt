package kotlinx.coroutines.test.internal

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.internal.MainDispatcherFactory
import kotlin.coroutines.CoroutineContext

/**
 * Le « fil principal » des tests sur PC (il n'y a pas d'Android) : ce que les écrans lancent (viewModelScope) s'exécute
 * tout de suite, dans le test. kotlinx.coroutines, quand il voit les classes d'Android, ne cherche que deux noms : celui
 * d'Android (qui échoue sans téléphone) et celui-ci, celui de kotlinx-coroutines-test (que le projet n'a pas).
 */
@OptIn(InternalCoroutinesApi::class)
class TestMainDispatcherFactory : MainDispatcherFactory {
    override val loadPriority: Int = Int.MAX_VALUE
    override fun createDispatcher(allFactories: List<MainDispatcherFactory>): MainCoroutineDispatcher = Immediat

    private object Immediat : MainCoroutineDispatcher() {
        override val immediate: MainCoroutineDispatcher get() = this
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = false
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
    }
}
