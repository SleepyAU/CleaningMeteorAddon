# Baritone Overworld Elytra builds

Roof Mosser uses the Overworld/End Elytra support merged upstream in May 2026. The local patch only adds moss blocks and crying obsidian to Baritone's safe landing-block allowlist; the upstream implementation already supports Overworld flight, above-build-limit routes, obsidian landings, and the public `IElytraProcess.pathTo` API.

The release bundle contains a separate patched Baritone build for each supported Minecraft version. Always pair like versions:

- Minecraft 1.21.1: official Baritone `1.21.1` branch at `11ea66c`
- Minecraft 1.21.4: official Baritone `1.21.4` branch at `e8a45bf` (the final native-pathfinder commit before the experimental Java-pathfinder merge)
- Minecraft 1.21.5: official Baritone `1.21.5` branch at `b60a3e5`
- Minecraft 1.21.8: official Baritone `1.21.8` branch at `b4599b4`

Build it with:

```bash
./scripts/build-baritone-overworld-elytra.sh
```

The default script builds the 1.21.5 artifact. Release bundles include the other three builds from their matching official branches. Remove any old Baritone jar from the client's mods directory before installing the matching patched jar; two Baritone jars cannot be loaded together.

Roof Mosser feature-detects the new API rather than trusting the unchanged version string. Regear travel automatically uses Elytra unless the destination is within 10 horizontal blocks and roughly the same height. It equips the inventory Elytra and restores the chestplate using only cursor-safe `SWAP` slot actions. It starts gliding with a flat jump instead of making Baritone search for a drop, and temporarily applies Meteor Timer's 0.1x override until the server glide flag and Baritone's attached boost rocket are both confirmed. It temporarily enables Baritone's above-build-limit support, then restores the user's Baritone settings. It falls back to walking if the updated Elytra process is missing, unsupported on the machine, finishes outside interaction range, or stalls.

Carry a usable Elytra and enough fireworks, and test the flat takeoff and landing behavior attended before leaving a client AFK on 2b2t.
