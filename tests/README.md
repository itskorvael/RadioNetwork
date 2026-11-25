# Probes

`run-probes.sh` builds RadioNetwork for Paper 26.1.2, starts a scratch Paper server with the plugin and
`probe/RadioProbe.java`, and prints one PASS or FAIL line per check. It exits 0 only if every check passed.

```
tests/run-probes.sh        # everything
tests/run-probes.sh -q     # only failures and the summary
KEEP=1 tests/run-probes.sh # keep the scratch server and its log
```

It needs a Paper 26.1.2 server folder to copy (`PAPER_DIR`, default `../ExileBounty/run/paper-26.1.2`) and a
Java 25 runtime (`JAVA25`, default the one Gradle downloaded). Nothing is downloaded.

What it covers (about 290 checks): signal-strength garble and dropped words, encryption noise, alert and
channel-tag formats, the documented config defaults, every item and its recognition, frequency access
and passwords, terminals and fuel, station announcements (speaker check, search radius, sign log), relays,
the jammer (waxed-bulb block, status light, warm-up, jam holding through the countdown, burn-out, lightning
overheat), decommissioned items and blocks (items, recipes, chunk marks, never registered) and the
scanner's numbers, and local chat (distance rules, the whisper and shout tiers, defaults, permissions, and that `/w` resolves to `/whisper` rather than vanilla's). Block breaking, explosions, pistons, the crafting grid and the chat event and the commands' delivery (who actually receives a message) need a real player and are not covered.

The config-default checks are deliberate: change a default on purpose and update the number here too.
The probe drives the plugin's classes directly, without players, so anything that needs a real client
(chat colours, the action bar, menus, sounds and particles) still has to be looked at in the game.
