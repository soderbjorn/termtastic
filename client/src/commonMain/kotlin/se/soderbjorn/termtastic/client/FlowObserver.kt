package se.soderbjorn.termtastic.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class FlowObserver {
    private val scope: CoroutineScope = MainScope()

    fun <T> observe(flow: Flow<T>, onChange: (T) -> Unit) {
        scope.launch {
            flow.collect { value ->
                onChange(value)
            }
        }
    }

    fun clear() {
        scope.cancel()
    }
}
