-- Beside the AI's reachability answer (E11-5, V41): what a reviewer should verify that the code it read can't show
-- (checks, at most three), and what the code was searched for — names the advisory or its fix commit writes, each as
-- 'lines:files:term' (searches). Neither holds code. Rows kept before this hold neither, as if nothing was searched.
alter table reachability_assessments add column checks text[] not null default '{}';
alter table reachability_assessments add column searches text[] not null default '{}';
