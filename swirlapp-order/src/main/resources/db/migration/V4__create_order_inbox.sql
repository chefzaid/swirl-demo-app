-- Inbox: IDs of result events already applied, so a redelivered or republished result is recognised.
CREATE TABLE IF NOT EXISTS order_inbox (
    event_id VARCHAR(255) PRIMARY KEY,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS order_inbox_processed_at_idx ON order_inbox (processed_at);
