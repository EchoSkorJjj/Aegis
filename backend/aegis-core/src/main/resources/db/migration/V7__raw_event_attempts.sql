-- Robustness: distinguish a transient normalisation failure from a definitively bad payload.
--
-- The drainer used to mark every event processed, success or failure — which meant a momentary blip
-- (a DB deadlock, a lost upsert race) dropped an event for good. It now increments `attempts` on a
-- transient failure and leaves the event queued, retrying it on later runs and only giving up
-- (marking it processed) once the attempt cap is reached. A payload that is simply malformed is still
-- dropped on the first try. `processed_at` remains the queue marker, so the unprocessed index is
-- unchanged.

alter table raw_events add column attempts int not null default 0;
