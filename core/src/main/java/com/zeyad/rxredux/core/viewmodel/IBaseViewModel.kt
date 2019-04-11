package com.zeyad.rxredux.core.viewmodel

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import android.os.Parcelable
import android.util.Log
import com.jakewharton.rx.ReplayingShare
import com.zeyad.rxredux.core.*
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.functions.BiFunction
import io.reactivex.schedulers.Schedulers

interface IBaseViewModel<R, S : Parcelable, E> {

    var disposable: CompositeDisposable

    fun reducer(newResult: R, event: BaseEvent<*>, currentStateBundle: S): S

    fun mapEventsToActions(event: BaseEvent<*>): Flowable<*>

    fun errorMessageFactory(throwable: Throwable, event: BaseEvent<*>): Message =
            StringMessage(throwable.localizedMessage)

    fun middleware(it: PModel<*>) {
        Log.d("IBaseViewModel", "PModel: $it")
        if (it is ErrorEffect) Log.e("IBaseViewModel", "Error", it.error)
    }

    fun store(events: Observable<BaseEvent<*>>, initialState: S): LiveData<PModel<*>> {
        val pModels = events.toFlowable(BackpressureStrategy.BUFFER)
                .toResult()
                .publish()
                .autoConnect(0)
        val liveState = MutableLiveData<PModel<*>>()
        val states = stateStream(pModels as Flowable<Result<R>>, initialState)
        val effects = effectStream(pModels as Flowable<Result<E>>)
        disposable.add(Flowable.merge(states, effects)
                .doAfterNext { middleware(it) }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { t: PModel<*> -> liveState.value = t })
        return liveState
    }

    private fun stateStream(pModels: Flowable<Result<R>>, initialState: S): Flowable<PModel<*>> {
        var latestState: PModel<S>? = null
        return pModels.filter { it is SuccessResult }
                .map { it as SuccessResult }
                .scan<PModel<S>>(SuccessState(initialState), stateReducer())
                .map {
                    if (it == latestState) {
                        EmptySuccessState()
                    } else {
                        latestState = it
                        it
                    }
                }
                .compose(ReplayingShare.instance())
    }

    private fun effectStream(pModels: Flowable<Result<E>>): Flowable<PModel<*>> {
        return pModels.filter { it is EffectResult }
                .map { it as EffectResult }
                .scan<PModel<*>>(EmptySuccessEffect(), effectReducer())
                .filter { t: PModel<*> -> t !is EmptySuccessEffect }
                .distinctUntilChanged()
    }

    private fun Flowable<BaseEvent<*>>.toResult(): Flowable<Result<*>> {
        return observeOn(Schedulers.computation())
                .concatMap { event ->
                    Log.d("IBaseViewModel", "Event: $event")
                    Flowable.just(event)
                            .concatMap { mapEventsToActions(it) }
                            .map<Result<*>> {
                                if (it is EffectResult<*>) it
                                else SuccessResult(it, event)
                            }.onErrorReturn { ErrorEffectResult(it, event) }
                            .startWith(LoadingEffectResult(event))
                }
                .distinctUntilChanged()
    }

    private fun stateReducer(): BiFunction<PModel<S>, SuccessResult<R>, PModel<S>> =
            BiFunction { currentUIModel, result ->
                SuccessState(reducer(result.bundle, result.event, currentUIModel.bundle), result.event)
            }

    private fun effectReducer(): BiFunction<PModel<*>, in EffectResult<E>, PModel<*>> =
            BiFunction { currentUIModel, result ->
                result.run {
                    when {
                        this is LoadingEffectResult -> LoadingEffect(currentUIModel.bundle, event)
                        this is SuccessEffectResult -> successEffect(currentUIModel as PEffect<E>)
                        this is ErrorEffectResult -> errorEffect(currentUIModel as PEffect<E>)
                        else -> currentUIModel.throwIllegalStateException(result)
                    }
                }
            }

    private fun SuccessEffectResult<E>.successEffect(currentUIModel: PEffect<E>): SuccessEffect<E> =
            when (currentUIModel) {
                is LoadingEffect -> SuccessEffect(bundle, event)
                is EmptySuccessEffect, is SuccessEffect, is ErrorEffect -> currentUIModel.throwIllegalStateException(this)
            }

    private fun ErrorEffectResult.errorEffect(currentUIModel: PEffect<E>): ErrorEffect<E> =
            when (currentUIModel) {
                is LoadingEffect -> ErrorEffect(error, errorMessageFactory(error, event), currentUIModel.bundle, event)
                is EmptySuccessEffect, is SuccessEffect, is ErrorEffect -> currentUIModel.throwIllegalStateException(this)
            }

    @Throws(IllegalStateException::class)
    private fun PModel<*>.throwIllegalStateException(result: Result<*>): Nothing =
            throw IllegalStateException("Can not reduce from $this to ${this::class.java.simpleName} with $result")

}
