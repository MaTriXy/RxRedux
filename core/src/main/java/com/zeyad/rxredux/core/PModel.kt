package com.zeyad.rxredux.core

sealed class PModel<S> {
    abstract val event: BaseEvent<*>
    abstract val isLoading: Boolean
    abstract val bundle: S

    override fun toString() = "stateEvent: $event"
}

data class LoadingState<S>(override val bundle: S,
                           override val event: BaseEvent<*>,
                           override val isLoading: Boolean = true) : PModel<S>() {
    override fun toString() = "State: Loading, " + super.toString()
}

data class ErrorState<S>(val error: Throwable,
                         override val bundle: S,
                         override val event: BaseEvent<*>,
                         override val isLoading: Boolean = false) : PModel<S>() {
    override fun toString() = "State: Error, ${super.toString()}, Throwable: $error"
}

data class SuccessState<S>(override val bundle: S,
                           override val event: BaseEvent<*> = EmptyEvent,
                           override val isLoading: Boolean = false) : PModel<S>() {
    override fun toString() = "State: Success, ${super.toString()},  Bundle: $bundle"
}
