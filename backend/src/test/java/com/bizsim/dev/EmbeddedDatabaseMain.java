package com.bizsim.dev;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Development launcher: runs a real PostgreSQL from the test dependency, on a fixed port, so the
 * backend can be started and clicked through on a machine with no Docker and no PostgreSQL install.
 *
 * <p>Test-scoped on purpose — this class is never in the application jar. It is started by
 * {@code scripts/dev-db.sh}.
 *
 * <p>The database itself is not created here: the application's {@code DatabaseBootstrap} does that
 * on first start, which is exactly the production path we want to exercise.
 */
public final class EmbeddedDatabaseMain {

    private EmbeddedDatabaseMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5433;
        EmbeddedPostgres postgres = EmbeddedPostgres.builder().setPort(port).start();

        System.out.println("EMBEDDED_PG_READY url=" + postgres.getJdbcUrl("postgres", "postgres"));
        System.out.println("EMBEDDED_PG_READY user=postgres  (trust auth, any password accepted)");
        System.out.flush();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                postgres.close();
            } catch (Exception ignored) {
                // shutting down anyway
            }
        }));
        Thread.currentThread().join();
    }
}
