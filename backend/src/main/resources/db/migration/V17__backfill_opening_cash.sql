-- Backfill opening_cash for statements computed before V16.
--
-- V16 added the column with a default of 0, so every round played before the deploy reports an
-- opening balance of zero and a movement equal to its whole closing cash -- wrong on both counts, and
-- visible to a class the moment they open the debrief.
--
-- The figure is recoverable exactly: `detail` has carried `cashChange` since the first round ever
-- computed, and opening = closing - movement by definition. Nothing is estimated and nothing is
-- re-run through the engine; a statement is corrected from its own stored row.
--
-- The `opening_cash = 0` guard keeps this idempotent and protects the legitimate case of a company
-- that really did start a round with nothing: such a row has a movement equal to its closing cash, so
-- the update would write the same value it already holds. Rows written after V16 are untouched because
-- the engine sets their opening balance directly.

update round_statement
set opening_cash = cash_after - ((detail ->> 'cashChange')::numeric)
where detail ? 'cashChange'
  and detail ->> 'cashChange' is not null
  and opening_cash = 0
  and cash_after is not null;

comment on column round_statement.opening_cash is
    'Cash the company held before the round was computed; cash_after - opening_cash is the cash movement';
