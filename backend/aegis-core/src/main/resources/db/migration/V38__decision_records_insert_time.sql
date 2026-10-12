-- A decision record's time is when the record was written, as its sequence is (E10 review). occurred_at defaulted to
-- now(), the start of the writing transaction, while seq is drawn as the row is inserted: two decisions written at the
-- same moment could be listed, in seq order, against the order of their times. clock_timestamp() is read as the row is
-- inserted too, so the two agree but for decisions inserted within microseconds of each other. Records in one
-- transaction (a ticket and the pull request drafted with it) now carry their own times rather than sharing one.
-- Existing records keep theirs: the trail is append-only.
alter table decision_records alter column occurred_at set default clock_timestamp();
