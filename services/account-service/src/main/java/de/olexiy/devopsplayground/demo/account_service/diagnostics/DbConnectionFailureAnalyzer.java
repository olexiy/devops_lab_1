package de.olexiy.devopsplayground.demo.account_service.diagnostics;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

import java.sql.SQLException;

public class DbConnectionFailureAnalyzer extends AbstractFailureAnalyzer<BeanCreationException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, BeanCreationException cause) {
        Throwable root = findCause(cause, SQLException.class);
        String msg = root != null ? root.getMessage() : cause.getMessage();
        if (msg == null || (!msg.contains("Communications link") && !msg.contains("refused"))) {
            return null;
        }
        return new FailureAnalysis(
            "Cannot connect to the database after 60 seconds of retries.",
            """
            Check that the database pod is running and port-forward is active:
              kubectl get pods -n databases
              kubectl port-forward svc/source-db -n databases 3307:3306
            Verify credentials in application.yaml (default: appuser / apppassword)
            NOTE: Run the port-forward command in PowerShell, not in bash — it must stay alive.
            """,
            cause
        );
    }
}
