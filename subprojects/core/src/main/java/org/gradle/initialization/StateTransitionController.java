/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.initialization;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

/**
 * Manages the transition between states of some object with mutable state. Adds validation to ensure that the object is in an expected state
 * and also that a transition cannot happen while another transition is currently happening (either by another thread or the thread that is currently
 * running a transition).
 */
public class StateTransitionController<T extends StateTransitionController.State> {
    private final Set<T> achievedStates = new HashSet<>();
    private T state;
    @Nullable
    private Thread owner;
    @Nullable
    private T currentTarget;

    public StateTransitionController(T initialState) {
        this.state = initialState;
    }

    /**
     * Transitions to the given "to" state. Does nothing if the "to" state has already been transitioned to (but not necessarily the current state).
     * Fails if the current state is not the given "from" state, or if some other transition is happening.
     */
    public void maybeTransition(T fromState, T toState, Runnable action) {
        Thread previousOwner = takeOwnership();
        try {
            if (achievedStates.contains(toState)) {
                return;
            }
            if (currentTarget != null) {
                throw new IllegalStateException("Cannot transition to state " + toState + " as already transitioning to state " + currentTarget + ".");
            }
            if (state != fromState) {
                throw new IllegalStateException("Can only transition to state " + toState + " from state " + fromState + " however currently in state " + state + ".");
            }
            currentTarget = toState;
            try {
                action.run();
                state = toState;
                achievedStates.add(toState);
            } finally {
                currentTarget = null;
            }
        } finally {
            releaseOwnership(previousOwner);
        }
    }

    @Nullable
    private Thread takeOwnership() {
        Thread currentThread = Thread.currentThread();
        synchronized (this) {
            if (owner == null) {
                owner = currentThread;
                return null;
            } else if (owner == currentThread) {
                return currentThread;
            } else {
                throw new IllegalStateException("Another thread is currently transitioning state.");
            }
        }
    }

    private void releaseOwnership(@Nullable Thread previousOwner) {
        synchronized (this) {
            owner = previousOwner;
        }
    }

    interface State {
    }
}
