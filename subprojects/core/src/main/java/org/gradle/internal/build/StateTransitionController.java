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

package org.gradle.internal.build;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

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
    @Nullable
    private ExecutionResult<Void> failure;

    public StateTransitionController(T initialState) {
        this.state = initialState;
    }

    /**
     * Verifies that the current state is the given state. Ignores any transition in progress and failures of previous transitions.
     *
     * <p>You should try to not use this method, as it does not provide any thread safety for the code that follows the call.</p>
     */
    public void assertInState(T finished) {
        synchronized (this) {
            if (state != finished) {
                throw new IllegalStateException("Should be in state " + state + ".");
            }
        }
    }

    /**
     * Verifies that the current state is not in the given state. Ignores any transition in progress and failures of previous transitions.
     *
     * <p>You should try to not use this method, as it does not provide any thread safety for the code that follows the call.</p>
     */
    public void assertNotInState(T finished) {
        synchronized (this) {
            if (state == finished) {
                throw new IllegalStateException("Should not be in state " + state + ".");
            }
        }
    }

    /**
     * Calculates a value when the current state is not the given state.
     * Fails if the current state is the given state or if some transition is happening or a previous transition has failed.
     */
    public <S> S notInState(T state, Supplier<S> supplier) {
        Thread previousOwner = takeOwnership();
        try {
            assertNotFailed();
            if (currentTarget == state) {
                throw new IllegalStateException("Should not be in state " + state + " but is in state " + this.state + " and transitioning to " + currentTarget + ".");
            }
            if (this.state == state) {
                throw new IllegalStateException("Should not be in state " + state + ".");
            }
            return supplier.get();
        } finally {
            releaseOwnership(previousOwner);
        }
    }

    /**
     * Runs the given action when the current state is the given state.
     * Fails if the current state is not the given state or if some transition is happening or a previous transition has failed.
     */
    public void inState(T state, Runnable action) {
        Thread previousOwner = takeOwnership();
        try {
            assertNotFailed();
            if (currentTarget != null) {
                throw new IllegalStateException("Expected to be in state " + state + " but is in state " + this.state + " and transitioning to " + currentTarget + ".");
            }
            if (this.state != state) {
                throw new IllegalStateException("Expected to be in state " + state + " but is in state " + this.state + ".");
            }
            action.run();
        } finally {
            releaseOwnership(previousOwner);
        }
    }

    /**
     * Transitions to the given "to" state.
     * Fails if the current state is not the given "from" state or if some other transition is happening or a previous transition has failed.
     */
    public void transition(T fromState, T toState, Runnable action) {
        Thread previousOwner = takeOwnership();
        try {
            doTransition(fromState, toState, action);
        } finally {
            releaseOwnership(previousOwner);
        }
    }

    /**
     * Transitions to the given "to" state.
     * Fails if the current state is not the given "from" state or if some other transition is happening or a previous transition has failed.
     */
    public ExecutionResult<Void> tryTransition(T fromState, T toState, Supplier<ExecutionResult<Void>> action) {
        Thread previousOwner = takeOwnership();
        try {
            return doTransition(fromState, toState, action);
        } finally {
            releaseOwnership(previousOwner);
        }
    }

    /**
     * Transitions to the given "to" state. Does nothing if the "to" state has already been transitioned to (but is not necessarily the current state).
     * Fails if the current state is not the given "from" state or if some other transition is happening or a previous transition has failed.
     */
    public void transitionIfNotPreviously(T fromState, T toState, Runnable action) {
        Thread previousOwner = takeOwnership();
        try {
            if (achievedStates.contains(toState)) {
                return;
            }
            doTransition(fromState, toState, action);
        } finally {
            releaseOwnership(previousOwner);
        }
    }

    /**
     * Transitions to a final state, taking any failures from previous transitions and transforming them.
     */
    public ExecutionResult<Void> finish(T toState, Function<ExecutionResult<Void>, ExecutionResult<Void>> action) {
        Thread previousOwner = takeOwnership();
        try {
            if (state == toState) {
                return ExecutionResult.succeeded();
            }
            try {
                if (failure == null) {
                    return action.apply(ExecutionResult.succeeded());
                } else {
                    return action.apply(failure);
                }
            } finally {
                state = toState;
                achievedStates.add(toState);
            }
        } finally {
            releaseOwnership(previousOwner);
        }
    }

    private void doTransition(T fromState, T toState, Runnable action) {
        doTransition(fromState, toState, () -> {
            action.run();
            return ExecutionResult.succeeded();
        }).getValueOrRethrow();
    }

    private ExecutionResult<Void> doTransition(T fromState, T toState, Supplier<ExecutionResult<Void>> action) {
        assertNotFailed();
        if (currentTarget != null) {
            throw new IllegalStateException("Cannot transition to state " + toState + " as already transitioning to state " + currentTarget + ".");
        }
        if (state != fromState) {
            throw new IllegalStateException("Can only transition to state " + toState + " from state " + fromState + " however currently in state " + state + ".");
        }
        currentTarget = toState;
        try {
            ExecutionResult<Void> result;
            try {
                result = action.get();
            } catch (Throwable t) {
                result = ExecutionResult.failed(t);
            }
            if (!result.getFailures().isEmpty()) {
                failure = result;
            } else {
                state = toState;
                achievedStates.add(toState);
            }
            return result;
        } finally {
            currentTarget = null;
        }
    }

    private void assertNotFailed() {
        if (failure != null) {
            throw new IllegalStateException("Cannot use this object as a previous transition failed.", failure.getFailure());
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
                throw new IllegalStateException("Another thread is currently transitioning state from " + state + " to " + currentTarget + ".");
            }
        }
    }

    private void releaseOwnership(@Nullable Thread previousOwner) {
        synchronized (this) {
            owner = previousOwner;
        }
    }

    public interface State {
    }
}
