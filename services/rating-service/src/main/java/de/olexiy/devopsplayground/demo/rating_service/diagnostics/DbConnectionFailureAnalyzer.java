package de.olexiy.devopsplayground.demo.rating_service.diagnostics;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

import java.sql.SQLException;

public class DbConnectionFailureAnalyzer extends AbstractFailureAnalyzer<BeanCreationException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, BeanCreationException cause) {
        Throwable root = findCause(cause, SQLException.class);
        String msg = root != null ? root.getMessage() : cause.getMessage();
        if (msg == null || (!msg.contains("Connection refused") && !msg.contains("refused"))) {
            return null;
        }
        return new FailureAnalysis(
            "Cannot connect to PostgreSQL after retries.",
            """
            Check that the PostgreSQL pod is running and port-forward is active:
              kubectl get pods -n databases
              kubectl port-forward svc/target-db -n databases 5433:5432
            Or start local stack:  cd services && docker compose up -d
            Verify credentials in application.yaml (default: appuser / apppassword)
            """,
            cause
        );
    }
}
