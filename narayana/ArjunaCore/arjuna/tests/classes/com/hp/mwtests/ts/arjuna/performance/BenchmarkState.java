package com.hp.mwtests.ts.arjuna.performance;


import com.hp.mwtests.ts.arjuna.resources.BasicRecord;
import com.hp.mwtests.ts.arjuna.resources.SyncRecord;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

@State(Scope.Thread)
public class BenchmarkState {
    protected BasicRecord record1 = new BasicRecord();
    protected BasicRecord record2 = new BasicRecord();
    protected SyncRecord syncRecord = new  SyncRecord();
};