 # SleepyAddon

  A Meteor Client **cleaning & utility** addon.

  ## Modules

  - **AntiCheat**
    Manages internal addon settings, including AirPlace hand mode and Grim direction for mining/placement packets.
    *Note:* It effectively behaves like an always-on module, so enabling/disabling it usually doesn’t matter.

  - **AirPlace**
    Place the block in your hand anywhere (including mid-air). Range is configurable.

  - **Printer**
    A Litematica-style and Baritone selection printing tool integrated into SleepyAddon. It can:
    - Automatically place schematic blocks in survival
    - Fill Baritone selections
    - Automatically mine **wrong** and **extra** blocks
    - Use SilentMine for blocked paths and cleanup

  - **MossPlacer**
    Places moss on top of valid solid blocks, with optional side placement, depth control, and fading placement renders.

  - **GlowBerryPlacer**
    Places glow berries under valid overhead blocks and renders recent placements.

  - **MossSpreader**
    Bonemeals moss edges, clears blocking snow when enabled, crafts bone meal from bone blocks, and cleans junk inventory slots.

  - **RoofMosser**
    Processes a configurable square centered at 0,0 by default (10,000 x 10,000 blocks), one whole 16x16 chunk at a time; no Baritone selection is needed. It always begins with the unfinished chunk nearest the player. Helpers only enter the shared API key: the server address, project, username-based worker ID, and renewable lease are automatic. It covers the exposed upper surface of any solid roof blocks near the configured roof plane and builds a compact snake from only the missing moss visible in the loaded chunk. Each lane is handled as one entry/exit pair: an entirely finished lane is skipped, while an active lane is entered from its closest end and traversed to the other so completed entrances cannot collapse the snake into diagonal return trips. Reached waypoints hand their successor to Baritone in the same tick, and the planned successor is pre-claimed during the final lanes. Movement follows one simple limiter rule sampled before the current placement burst: Baritone's movement inputs are suppressed for only the current tick when all 9 placement slots in the rolling 300-ms window were already consumed, and movement continues as soon as even one slot is available. Finishing a sweep immediately transfers movement and placement to the next claimed chunk. The old chunk's two full verification scans and completion publishing run entirely in the background; missed or temporarily blocked columns release that old lease and enter deferred cleanup without reversing current movement. Rejected completions are likewise deferred instead of pulling the bot backward.
    Auto Regear places one moss shulker above the player and burst-moves as many stacks as fit while preserving a pickup slot, then waits for the server response. A full inventory is considered successful once the loose moss supply exceeds the refill threshold: SilentMine recovers the partial shulker into the reserved slot and work resumes. If the drop misses the player, the bot strafes directly onto the tracked item entity (or uses Baritone for an unusually distant/vertical drop) instead of waiting for it to despawn. Partially used boxes travel with the bot and are reopened later. At recorded containers, any shulker with at least one moss block is valid even when it contains other items; the bot takes the least-filled one first, removes only its moss, and returns that tracked box after its moss is exhausted. Use the module's `Set Regear Stations` button, right-click each container, then press Shift.
    Regear trips automatically use Baritone's updated Overworld Elytra process unless the destination is on roughly the same level and within 10 blocks. Roof Mosser equips the carried Elytra and restores the chestplate using cursor-safe `SWAP` clicks only. SleepyAddon also patches Meteor Chest Swap and Auto Replenish to use cursor-safe whole-stack `SWAP` clicks instead of `PICKUP` inventory moves. Before leaving the ground, Roof Mosser submits the goal and waits for Baritone to produce a non-empty Elytra path. Takeoff then mirrors Syntaxia Normal + Grim Strict: one grounded tick performs only the vanilla jump, then a later tick that actually observes airborne state sends `START_FALL_FLYING`, starts client gliding, and uses a rocket through a cursor-safe silent `SWAP`. A brief upward-pitch guard bridges the launch until Baritone begins steering the already calculated route. Only after that airborne pulse does Timer slow the client to 0.1x until the server confirms gliding and the attached boost rocket appears. Failed boosts and stalled flights restart this lifecycle instead of falling back to long-distance walking. Install the patched Baritone jar that matches the Minecraft version; the original 1.21.5 v1.14.0 release still has the Nether/Y-127 restriction despite sharing the same version string. See [Baritone Overworld Elytra setup](BARITONE-ELYTRA.md).
    The Safety toggles temporarily enable Meteor Auto Eat (pausing all Roof Mosser actions while eating) and configure Meteor Auto Log to disconnect on the first totem pop only. The player's previous module states and Auto Log settings are restored when Roof Mosser is disabled.
    The `Sneak Desync` HUD element ports Syntaxia's server-pose detector. Roof Mosser checks it only immediately before opening or placing a regear container. If the pose is desynced, that interaction waits for a timed sneak/unsneak repair sequence; normal movement and chunk work remain unaffected.

  - **SilentMine**
    A packet/silent mining module for **2b2t**, designed to make mining smoother, with queued mining, rebreak support, instant-break handling, and Printer mining integration.

  - **WorldEater**
    A large-area cleaning module for removing terrain and blocks. Designed for bigger cleanup jobs where normal manual mining or small Printer cleanup is too slow.


  ## Dependencies

  - [Meteor Client](https://meteorclient.com/archive)
  - [Litematica](https://modrinth.com/mod/litematica/versions)
  - [MaLiLib](https://modrinth.com/mod/malilib/versions)

  ## Credits

  - **SleepyFemboy** — I made this
  - **ChatGPT (OpenAI)** — GPT my beloved also yes it wrote most this README 💫
  - The **SilentMine** module is based on / adapted from Gonbleware:
    https://github.com/Shays-Forks/meteor-client-gonbleware-fork
