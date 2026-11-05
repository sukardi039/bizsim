-- An opponent plays when the round is closed before its moment (D56).
--
-- The seeded submit moment is 20–70% into a round. A facilitator who computes early — the normal
-- case in a workshop — used to close the round before that moment arrived, and the opponent then had
-- no decision at all: a company like any other on paper, but one that never had the chance a person
-- sitting at the keyboard had. It now decides at close, recorded as AT_CLOSE so the trace says which
-- of the three happened: scheduled, by hand, or at the close itself.

alter table bot_decision drop constraint if exists bot_decision_mode_check;

alter table bot_decision add constraint bot_decision_mode_check
    check (mode in ('SCHEDULED', 'MANUAL', 'AT_CLOSE', 'NONE'));

comment on column bot_decision.mode is
    'SCHEDULED = its own moment; MANUAL = a facilitator asked; AT_CLOSE = the round closed before that '
    'moment; NONE = the round closed and no decision was ever made';
