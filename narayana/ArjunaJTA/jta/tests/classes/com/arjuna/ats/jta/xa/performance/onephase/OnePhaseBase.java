package com.arjuna.ats.jta.xa.performance.onephase;

import com.arjuna.ats.arjuna.common.CoreEnvironmentBeanException;
import com.arjuna.ats.arjuna.common.arjPropertyManager;
import org.junit.BeforeClass;

public class OnePhaseBase {
    protected static BenchmarkState junitState;

    @BeforeClass
    public static void beforeClass() {
        try {
            arjPropertyManager.getCoreEnvironmentBean().setNodeIdentifier("node");
        } catch (CoreEnvironmentBeanException e) {
            e.printStackTrace();
        }

        junitState = new BenchmarkState();
    }

    protected void test(BenchmarkState state) throws Exception {
        state.tm.begin();

        state.tm.getTransaction().enlistResource(state.resource);

        state.tm.commit();
    }
}
