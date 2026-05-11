package com.gromozeka.presentation.services

import com.gromozeka.presentation.ui.state.UIState

interface UIStateStore {
    fun load(): UIState?
    fun save(state: UIState)
}

class InMemoryUIStateStore : UIStateStore {
    private var state: UIState? = null

    override fun load(): UIState? = state

    override fun save(state: UIState) {
        this.state = state
    }
}
