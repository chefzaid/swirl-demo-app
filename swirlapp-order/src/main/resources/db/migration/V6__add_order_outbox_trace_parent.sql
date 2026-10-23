-- The trace context of the change that recorded each event, so the relay's Kafka record continues that trace.
ALTER TABLE order_outbox ADD COLUMN IF NOT EXISTS trace_parent VARCHAR(255);
