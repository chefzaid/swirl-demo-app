-- Scheduler locks: one row per cluster-wide job, so a job runs on one replica at a time (ShedLock).
CREATE TABLE IF NOT EXISTS order_scheduler_lock (
    name VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMP NOT NULL,
    locked_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);
