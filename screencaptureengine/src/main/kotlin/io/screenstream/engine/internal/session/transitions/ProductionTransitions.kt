package io.screenstream.engine.internal.session.transitions

import io.screenstream.engine.internal.session.SessionAdmissions
import io.screenstream.engine.internal.session.SessionLifecycle

internal object ProductionTransitions {
    internal fun mayMaterialize(
        lifecycle: SessionLifecycle,
        admissions: SessionAdmissions,
        terminalCutoffApplied: Boolean,
    ): Boolean = lifecycle is SessionLifecycle.Active &&
            admissions == SessionAdmissions.Open &&
            !terminalCutoffApplied
}
