-- Searching the decision trail (E10 story 2): by the person who took each step. The period (occurred_at) already has
-- ix_decision_records_occurred (V36); a service, its GIN index. The step itself (action) has ten values and is
-- always searched within an org, beside one of these, so it gets none.
create index ix_decision_records_actor on decision_records (org_id, actor_id, seq);
