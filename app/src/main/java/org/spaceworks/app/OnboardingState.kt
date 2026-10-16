package org.spaceworks.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class OnboardingStateHolder(complete:Boolean,private val save:(Boolean)->Unit) {
    private val mutable=MutableStateFlow(complete)
    val completed=mutable.asStateFlow()
    fun finish(){save(true);mutable.value=true}
    fun replay(){save(false);mutable.value=false}
}
class AppearanceStateHolder(initialDark:Boolean,private val save:(Boolean)->Unit) {
    private val mutable=MutableStateFlow(initialDark)
    val dark=mutable.asStateFlow()
    fun setDark(value:Boolean){save(value);mutable.value=value}
}
