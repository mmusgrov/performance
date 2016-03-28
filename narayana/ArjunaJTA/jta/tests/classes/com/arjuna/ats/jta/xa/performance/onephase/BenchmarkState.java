package com.arjuna.ats.jta.xa.performance.onephase;

import com.arjuna.ats.arjuna.common.arjPropertyManager;

import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

@State(Scope.Thread)
public class BenchmarkState {
    protected javax.transaction.TransactionManager tm;
    protected SampleOnePhaseResource resource;

    public BenchmarkState() {
        arjPropertyManager.getCoordinatorEnvironmentBean().setCommitOnePhase(false);

        this.resource = new SampleOnePhaseResource(SampleOnePhaseResource.ErrorType.none, false);
        this.tm = com.arjuna.ats.jta.TransactionManager.transactionManager();
    }

};