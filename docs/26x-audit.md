# 26.x audit

Butler targets Minecraft 26.1 and up: `api-version: '26.1'`, Java 25, compiled against `paper-api 26.1.2.build.74-stable`, the oldest supported version, so nothing newer can slip in. 1.21.x support was dropped on 2026-11-07. There were no 1.21-only code paths to remove; only the build target, the bot-test versions and the docs changed.

The comparison was against Paper's current docs (paper-plugins, command API, project setup) and the 26.1.2 API jar itself. Compiling with `-Xlint:deprecation,removal` against that jar gives no warnings.

## Modernised

- **Commands use Brigadier.** `/butler` is registered in `LifecycleEvents.COMMANDS`, with a `requires` check for each subcommand and player-name suggestions for `status`. The `commands:` block and the old executor and tab completer are gone. Players without a permission no longer see the subcommand at all.
- **`paper-plugin.yml`, not `plugin.yml`.** The owner's rule is Paper only. Paper's docs still mark Paper plugins as experimental, but Butler uses none of the experimental parts: no bootstrapper, no loader, no dependencies. The `permissions` block is read by Paper's `PaperPluginMeta` on 26.1.2 (checked in the server jar). Commands can't be declared there, which suits Brigadier. Classloader isolation doesn't matter: Butler calls into no other plugin.
- **Java 25.** The status listing uses a pattern-matching `switch` with `case null` and guards. Records were already used for config and check results.

## Kept, and why

- **Sending packs at join, not during configuration.** 26.1 has `AsyncPlayerConnectionConfigureEvent`, so packs could load on the join screen. But Butler picks packs by world, and before join there's no `Player`, and so no world, short of reading the player's save file. A second send path with a guessed world, and its own status handling, is more to get wrong than the one join-time path. It's worth a look if real clients show that the join-time prompt is disruptive.
- **Adventure throughout.** `ResourcePackRequest` and `ResourcePackInfo`, `Audience#removeResourcePacks`, and `Component` messages were already in use. There are no legacy colour codes.
- **`PlayerResourcePackStatusEvent`.** It's still the event for per-pack statuses with IDs in 26.1, and isn't deprecated.
- **Bukkit scheduler and `YamlConfiguration`.** Both are current and not deprecated. Butler isn't meant for Folia.
- **Not applicable:** data components, registries, display entities and dialogs. Butler has no items, blocks or UI beyond the client's own pack prompt.

## Tested

Bots on throwaway Paper 26.1.2, 26.2 and 26.3 servers, connecting as 26.1 clients: directly on 26.1.2, and through ViaVersion and ViaBackwards on 26.2 and 26.3. The packs are made-up zips served by a local web server, and the bots answer pack requests the way the client's pack manager does. On 26.3 the bots don't move themselves, because ViaBackwards gets moving bots kicked there, so the Waystation gate walk was tested on 26.1.2 and 26.2 only. No real client has been used. See the README's test section.
