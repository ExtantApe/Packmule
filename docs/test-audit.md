# Test audit

Kept by this repo's owning session (Minecraft: Plugins Lead), per `~/Code/CLAUDE.md`, "Test self-audits". Newest first.

## 2026-11-09: first full audit (at 0.2.0)

Each README promise and the test that holds it. "Bots" is `test/suite.mjs` on throwaway Paper 26.1.2, 26.2 and 26.3 with a local pack host; "unit" is `src/test`.

| Promise | Covered by |
| --- | --- |
| Ordered per-world list, sent together; empty list sends nothing | unit `PackPlanTest` (order, inserted pack, empty list); bots "a world with two packs gets both, in order", "an empty list removes Packmule's" |
| Same packs across worlds send nothing; different packs swap only Packmule's | unit `samePacksChangeNothing`, `swapReplacesTheWholeList`, `sharedStartIsKept`; bots teleport, same pack, End, swap |
| Never removes or replaces another plugin's pack | unit `emptyListRemovesOnlyPackmulesPacks`; bots "Packmule never removed a pack it didn't send", BlueMapMini's pack through switches, reloads and hopping |
| Every pack required: decline kicked, no answer kicked after 60 s | bots "a decline is kicked", "kicked after the timeout"; the 60 s default in the long run (`PACKMULE_TEST_LONG`) |
| Failed download retried twice, then the player stays in; admins told | bots retry, "the player stays in without it", admin warning, quiet once retries run out, recovery |
| **Players never see pack names, world names or addresses** | bots "the player is told once for the world… without internal names" |
| Startup check: 404, timeout, wrong hash, bad URL reported with worlds; the rest keep working; startup doesn't wait | unit `LinkCheckerTest` (each failure kind); bots startup section |
| `/packmule recheck`; players online keep their packs | bots "a re-check leaves players online alone", new hash on next world change and join |
| `.sha1` problems keep the last good hash | unit `brokenHashFileKeepsTheLastGoodHash`, `hashStoreSurvivesARestart`; bots |
| `server.properties` pack warns | bots |
| **Fail closed on a broken config:** reload keeps the old one; startup sends no packs and players still get in | bots "a reload with broken config warns and keeps the old config", "fails safe: players get in, with no packs from Packmule and no kick" |
| Commands need permission | bots "a player without permission can't…", "an op can" |
| Pack IDs are stable (clients see them) | unit `packIdsUseThePackmuleNamespace` |

**Gaps added this audit:** none. Every promise already had a test.

**Skipped, with reasons:**
- "No background polling": it's an absence of code (there's no scheduled check); a test watching for traffic over time would prove little more than reading `Packmule.java`, done at this audit.
- How the prompt and kick messages read on screen, and how a real loading screen feels: real client only, listed under "What hasn't been tested".

## 2026-11-15: gated packs (0.4.0)

| Promise | Test |
| --- | --- |
| A gated pack is checked at the local address with the token, and players keep the public address | unit `checksAtTheLocalAddressWithTheTokenAndPlayersKeepThePublicOne` |
| Without a check block the gate refuses Packmule's check | unit `withoutTheCheckBlockTheGateRefusesTheCheck` |
| **The token never leaves this machine:** non-loopback, user-part, wrong-prefix and incomplete check blocks are config errors | unit `refusesACheckBlockThatCouldSendTheTokenAway`, `loopbackMeansThisMachineOnly` |
| **The token never follows a redirect** or goes to an address outside the public prefix | unit `theTokenNeverFollowsARedirect`, `addressesOutsideThePublicPrefixGoWithoutTheToken` |
| **Fail closed on the token file:** shared (not 600), linked, missing or malformed files fail the check, and the token isn't in the reason | unit `aTokenFileOthersCanReadIsRefusedAndNothingIsSent`, `aLinkedTokenFileIsRefused`, `aMissingOrMalformedTokenFileIsRefused`, `aWrongTokenIsDead` |
| The check block is read | unit `readsAGatedPacksCheckBlock` |

**Gaps:** `/packmule pack set` keeping the block is a three-line lookup in `Packmule.gatedCheck`, covered by reading it; a bot test needs Leaf Juice's half (D220), so it runs with the refusal tests against the real gate.
**Skipped:** a file owned by another user, since a test can't create one without root; the owner comparison is one line next to the tested mode check.

## 2026-11-16: cleanup (D226)

- **Covered:** the harness records each server folder it creates and each Java process it starts. When the run ends (pass, fail, an uncaught error, Ctrl-C, SIGTERM or SIGHUP) it kills those processes and deletes those folders, and nothing else. A folder from an earlier run that's in the way stops the run; it isn't deleted. `KEEP_TEST_SERVERS=1` keeps the folders for debugging.
- **Checked by hand:** on MobShowdown, a full run (42/42) and a run interrupted after 45 seconds both left the work folder empty, with no Java left and the test port closed. The other harnesses use the same code.
- **Gap skipped:** the suite can't check its own leftovers, because cleanup runs as the process exits. A wrapper that checks after the suite exits would add more machinery than the check is worth here.
