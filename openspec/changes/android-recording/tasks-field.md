# Field tasks (real runs)

These tasks were moved out of `tasks.md` on 2026-11-16 at the owner's request: each needs a real run with the Pixel 8 and the Galaxy Watch 4, which will be done later. They are **not counted** in the progress OpenSpec reports for the change, so tick them here. They keep their original numbers; `running-foundations` task 3.9 is here too because it is satisfied by the same file as 10.5.

Neither change should be archived before this list is done.

## Before the run

- Install the current builds on both devices and pair the phone with the home server (`docs/android.md`).
- Note the watch battery level, and pick a route whose length is known.

## During and after one run of at least one hour

- [ ] 10.1 **(device)** Record a run of at least one hour with the phone screen off and the watch joined, with laps, a manual pause and a stop at a crossing; verify the trace is continuous, heart rate covers the whole run, and distance is within 2 % of a known route length
- [ ] 10.3 **(device)** Measure watch battery over the one-hour run and walk away from the phone for two minutes during a run; verify the drop is 12 points or fewer, heart rate is present over the dropout, and both figures are recorded in `docs/android.md`
- [ ] 10.2 **(device)** Come home with the app closed; verify the run appears on the server within 15 minutes with the same laps and pauses, a distance within 1 % of the phone's, and the effort and note entered on the phone
- [ ] 10.4 **(device)** Observe the phone's network traffic during a run and the following sync; verify the app connects only to the configured server

## With the recorded file

- [ ] 10.5 Copy the field-test FIT file into `testdata/` with its key figures in `testdata/README.md`; verify the server's real-file test passes on it
- [ ] 3.9 (`running-foundations`) Extend the server's real-file test to assert lap count and average heart rate against that file's own lap and session messages; verify the test passes on that file

## Closing

- [ ] 10.6b Walk every scenario of the six `android-recording` specs; verify each has been checked on the devices (checklists in `docs/android.md`) or by an automated test, then tune the hold durations for unlock and stop if the run showed they need it (open question in `design.md`)
