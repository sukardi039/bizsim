-- V47: a read review is sticky, and the charge follows the status at settlement (D125).
--
-- D124 made the purchase sticky at *submission*, which withdrew the trainee's ability to change their mind
-- before they had seen anything: a plan submitted with the box ticked could not be un-ticked, and the round
-- was billed whether or not the report was ever opened.
--
-- The owner's rule (23 Nov 2026): the box stays unticked-and-changeable until the report is **read**. Once
-- read, it cannot be un-ticked. Charging is decided by the purchase status recorded on the *final* submission
-- of the round — the last plan submitted before the round closes, or the auto-computed plan on timeout.
--
-- So `report_purchase` stops being "a purchase was submitted" and becomes "the review was read for this
-- round". The charge still comes from `decision.payload.marketReport` at settlement, exactly as before: the
-- read record only stops the trainee withdrawing it afterwards.

comment on table report_purchase is
    'D125: the market review was READ for this round. Reading is what locks the box; the charge follows the final submission.';

alter table report_purchase add column read_at timestamptz;
update report_purchase set read_at = bought_at where read_at is null;
alter table report_purchase alter column read_at set not null;
alter table report_purchase alter column read_at set default now();
